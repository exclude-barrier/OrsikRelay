"""Private settings and CLI contracts; arrow/Enter interaction lives in test_setup_tui."""
import io
import os
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest import mock

from orsik_mobile_bridge import setup, server, store


class SetupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.home = self.root / "bridge"
        self.home.mkdir()
        self.path = self.home / "bridge.env"
        self.original = '# keep comment\nHERMES_API_KEY="synthetic-only"\nBRIDGE_TERMINAL=0\nBRIDGE_FILES=0\n'
        self.path.write_text(self.original)
        self.path.chmod(0o600)
        self.env = {"BRIDGE_HOME": str(self.home), "BRIDGE_ENV_FILE": str(self.path)}

    def settings(self):
        return setup.SettingsFile(self.path, self.env)

    def test_save_preserves_unrelated_lines_and_uses_private_mode(self):
        self.path.write_text(self.original + ' export BRIDGE_TERMINAL="0"\n')
        settings = self.settings()
        settings.save({"BRIDGE_TERMINAL": "1"})
        values = server._load_env_file(str(self.path))
        self.assertEqual(values["BRIDGE_TERMINAL"], "1")
        self.assertIn('# keep comment\nHERMES_API_KEY="synthetic-only"\n', self.path.read_text())
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(sorted(p.name for p in self.home.iterdir()), ["bridge.env"])

    def test_concurrent_edit_is_not_overwritten(self):
        settings = self.settings()
        external = self.original + '# another operator\n'
        self.path.write_text(external)
        with self.assertRaises(setup.SetupError):
            settings.save({"BRIDGE_TERMINAL": "1"})
        self.assertEqual(self.path.read_text(), external)

    def test_failed_replace_keeps_original_and_removes_temporary_file(self):
        settings = self.settings()
        with mock.patch.object(setup.os, 'replace', side_effect=PermissionError):
            with self.assertRaises(setup.SetupError):
                settings.save({'BRIDGE_TERMINAL': '1'})
        self.assertEqual(self.path.read_text(), self.original)
        self.assertEqual(sorted(p.name for p in self.home.iterdir()), ['bridge.env'])

    def test_symlink_or_public_config_is_refused(self):
        link = self.home / "link.env"
        link.symlink_to(self.path)
        with self.assertRaises(setup.SetupError):
            setup.SettingsFile(link, self.env)
        self.path.chmod(0o644)
        with self.assertRaises(setup.SetupError):
            self.settings()

    def test_only_allowlisted_single_line_settings_can_be_written(self):
        settings = self.settings()
        for changes in ({"HERMES_API_KEY": "replacement"}, {"BRIDGE_FILES_ROOT": "/x\nBRIDGE_BIND=0.0.0.0"}):
            with self.assertRaises(setup.SetupError):
                settings.save(changes)
        self.assertEqual(self.path.read_text(), self.original)

    def test_non_interactive_invocation_is_refused(self):
        with mock.patch.dict(os.environ, self.env, clear=True), \
                mock.patch("sys.stdin", io.StringIO()), mock.patch("sys.stdout", io.StringIO()):
            self.assertEqual(server.main(["setup"]), 1)
        self.assertEqual(self.path.read_text(), self.original)

    def test_default_env_path_uses_bridge_home_and_override_wins(self):
        with mock.patch.dict(os.environ, {"BRIDGE_HOME": str(self.home)}, clear=True):
            self.assertEqual(setup.config_path(None), self.path)
        with mock.patch.dict(os.environ, self.env, clear=True):
            self.assertEqual(setup.config_path("/explicit/bridge.env"), Path('/explicit/bridge.env'))

    def test_shortcut_uses_adjacent_virtualenv_config_without_changing_other_cli_defaults(self):
        with mock.patch.dict(os.environ, {}, clear=True), \
                mock.patch.object(setup.sys, "prefix", str(self.home / "venv")):
            self.assertEqual(setup.config_path(None), self.path)
            settings = setup.SettingsFile(setup.config_path(None), {})
            self.assertEqual(settings.values['BRIDGE_HOME'], str(self.home))

    def test_installer_shortcut_does_not_replace_another_command(self):
        source = (Path(__file__).parent.parent / 'install.sh').read_text()
        function = source.split('# BEGIN OMB SHORTCUT\n', 1)[1].split('# END OMB SHORTCUT', 1)[0]
        env = {**os.environ, 'HOME': str(self.root), 'BRIDGE_HOME': str(self.home)}
        bin_path = self.root / '.local' / 'bin'
        subprocess.run(['bash', '-c', function + '\ninstall_omb_shortcut'], env=env, check=True, capture_output=True)
        link = bin_path / 'omb'
        self.assertEqual(os.readlink(link), str(self.home / 'venv' / 'bin' / 'omb'))
        link.unlink()
        link.write_text('foreign command')
        subprocess.run(['bash', '-c', function + '\ninstall_omb_shortcut'], env=env, check=True, capture_output=True)
        self.assertEqual(link.read_text(), 'foreign command')

    def test_empty_home_override_still_uses_adjacent_installation(self):
        with mock.patch.object(setup.sys, 'prefix', str(self.home / 'venv')):
            settings = setup.SettingsFile(self.path, {'BRIDGE_HOME': ''})
        self.assertEqual(settings.values['BRIDGE_HOME'], str(self.home))

    def test_toggle_twice_restores_clean_draft(self):
        session = setup.SetupSession(self.settings(), 'de')
        session.toggle('BRIDGE_TERMINAL')
        session.toggle('BRIDGE_TERMINAL')
        self.assertEqual(session.pending, {})
        self.assertEqual(self.path.read_text(), self.original)

    def test_multiple_saves_keep_previous_values(self):
        session = setup.SetupSession(self.settings(), 'en')
        session.toggle('BRIDGE_TERMINAL')
        with mock.patch.object(server.terminal, 'default_command', return_value=('tmux',)):
            session.save()
        session.toggle('BRIDGE_TERMINAL')
        session.save()
        self.assertEqual(server._load_env_file(str(self.path))['BRIDGE_TERMINAL'], '0')
        self.assertFalse(session.pending)

    def test_missing_terminal_tools_keeps_draft_and_file_unchanged(self):
        session = setup.SetupSession(self.settings(), 'de')
        session.toggle('BRIDGE_TERMINAL')
        message = 'BRIDGE_TERMINAL=1 needs tmux and setsid (util-linux) on the PATH'
        with mock.patch.object(server.terminal, 'default_command', side_effect=SystemExit(message)):
            with self.assertRaises(SystemExit) as error:
                session.save()
        self.assertEqual(self.path.read_text(), self.original)
        self.assertEqual(session.pending['BRIDGE_TERMINAL'], '1')
        self.assertIn('benötigt', setup.error_text(error.exception, 'de'))
        self.assertIn('requires', setup.error_text(error.exception, 'en'))

    def test_missing_store_is_not_created(self):
        session = setup.SetupSession(self.settings(), 'en')
        self.assertEqual(session.devices(), [])
        self.assertFalse((self.home / 'state.db').exists())

    def test_relative_state_path_is_resolved_against_user_home(self):
        self.path.write_text(self.original + 'BRIDGE_STATE_DB=data/state.db\n')
        (self.root / 'data').mkdir()
        db = store.BridgeStore(str(self.root / 'data/state.db'))
        self.addCleanup(db.close)
        token, device = db.mint_device('Büro')
        with mock.patch.dict(os.environ, {'HOME': str(self.root)}):
            session = setup.SetupSession(self.settings(), 'de')
            self.assertEqual(session.devices()[0]['device_id'], device.device_id)
            self.assertTrue(session.change_terminal(device.device_id, True))
        self.assertTrue(db.lookup_device(token).terminal_allowed)

    def test_english_and_german_noninteractive_errors(self):
        for lang, expected in [('de', 'interaktives Terminal'), ('en', 'interactive terminal')]:
            output = io.StringIO()
            with mock.patch('sys.stdin', io.StringIO()), mock.patch('sys.stdout', output):
                result = server.main(['setup', '--language', lang])
            self.assertEqual(result, 1)
            self.assertIn(expected, output.getvalue())

    def test_backend_errors_are_localized_without_touching_unknown_update_errors(self):
        error = setup.SetupError('config_write')
        self.assertIn('Prüfe', error.localized('de'))
        self.assertIn('permissions', error.localized('en'))
        self.assertEqual(setup.SetupError('update-specific').localized('en'), 'update-specific')
