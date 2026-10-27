"""Full-screen, keyboard-first local administration. No terminal escape parsing."""
from __future__ import annotations

from prompt_toolkit import Application
from prompt_toolkit.filters import Condition
from prompt_toolkit.key_binding import KeyBindings
from prompt_toolkit.layout import (
    HSplit, VSplit, Window, Layout, FloatContainer, Float,
    ConditionalContainer, DynamicContainer, Dimension,
)
from prompt_toolkit.layout.controls import FormattedTextControl
from prompt_toolkit.layout.screen import Point
from prompt_toolkit.styles import Style
from prompt_toolkit.widgets import Button, Label, TextArea, Frame, Box

from . import files, terminal, store
from .setup import SetupSession, SetupError, error_text

SECTIONS = ('terminal', 'files', 'folder', 'devices', 'language', 'save', 'apply_restart', 'quit')
STYLE = Style.from_dict({
    '': 'bg:#101923 #d5e0ed',
    'brand': 'bg:#172637 #70d9e5 bold',
    'subtitle': 'bg:#172637 #9db3c7',
    'path': '#89a0b5',
    'nav': 'bg:#142130 #a8bbcd',
    'nav.selected': 'bg:#244b60 #ffffff bold',
    'title': '#70d9e5 bold',
    'value': '#ffffff bold',
    'muted': '#94aabd',
    'success': '#9cddba',
    'warning': '#f0ce88',
    'error': '#f5a0a0',
    'border': '#36546c',
    'footer': 'bg:#172637 #b5c9db',
    'dialog': 'bg:#1b2c3d',
    'dialog frame.label': '#70d9e5 bold',
    'button': 'bg:#263e52 #d5e0ed',
    'button.focused': 'bg:#70d9e5 #101923 bold',
    'dialog button.focused button.text': 'bg:#70d9e5 #101923 bold',
    'dialog button.focused button.arrow': 'bg:#70d9e5 #101923 bold',
    'text-area': 'bg:#0c1420 #ffffff',
    'text-area last-line': 'underline',
})


def safe_text(value: str) -> str:
    return ''.join(char for char in str(value) if char.isprintable())[:256]


