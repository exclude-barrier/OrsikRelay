"""Exercise actual arrow/Enter bindings with prompt-toolkit's supported test I/O."""
import asyncio
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from prompt_toolkit.input import create_pipe_input
from prompt_toolkit.output import DummyOutput

from orsik_mobile_bridge import setup, setup_i18n, setup_tui, server, store

DOWN = '\x1b[B'
RIGHT = '\x1b[C'
ENTER = '\r'
QUIT = '\x11'


class SetupTuiTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.home = self.root / 'bridge'
        self.home.mkdir()
        self.path = self.home / 'bridge.env'
        self.original = '# unchanged\nHERMES_API_KEY=synthetic-only\nBRIDGE_TERMINAL=0\nBRIDGE_FILES=0\n'
        self.path.write_text(self.original)
        self.path.chmod(0o600)
        self.env = {'BRIDGE_HOME': str(self.home), 'BRIDGE_ENV_FILE': str(self.path)}

    def session(self, language='de'):
        return setup.SetupSession(setup.SettingsFile(self.path, self.env), language)

    async def drive(self, session, keys):
        with create_pipe_input() as pipe:
            ui = setup_tui.SetupApplication(session, input=pipe, output=DummyOutput())
            pipe.send_text(keys)
            result = await asyncio.wait_for(ui.application.run_async(), 4)
            return result, ui

    async def test_language_selection_is_saved_for_future_commands_without_restart(self):
        session = self.session('en')
        await self.drive(session, DOWN * 4 + ENTER + '\x1b[A' + ENTER + '\x13' + RIGHT + ENTER + QUIT)
        values = server._load_env_file(str(self.path))
        self.assertEqual(values['OMB_LANGUAGE'], 'de')
        self.assertEqual(setup_i18n.detect_language(values), 'de')
        self.assertFalse(session.restart_required)

    async def test_restart_requires_saved_changes_and_explicit_confirmation(self):
        session = self.session('en')
        with mock.patch.object(server.terminal, 'default_command', return_value=('tmux',)), \
                mock.patch.object(setup.subprocess, 'run') as run:
            # Save terminal draft; restart defaults Cancel, then confirm.
            run.return_value.returncode = 0
            await self.drive(session, ENTER + '\x13' + RIGHT + ENTER + DOWN * 6 + ENTER + ENTER + ENTER + RIGHT + ENTER + QUIT)
        run.assert_called_once_with(['systemctl', '--user', 'restart', 'orsik-mobile-bridge.service'], check=False, capture_output=True, timeout=30)
        self.assertFalse(session.restart_required)

    async def test_restart_failure_preserves_restart_required_without_retry(self):
        session = self.session('en')
        session.restart_required = True
        with mock.patch.object(setup.subprocess, 'run') as run:
            run.return_value.returncode = 1
            await self.drive(session, DOWN * 6 + ENTER + RIGHT + ENTER + QUIT)
        run.assert_called_once()
        self.assertTrue(session.restart_required)

    async def test_arrow_enter_toggles_then_cancelled_quit_preserves_draft(self):
        # Enter toggles terminal; quit opens discard dialog, Enter defaults Cancel.
        session = self.session()
        _, ui = await self.drive(session, ENTER + QUIT + ENTER + QUIT + RIGHT + ENTER)
        self.assertEqual(self.path.read_text(), self.original)
        self.assertTrue(session.pending)

    async def test_ctrl_s_defaults_cancel_then_explicit_right_enter_saves(self):
        session = self.session()
        with mock.patch.object(server.terminal, 'default_command', return_value=('tmux',)):
            await self.drive(session, ENTER + '\x13' + ENTER + '\x13' + RIGHT + ENTER + QUIT)
        self.assertEqual(server._load_env_file(str(self.path))['BRIDGE_TERMINAL'], '1')
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        self.assertIn('HERMES_API_KEY=synthetic-only', self.path.read_text())
        self.assertTrue(session.restart_required)

    async def test_english_keyboard_save_has_same_behavior(self):
        session = self.session('en')
        with mock.patch.object(server.terminal, 'default_command', return_value=('tmux',)):
            await self.drive(session, ENTER + '\x13' + RIGHT + ENTER + QUIT)
        self.assertEqual(server._load_env_file(str(self.path))['BRIDGE_TERMINAL'], '1')

    async def test_tab_then_left_returns_confirmation_to_cancel(self):
        session = self.session()
        with mock.patch.object(server.terminal, 'default_command', return_value=('tmux',)):
            await self.drive(session, ENTER + '\x13' + '\t' + '\x1b[D' + ENTER + QUIT + RIGHT + ENTER)
        self.assertEqual(self.path.read_text(), self.original)

    async def test_language_switch_keeps_unsaved_settings(self):
        session = self.session('en')
        # Terminal draft, Language row, choose German with Enter, then discard.
        await self.drive(session, ENTER + DOWN * 4 + ENTER + '\x1b[A' + ENTER + QUIT + RIGHT + ENTER)
        self.assertEqual(session.language, 'de')
        self.assertEqual(session.pending['BRIDGE_TERMINAL'], '1')
        self.assertEqual(self.path.read_text(), self.original)

    async def test_unicode_folder_edit_enter_and_confirm_save(self):
        folder = self.root / 'Büro für Größen'
        folder.mkdir()
        session = self.session()
        await self.drive(session, DOWN * 2 + ENTER + str(folder) + ENTER + '\x1b[A' + ENTER + '\x13' + RIGHT + ENTER + QUIT)
        values = server._load_env_file(str(self.path))
        self.assertEqual(values['BRIDGE_FILES_ROOT'], str(folder))
        self.assertEqual(values['BRIDGE_FILES'], '1')

    async def test_device_grant_enter_defaults_cancel_then_requires_confirmation(self):
        db = store.BridgeStore(str(self.home / 'state.db'))
        self.addCleanup(db.close)
        token, device = db.mint_device('Pixel Büro\x1b[2J')
        other_token, _ = db.mint_device('Other')
        session = self.session()
        # Devices row -> first device -> cancel. Retry -> right -> grant.
        await self.drive(session, DOWN * 3 + ENTER + ENTER + ENTER + ENTER + RIGHT + ENTER + QUIT)
        self.assertTrue(db.lookup_device(token).terminal_allowed)
        self.assertFalse(db.lookup_device(other_token).terminal_allowed)
        self.assertEqual(db.lookup_device(token).device_id, device.device_id)
        self.assertEqual(self.path.read_text(), self.original)
        self.assertFalse(session.pending)

    async def test_revocation_keeps_device_credential(self):
        db = store.BridgeStore(str(self.home / 'state.db'))
        self.addCleanup(db.close)
        token, device = db.mint_device('Pixel')
        db.set_terminal_access(device.device_id, True)
        await self.drive(self.session(), DOWN * 3 + ENTER + ENTER + RIGHT + ENTER + QUIT)
        self.assertFalse(db.lookup_device(token).terminal_allowed)

    async def test_saved_grant_is_not_reported_as_failed_if_list_refresh_fails(self):
        db = store.BridgeStore(str(self.home / 'state.db'))
        self.addCleanup(db.close)
        token, _ = db.mint_device('Pixel')
        session = self.session('de')
        initial = session.devices()
        with mock.patch.object(session, 'devices', side_effect=[initial, store.StoreUnavailable('synthetic-only')]) as reads:
            _, ui = await self.drive(session, DOWN * 3 + ENTER + ENTER + RIGHT + ENTER + QUIT)
        self.assertTrue(db.lookup_device(token).terminal_allowed)
        self.assertIn('gespeichert', ui.notice)
        self.assertNotIn('synthetic-only', ui.notice)
        self.assertEqual(reads.call_count, 2)

    async def test_invalid_file_root_shows_error_and_never_saves(self):
        session = self.session('en')
        session.set_value('BRIDGE_FILES_ROOT', str(self.home))
        session.set_value('BRIDGE_FILES', '1')
        await self.drive(session, '\x13' + RIGHT + ENTER + QUIT + RIGHT + ENTER)
        self.assertEqual(self.path.read_text(), self.original)

    async def test_store_error_does_not_expose_exception_or_secret(self):
        session = self.session('de')
        with mock.patch.object(session, 'devices', side_effect=store.StoreUnavailable('synthetic-only')):
            _, ui = await self.drive(session, DOWN * 3 + ENTER + QUIT)
        self.assertNotIn('synthetic-only', ui.notice)
        self.assertIn('Gerät', ui.notice)

    async def test_ctrl_c_requires_discard_confirmation(self):
        await self.drive(self.session(), ENTER + '\x03' + RIGHT + ENTER)
        self.assertEqual(self.path.read_text(), self.original)

    async def test_input_eof_never_saves_a_pending_change(self):
        session = self.session()
        with create_pipe_input() as pipe:
            ui = setup_tui.SetupApplication(session, input=pipe, output=DummyOutput())
            task = asyncio.create_task(ui.application.run_async())
            try:
                pipe.send_text(ENTER)

                async def wait_for_draft():
                    while not session.pending:
                        await asyncio.sleep(0)

                await asyncio.wait_for(wait_for_draft(), 2)
                pipe.close()
                with self.assertRaises(EOFError):
                    await asyncio.wait_for(task, 2)
            finally:
                if not task.done():
                    task.cancel()
                await asyncio.gather(task, return_exceptions=True)
        self.assertEqual(self.path.read_text(), self.original)


