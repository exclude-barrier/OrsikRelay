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

    def invoke(self, args=(), answer='j\n', tty=True):
        output = Tty() if tty else io.StringIO()
        with mock.patch.dict(os.environ, self.env, clear=True), \
                mock.patch.object(update.sys, 'prefix', str(self.prefix)), \
                mock.patch('sys.stdin', Tty(answer) if tty else io.StringIO(answer)), \
                mock.patch('sys.stdout', output):
            return server.main(['update', *args]), output.getvalue()

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
            self.assertNotIn('ORSIK_REF', env)
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
