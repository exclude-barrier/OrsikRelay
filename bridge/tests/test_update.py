"""Update wrapper: installed bootstrap, fresh source, explicit operator consent."""
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

from orsik_mobile_bridge import server, update


class Tty(io.StringIO):
    def isatty(self):
        return True


class UpdateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.home = self.root / 'bridge'
        self.home.mkdir()
        self.config = self.home / 'bridge.env'
        self.config.write_text('BRIDGE_TERMINAL=0\nBRIDGE_HERMES_ENV="' + str(self.root / 'hermes/.env') + '"\n')
        self.config.chmod(0o600)
        self.prefix = self.home / 'venv'
        self.script = self.prefix / 'share/orsik-mobile-bridge/install-bridge.sh'
        self.script.parent.mkdir(parents=True)
        self.script.write_text('#!/bin/bash\nexit 0\n')
        self.script.chmod(0o644)
        self.env = {'BRIDGE_HOME': str(self.home), 'BRIDGE_ENV_FILE': str(self.config),
                    'ORSIK_DIR': str(self.root / 'do-not-touch'), 'ORSIK_REF': 'do-not-inherit'}

    def invoke(self, args=(), answer='y\n', tty=True, release_error=None, matches=False):
        output = Tty() if tty else io.StringIO()
        with mock.patch.dict(os.environ, self.env, clear=True), \
                mock.patch.object(update.sys, 'prefix', str(self.prefix)), \
                mock.patch.object(update, '_latest_release', return_value=('v0.1.0-alpha.17-bridge.1', 'a' * 40), side_effect=release_error), \
                mock.patch.object(update, '_matches_release', return_value=matches), \
                mock.patch('sys.stdin', Tty(answer) if tty else io.StringIO(answer)), \
                mock.patch('sys.stdout', output):
            return server.main(['update', *args]), output.getvalue()

    def test_default_is_english_even_with_german_locale(self):
        self.env['LANG'] = 'de_DE.UTF-8'
        code, output = self.invoke(answer='n\n')
        self.assertEqual(code, 0)
        self.assertIn('Update to the latest release tag?', output)
        self.assertIn('Start update? [y/N]', output)
        self.assertNotIn('Gerätestore', output)

    def test_saved_german_is_used_for_prompt_and_errors(self):
        self.config.write_text(self.config.read_text() + 'OMB_LANGUAGE=de\n')
        code, output = self.invoke(answer='n\n')
        self.assertEqual(code, 0)
        self.assertIn('Gerätestore, behält', output)
        self.assertIn('Er prüft Hermes', output)
        self.script.unlink()
        code, output = self.invoke(['--yes'])
        self.assertEqual(code, 1)
        self.assertIn('regulären Installer', output)

    def test_english_yes_is_accepted_and_german_yes_is_language_specific(self):
        with mock.patch.object(update.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as run:
            code, output = self.invoke(answer='y\n')
        self.assertEqual(code, 0)
        run.assert_called_once()

    def test_current_version_never_prompts_installs_or_restarts(self):
        marker = self.home / 'installed-commit'
        marker.write_text('a' * 40 + '\n')
        marker.chmod(0o600)
        with mock.patch.object(update.subprocess, 'run') as run:
            code, output = self.invoke(answer='y\n')
        self.assertEqual(code, 0)
        self.assertIn('already up to date', output)
        self.assertNotIn('Start update?', output)
        run.assert_not_called()

    def test_unknown_installed_version_is_reported_before_confirmation(self):
        code, output = self.invoke(answer='n\n')
        self.assertEqual(code, 0)
        self.assertIn('could not be identified reliably', output)

    def test_failed_release_check_does_not_run_installer(self):
        with mock.patch.object(update.subprocess, 'run') as run:
            code, output = self.invoke(['--yes'], release_error=update.SetupError('update_check_failed'))
        self.assertEqual(code, 1)
        self.assertIn('Update check failed', output)
        run.assert_not_called()

    def test_explicit_language_overrides_saved_language(self):
        self.config.write_text(self.config.read_text() + 'OMB_LANGUAGE=de\n')
        code, output = self.invoke(['--language', 'en'], answer='n\n')
        self.assertEqual(code, 0)
        self.assertIn('Start update? [y/N]', output)
        self.assertNotIn('Update starten?', output)

    def test_installed_marker_rejects_symlinks_invalid_and_writable_content(self):
        marker = self.home / 'installed-commit'
        marker.symlink_to(self.config)
        self.assertIsNone(update._installed_commit(self.home))
        marker.unlink()
        marker.write_text('bad')
        self.assertIsNone(update._installed_commit(self.home))
        marker.write_text('a' * 40)
        marker.chmod(0o666)
        self.assertIsNone(update._installed_commit(self.home))

    def test_release_probe_pins_resolved_commit_and_has_bounded_network_wait(self):
        with mock.patch.object(update.subprocess, 'run') as clone, \
                mock.patch.object(update.subprocess, 'check_output', side_effect=['v0.1.0-alpha.17-bridge.1\n', 'b' * 40 + '\n']) as git:
            tag, commit = update._latest_release(self.root / 'source')
        self.assertEqual(commit, 'b' * 40)
        self.assertEqual(tag, 'v0.1.0-alpha.17-bridge.1')
        self.assertEqual(clone.call_args.kwargs['timeout'], 60)
        self.assertEqual(git.call_count, 2)
        self.assertIn('origin/main', git.call_args_list[0].args[0])

    def test_exact_installed_files_are_recognized_without_a_marker(self):
        import io
        import tarfile
        package = self.root / 'installed-package'
        package.mkdir()
        (package / '__init__.py').write_bytes(b'# package')
        (package / 'update.py').write_bytes(b'# updater')
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode='w') as tar:
            for name, content in {
                'bridge/orsik_mobile_bridge/__init__.py': b'# package',
                'bridge/orsik_mobile_bridge/update.py': b'# updater',
                'bridge/install-bridge.sh': b'# installer',
            }.items():
                entry = tarfile.TarInfo(name)
                entry.size = len(content)
                tar.addfile(entry, io.BytesIO(content))
        with mock.patch.object(update.subprocess, 'check_output', return_value=archive.getvalue()), \
                mock.patch.object(update, '__file__', str(package / 'update.py')):
            self.assertTrue(update._matches_release(self.root / 'source', 'a' * 40, b'# installer'))
            (package / 'update.py').write_bytes(b'# different')
            self.assertFalse(update._matches_release(self.root / 'source', 'a' * 40, b'# installer'))
            (package / 'update.py').write_bytes(b'# updater')
            (package / 'extra.py').write_bytes(b'# extra')
            self.assertFalse(update._matches_release(self.root / 'source', 'a' * 40, b'# installer'))

    def test_matching_legacy_install_never_prompts_or_installs(self):
        with mock.patch.object(update.subprocess, 'run') as run:
            code, output = self.invoke(matches=True)
        self.assertEqual(code, 0)
        self.assertIn('already up to date', output)
        self.assertNotIn('Start update?', output)
        run.assert_not_called()

    def test_successful_legacy_installer_also_records_revision(self):
        with mock.patch.object(update.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)):
            code, _ = self.invoke(['--yes'])
        self.assertEqual(code, 0)
        self.assertEqual(update._installed_commit(self.home), 'a' * 40)

    def test_cancel_or_eof_never_runs_installer(self):
        for answer in ('n\n', ''):
            with mock.patch.object(update.subprocess, 'run') as run:
                code, _ = self.invoke(answer=answer)
                self.assertEqual(code, 0)
                run.assert_not_called()

    def test_noninteractive_needs_explicit_yes(self):
        with mock.patch.object(update.subprocess, 'run') as run:
            code, _ = self.invoke(tty=False)
        self.assertEqual(code, 1)
        run.assert_not_called()

    def test_uses_bundled_installer_and_fresh_source_preserves_config(self):
        original = self.config.read_bytes()
        workspace = self.root / 'do-not-touch'
        workspace.mkdir()
        marker = workspace / 'omp-work'
        marker.write_text('keep')
        def run(command, *, env, check):
            script = Path(command[1])
            self.assertEqual(command[0], 'bash')
            self.assertEqual(command[2:], ['--no-service'])
            self.assertEqual(script.read_bytes(), self.script.read_bytes())
            self.assertNotEqual(env['ORSIK_DIR'], str(workspace))
            self.assertFalse(Path(env['ORSIK_DIR']).exists())
            self.assertEqual(env['BRIDGE_HOME'], str(self.home))
            self.assertEqual(env['HERMES_HOME'], str(self.root / 'hermes'))
            self.assertEqual(env['ORSIK_REF'], 'a' * 40)
            self.executed_script = script
            return subprocess.CompletedProcess(command, 0)
        with mock.patch.object(update.subprocess, 'run', side_effect=run):
            code, _ = self.invoke(['--no-service'])
        self.assertEqual(code, 0)
        self.assertFalse(self.executed_script.parent.exists())
        self.assertEqual(self.config.read_bytes(), original)
        self.assertEqual(marker.read_text(), 'keep')

    def test_failure_exit_status_propagates_without_automatic_retry(self):
        with mock.patch.object(update.subprocess, 'run', return_value=subprocess.CompletedProcess([], 7)) as run:
            code, _ = self.invoke(['--yes'], tty=False)
        self.assertEqual(code, 7)
        run.assert_called_once()

    def test_missing_or_writable_bootstrap_is_refused(self):
        self.script.unlink()
        with mock.patch.object(update.subprocess, 'run') as run:
            code, _ = self.invoke(['--yes'])
            self.assertEqual(code, 1)
            run.assert_not_called()
        self.script.write_text('exit 0')
        self.script.chmod(0o666)
        with mock.patch.object(update.subprocess, 'run') as run:
            code, _ = self.invoke(['--yes'])
            self.assertEqual(code, 1)
            run.assert_not_called()

    def test_external_config_is_not_silently_replaced_by_default_config(self):
        other = self.root / 'other.env'
        other.write_bytes(self.config.read_bytes())
        other.chmod(0o600)
        with mock.patch.object(update.subprocess, 'run') as run:
            code, _ = self.invoke(['--env-file', str(other), '--yes'])
        self.assertEqual(code, 1)
        run.assert_not_called()

    def test_help_does_not_require_hermes_credentials(self):
        with self.assertRaises(SystemExit) as result:
            self.invoke(['--help'])
        self.assertEqual(result.exception.code, 0)

    def test_regular_installer_hardens_packaged_bootstrap_mode(self):
        installer = (Path(__file__).parent.parent / 'install.sh').read_text()
        command = next(line for line in installer.splitlines()
                       if line.startswith('chmod 600 ') and '/share/orsik-mobile-bridge/install-bridge.sh' in line)
        self.script.chmod(0o775)
        subprocess.run(['bash', '-c', command], env={**os.environ, 'BRIDGE_HOME': str(self.home)}, check=True)
        self.assertEqual(self.script.stat().st_mode & 0o777, 0o600)