class SetupLanguageTest(unittest.TestCase):
    def test_complete_catalog_has_real_german_umlauts_and_english(self):
        self.assertEqual(set(setup_i18n.TEXT['de']), set(setup_i18n.TEXT['en']))
        self.assertIn('Änderungen', setup_i18n.text('de', 'save'))
        self.assertIn('Geräte', setup_i18n.text('de', 'devices'))
        self.assertIn('für', setup_i18n.text('de', 'terminal_help'))
        for language in ('de', 'en'):
            for value in setup_i18n.TEXT[language].values():
                self.assertTrue(value.strip())

    def test_locale_precedence_and_explicit_language(self):
        self.assertEqual(setup_i18n.detect_language({'LANG': 'de_DE.UTF-8'}), 'en')
        self.assertEqual(setup_i18n.detect_language({'LC_ALL': 'en_US.UTF-8', 'LANG': 'de_DE.UTF-8'}), 'en')
        self.assertEqual(setup_i18n.detect_language({'LC_MESSAGES': 'de_AT.UTF-8', 'LANG': 'en_US.UTF-8'}), 'en')
        self.assertEqual(setup_i18n.detect_language({'LANG': 'C.UTF-8'}), 'en')

    def test_external_labels_cannot_inject_terminal_controls(self):
        self.assertEqual(setup_tui.safe_text('Büro\x1b[2J\n'), 'Büro[2J')