class SetupApplication:
    def __init__(self, session: SetupSession, *, input=None, output=None):
        self.session = session
        self.selected = 0
        self.mode = 'main'
        self.device_rows: list[dict] = []
        self.dialog = None
        self.dialog_buttons: list[Button] = []
        self.button_index = 0
        self.notice_key = 'ready'
        self.last_error = None
        self.control = FormattedTextControl(
            self._navigation, focusable=True, show_cursor=False,
            get_cursor_position=lambda: Point(x=0, y=self.selected),
        )
        navigation = Window(self.control, width=Dimension(min=18, preferred=26, max=28),
                            style='class:nav', always_hide_cursor=True)
        details = Window(FormattedTextControl(self._details), wrap_lines=True,
                         always_hide_cursor=True, get_line_prefix=lambda line, wrap: '  ')
        main = HSplit([
            Window(FormattedTextControl(lambda: [('class:brand', '  ORSIK / BRIDGE  '),
                                                 ('class:subtitle', self.t('title'))]), height=1),
            Window(FormattedTextControl(lambda: '  ' + self.t('subtitle')), height=1),
            Window(FormattedTextControl(lambda: [('class:path', '  ' + safe_text(str(session.settings.path)))]), height=1),
            VSplit([navigation, Window(width=1, char='│', style='class:border'), details]),
            Window(FormattedTextControl(self._notice), height=2, wrap_lines=True),
            Window(FormattedTextControl(self._footer), height=2, wrap_lines=True, style='class:footer'),
        ])
        overlay = ConditionalContainer(
            DynamicContainer(lambda: self.dialog if self.dialog is not None else Window()),
            filter=Condition(lambda: self.dialog is not None),
        )
        layout = Layout(FloatContainer(main, floats=[Float(content=overlay, z_index=10)]),
                        focused_element=self.control)
        self.application = Application(
            layout=layout, key_bindings=self._bindings(), style=STYLE,
            full_screen=True, mouse_support=False, input=input, output=output,
            erase_when_done=True,
        )
        self.application.ttimeoutlen = 0.1

    def t(self, key):
        return self.session.t(key)

    @property
    def notice(self):
        return error_text(self.last_error, self.session.language) if self.last_error else self.t(self.notice_key)

    def _notice(self):
        state = self.t('draft') if self.session.pending else self.t('clean')
        return [('class:error' if self.last_error else 'class:success', '  ' + self.notice + '\n'),
                ('class:warning' if self.session.pending else 'class:muted', '  ' + state)]

    def _footer(self):
        key = 'dialog_keys' if self.dialog else ('device_hint' if self.mode == 'devices' else 'keys')
        return '  ' + self.t(key)

    def _choices(self):
        if self.mode == 'devices':
            return [safe_text(device['label']) for device in self.device_rows]
        if self.mode == 'language':
            return ['Deutsch', 'English']
        return [self.t(key) for key in SECTIONS]

    def _navigation(self):
        result = []
        for index, label in enumerate(self._choices()):
            style = 'class:nav.selected' if index == self.selected else 'class:nav'
            marker = ' ▸ ' if index == self.selected else '   '
            result.append((style, (marker + label[:23]).ljust(26) + '\n'))
        return result

    def _state(self, key):
        parse = terminal.parse_enabled if key == 'BRIDGE_TERMINAL' else files.parse_enabled
        try:
            return self.t('on') if parse(self.session.values.get(key)) else self.t('off')
        except SystemExit:
            return '—'

    def _details(self):
        if self.mode == 'language':
            title, body = self.t('language'), self.t('language_help')
        elif self.mode == 'devices':
            title = self.t('devices')
            if not self.device_rows:
                body = self.t('no_devices')
            else:
                device = self.device_rows[self.selected]
                state = self.t('allowed' if device['terminal_allowed'] else 'denied')
                body = (safe_text(device['label']) + '\n\n' + self.t('device_id') + '\n' +
                        safe_text(device['device_id']) + '\n\n' + self.t('terminal') + ': ' + state +
                        '\n\n' + self.t('devices_note'))
        else:
            key = SECTIONS[self.selected]
            title = self.t(key)
            body = self.t(key + '_help')
            if key in ('terminal', 'files'):
                flag = 'BRIDGE_TERMINAL' if key == 'terminal' else 'BRIDGE_FILES'
                body = self._state(flag) + '\n\n' + body + '\n\n' + self.t(key + '_note')
            elif key == 'folder':
                body = (safe_text(self.session.values.get('BRIDGE_FILES_ROOT', '')) or self.t('not_set')) + '\n\n' + body
            elif key == 'save':
                for flag, value in self.session.pending.items():
                    name = {'BRIDGE_TERMINAL': 'terminal', 'BRIDGE_FILES': 'files', 'BRIDGE_FILES_ROOT': 'folder', 'OMB_LANGUAGE': 'language'}[flag]
                    display = self.t('on' if value == '1' else 'off') if name not in ('folder', 'language') else safe_text(value)
                    body += '\n\n' + self.t(name) + ' → ' + display
        if self.session.restart_required:
            body += '\n\n' + self.t('saved') + '\n' + self.t('apply_restart_help')
        return [('class:title', '\n' + title + '\n\n'), ('', body)]

    def _set_notice(self, key='ready', error=None):
        self.notice_key, self.last_error = key, error
        self.application.invalidate()

    def _close_dialog(self):
        self.dialog = None
        self.dialog_buttons = []
        self.application.layout.focus(self.control)
        self.application.invalidate()

    def _modal(self, title, body, focus_order):
        bindings = KeyBindings()

        def move(delta):
            current = next(index for index, item in enumerate(focus_order)
                           if self.application.layout.has_focus(item))
            self.application.layout.focus(focus_order[(current + delta) % len(focus_order)])

        @bindings.add('tab')
        def next_control(event):
            move(1)

        @bindings.add('s-tab')
        def previous_control(event):
            move(-1)

        return Frame(HSplit([
            Box(body, padding=1),
            Box(VSplit(self.dialog_buttons, padding=1), padding=1, height=3),
        ]), title=title, width=Dimension(min=36, preferred=72, max=90),
                     modal=True, key_bindings=bindings, style='class:dialog')

    def _confirm(self, title, body, action_label, action):
        cancel = Button(self.t('cancel'), handler=self._close_dialog, width=16)

        def confirmed():
            self._close_dialog()
            try:
                action()
            except (SetupError, store.StoreUnavailable, SystemExit) as error:
                self._set_notice(error=error)

        accept = Button(action_label, handler=confirmed, width=max(16, len(action_label) + 4))
        self.dialog_buttons, self.button_index = [cancel, accept], 0
        self.dialog = self._modal(title, Label(body), self.dialog_buttons)
        self.application.layout.focus(cancel)
        self.application.invalidate()

    def _edit_folder(self):
        def apply_path():
            self.session.set_value('BRIDGE_FILES_ROOT', field.text.strip())
            self._close_dialog()
            self._set_notice()

        field = TextArea(text=self.session.values.get('BRIDGE_FILES_ROOT', ''), multiline=False,
                         height=1, style='class:text-area', accept_handler=lambda buffer: apply_path())
        cancel = Button(self.t('cancel'), self._close_dialog, width=16)
        accept = Button(self.t('apply_path'), apply_path, width=18)
        self.dialog_buttons, self.button_index = [cancel, accept], 0
        self.dialog = self._modal(self.t('folder'), HSplit([
            Label(self.t('folder_prompt')), field, Label(self.t('folder_hint')),
        ]), [field, *self.dialog_buttons])
        self.application.layout.focus(field)
        self.application.invalidate()

    def _save(self):
        if not self.session.pending:
            self._set_notice('no_changes')
            return

        def save():
            self.session.save()
            self._set_notice('saved' if self.session.restart_required else 'language_saved')

        self._confirm(self.t('save_title'), self.t('save_confirm'), self.t('save'), save)

    def _quit(self):
        if self.dialog:
            self._close_dialog()
        if self.session.pending:
            self._confirm(self.t('discard_title'), self.t('discard_help'), self.t('discard'),
                          lambda: self.application.exit(result=0))
        else:
            self.application.exit(result=0)

    def _activate(self):
        try:
            if self.mode == 'language':
                self.session.set_language(('de', 'en')[self.selected])
                self.mode, self.selected = 'main', SECTIONS.index('language')
                self._set_notice('language_selected')
            elif self.mode == 'devices':
                if not self.device_rows:
                    return
                device = dict(self.device_rows[self.selected])
                allowed = not device['terminal_allowed']

                def change():
                    changed = self.session.change_terminal(device['device_id'], allowed)
                    try:
                        self.device_rows = self.session.devices()
                    except store.StoreUnavailable:
                        if changed:
                            self._set_notice('device_saved_refresh_failed')
                            return
                        raise
                    self.selected = min(self.selected, max(0, len(self.device_rows) - 1))
                    self._set_notice(('granted' if allowed else 'revoked') if changed else 'device_gone')

                verb = 'grant' if allowed else 'revoke'
                body = (safe_text(device['label']) + '\n' + safe_text(device['device_id']) + '\n\n' +
                        self.t(verb + '_help'))
                self._confirm(self.t(verb + '_title'), body, self.t(verb), change)
            else:
                section = SECTIONS[self.selected]
                if section in ('terminal', 'files'):
                    self.session.toggle('BRIDGE_TERMINAL' if section == 'terminal' else 'BRIDGE_FILES')
                    self._set_notice()
                elif section == 'folder':
                    self._edit_folder()
                elif section == 'devices':
                    self.device_rows = self.session.devices()
                    self.mode, self.selected = 'devices', 0
                    self._set_notice()
                elif section == 'language':
                    self.mode = 'language'
                    self.selected = 0 if self.session.language == 'de' else 1
                elif section == 'save':
                    self._save()
                elif section == 'apply_restart':
                    if self.session.pending or not self.session.restart_required:
                        self._set_notice('restart_save_first')
                    else:
                        def restart():
                            self.session.restart()
                            self._set_notice('restarted')
                        self._confirm(self.t('apply_restart'), self.t('restart_confirm'), self.t('restart_action'), restart)
                elif section == 'quit':
                    self._quit()
        except (SetupError, store.StoreUnavailable, SystemExit) as error:
            self._set_notice(error=error)

    def _back(self):
        if self.dialog:
            self._close_dialog()
        elif self.mode != 'main':
            self.selected = SECTIONS.index('devices' if self.mode == 'devices' else 'language')
            self.mode = 'main'
        else:
            self._quit()

    def _bindings(self):
        keys = KeyBindings()
        browsing = Condition(lambda: self.dialog is None)
        buttons = Condition(lambda: self.dialog is not None and any(
            self.application.layout.has_focus(button) for button in self.dialog_buttons))

        @keys.add('up', filter=browsing)
        @keys.add('s-tab', filter=browsing)
        def previous(event):
            self.selected = (self.selected - 1) % max(1, len(self._choices()))

        @keys.add('down', filter=browsing)
        @keys.add('tab', filter=browsing)
        def following(event):
            self.selected = (self.selected + 1) % max(1, len(self._choices()))

        @keys.add('enter', filter=browsing)
        def activate(event):
            self._activate()

        @keys.add('escape', eager=True)
        def back(event):
            self._back()

        @keys.add('c-q')
        @keys.add('c-c')
        def quit_(event):
            self._quit()

        @keys.add('c-d', filter=browsing)
        @keys.add('q', filter=browsing)
        def quit_main(event):
            self._quit()

        @keys.add('c-s', filter=browsing)
        def save(event):
            self._save()

        @keys.add('left', filter=buttons)
        @keys.add('up', filter=buttons)
        def previous_button(event):
            current = next(index for index, button in enumerate(self.dialog_buttons)
                           if self.application.layout.has_focus(button))
            self.button_index = max(0, current - 1)
            self.application.layout.focus(self.dialog_buttons[self.button_index])

        @keys.add('right', filter=buttons)
        @keys.add('down', filter=buttons)
        def next_button(event):
            current = next(index for index, button in enumerate(self.dialog_buttons)
                           if self.application.layout.has_focus(button))
            self.button_index = min(len(self.dialog_buttons) - 1, current + 1)
            self.application.layout.focus(self.dialog_buttons[self.button_index])

        return keys
