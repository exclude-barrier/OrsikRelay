"""Local setup: explicit changes, secret preservation and existing access rules."""
import io
import os
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest import mock

from orsik_mobile_bridge import setup, server, store


class Tty(io.StringIO):
    def isatty(self):
        return True


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

    def menu(self, answers):
        output = Tty()
        with mock.patch.dict(os.environ, self.env, clear=True), \
                mock.patch("sys.stdin", Tty(answers)), mock.patch("sys.stdout", output):
            result = server.main(["setup"])
        self.assertNotIn("synthetic-only", output.getvalue())
        return result, output.getvalue()

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

    def test_quit_eof_and_interrupt_do_not_save(self):
        for answers in ('1\nq\n', '1\n', '1\ns\nn\nq\n'):
            result, _ = self.menu(answers)
            self.assertEqual(result, 0)
            self.assertEqual(self.path.read_text(), self.original)
        with mock.patch("builtins.input", side_effect=KeyboardInterrupt):
            result, _ = self.menu('')
        self.assertEqual(result, 0)

    def test_terminal_save_is_explicit_and_checks_required_programs(self):
        with mock.patch.object(server.terminal, "default_command", return_value=("tmux",)) as check:
            result, output = self.menu('1\ns\nj\n')
        self.assertEqual(result, 0)
        check.assert_called_once()
        self.assertEqual(server._load_env_file(str(self.path))["BRIDGE_TERMINAL"], "1")
        self.assertIn("systemctl --user restart orsik-mobile-bridge.service", output)

    def test_missing_terminal_dependency_prevents_save(self):
        with mock.patch.object(server.terminal, "default_command", side_effect=SystemExit("missing tmux")):
            result, output = self.menu('1\ns\nj\nq\n')
        self.assertEqual(result, 0)
        self.assertEqual(self.path.read_text(), self.original)
        self.assertIn("missing tmux", output)

    def test_files_use_existing_protected_root_validation(self):
        workspace = self.root / "work space"
        workspace.mkdir()
        result, _ = self.menu(f'3\n{workspace}\n2\ns\nj\n')
        self.assertEqual(result, 0)
        values = server._load_env_file(str(self.path))
        self.assertEqual(values["BRIDGE_FILES"], "1")
        self.assertEqual(values["BRIDGE_FILES_ROOT"], str(workspace))
        # Never broaden the file root into the bridge's private directory.
        result, _ = self.menu(f'3\n{self.home}\ns\nj\nq\n')
        self.assertEqual(result, 0)
        self.assertEqual(server._load_env_file(str(self.path))["BRIDGE_FILES_ROOT"], str(workspace))

    def test_device_selection_requires_confirmation_and_preserves_chat(self):
        db = store.BridgeStore(str(self.home / "state.db"))
        self.addCleanup(db.close)
        token, device = db.mint_device("phone\x1b[2J")
        other_token, _ = db.mint_device("other")
        self.menu('4\n1\nn\n0\nq\n')
        self.assertFalse(db.lookup_device(token).terminal_allowed)
        _, output = self.menu('4\n1\nj\n0\nq\n')
        self.assertTrue(db.lookup_device(token).terminal_allowed)
        self.assertFalse(db.lookup_device(other_token).terminal_allowed)
        self.assertNotIn('\x1b', output)
        self.assertNotIn(token, output)
        self.menu('4\n1\nj\n0\nq\n')
        self.assertFalse(db.lookup_device(token).terminal_allowed)
        self.assertEqual(db.lookup_device(token).device_id, device.device_id)

    def test_missing_store_is_not_created_by_device_menu(self):
        self.menu('4\nq\n')
        self.assertFalse((self.home / "state.db").exists())

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

    def test_relative_state_path_is_resolved_against_service_home(self):
        self.path.write_text(self.original + 'BRIDGE_STATE_DB=data/state.db\n')
        self.env['HOME'] = str(self.root)
        (self.root / 'data').mkdir()
        db = store.BridgeStore(str(self.root / 'data' / 'state.db'))
        self.addCleanup(db.close)
        token, _ = db.mint_device('phone')
        self.menu('4\n1\nj\n0\nq\n')
        self.assertTrue(db.lookup_device(token).terminal_allowed)

    def test_oversized_device_number_is_rejected_without_crash_or_grant(self):
        db = store.BridgeStore(str(self.home / 'state.db'))
        self.addCleanup(db.close)
        token, _ = db.mint_device('phone')
        result, output = self.menu('4\n' + '9' * 5000 + '\n0\nq\n')
        self.assertEqual(result, 0)
        self.assertIn('Ungueltige Auswahl.', output)
        self.assertFalse(db.lookup_device(token).terminal_allowed)

    def test_empty_home_override_still_uses_adjacent_installation(self):
        with mock.patch.object(setup.sys, 'prefix', str(self.home / 'venv')):
            settings = setup.SettingsFile(self.path, {'BRIDGE_HOME': ''})
        self.assertEqual(settings.values['BRIDGE_HOME'], str(self.home))
