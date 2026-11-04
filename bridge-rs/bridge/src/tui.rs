//! The keyboard-driven `omb setup` screen (Python `setup_tui.py`): sections
//! on the left, details on the right, confirmations as modal dialogs. The
//! state machine is separate from drawing so it can be tested headless.

use ratatui::Frame;
use ratatui::crossterm::event::{self, Event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span, Text};
use ratatui::widgets::{Block, Borders, Clear, Paragraph, Wrap};

use crate::i18n::Language;
use crate::settings::{SetupError, SetupSession, error_text};
use crate::store::DeviceListing;

const SECTIONS: [&str; 8] = [
    "terminal",
    "files",
    "folder",
    "devices",
    "language",
    "save",
    "apply_restart",
    "quit",
];

fn rgb(hex: u32) -> Color {
    Color::Rgb((hex >> 16) as u8, (hex >> 8) as u8, hex as u8)
}

fn style(fg: u32, bg: Option<u32>) -> Style {
    let style = Style::default().fg(rgb(fg));
    match bg {
        Some(bg) => style.bg(rgb(bg)),
        None => style,
    }
}

/// Printable characters only, at most 256.
pub fn safe_text(value: &str) -> String {
    value
        .chars()
        .filter(|c| !c.is_control())
        .take(256)
        .collect()
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Mode {
    Main,
    Devices,
    Language,
}

#[derive(Clone, Debug, PartialEq)]
enum Confirmed {
    Save,
    Discard,
    Restart,
    Device { device_id: String, allowed: bool },
}

#[derive(Clone, Debug, PartialEq)]
enum Button {
    Cancel,
    Confirm(Confirmed),
    ApplyPath,
}

struct Dialog {
    title: String,
    body: String,
    /// The folder field (text, cursor in chars) of the folder dialog.
    field: Option<(String, usize)>,
    hint: String,
    buttons: Vec<(String, Button)>,
    /// 0..buttons.len() are the buttons; buttons.len() is the field.
    focus: usize,
}

impl Dialog {
    fn field_focused(&self) -> bool {
        self.field.is_some() && self.focus == self.buttons.len()
    }

    fn cycle(&mut self, delta: isize) {
        let count = self.buttons.len() + usize::from(self.field.is_some());
        // Order: field (if any) first, then the buttons.
        let order: Vec<usize> = if self.field.is_some() {
            std::iter::once(self.buttons.len())
                .chain(0..self.buttons.len())
                .collect()
        } else {
            (0..self.buttons.len()).collect()
        };
        let current = order.iter().position(|f| *f == self.focus).unwrap_or(0) as isize;
        self.focus = order[(current + delta).rem_euclid(count as isize) as usize];
    }
}

pub struct App {
    session: SetupSession,
    selected: usize,
    mode: Mode,
    devices: Vec<DeviceListing>,
    dialog: Option<Dialog>,
    notice_key: &'static str,
    last_error: Option<SetupError>,
    pub exit: Option<i32>,
}

impl App {
    pub fn new(session: SetupSession) -> Self {
        App {
            session,
            selected: 0,
            mode: Mode::Main,
            devices: Vec::new(),
            dialog: None,
            notice_key: "ready",
            last_error: None,
            exit: None,
        }
    }

    fn t(&self, key: &str) -> String {
        self.session.t(key)
    }

    fn set_notice(&mut self, key: &'static str) {
        self.notice_key = key;
        self.last_error = None;
    }

    fn fail(&mut self, error: SetupError) {
        self.last_error = Some(error);
    }

    fn choices(&self) -> Vec<String> {
        match self.mode {
            Mode::Devices => self.devices.iter().map(|d| safe_text(&d.label)).collect(),
            Mode::Language => vec!["Deutsch".into(), "English".into()],
            Mode::Main => SECTIONS.iter().map(|k| self.t(k)).collect(),
        }
    }

    fn state_label(&self, key: &str) -> String {
        match self.session.enabled(key) {
            Ok(true) => self.t("on"),
            Ok(false) => self.t("off"),
            Err(_) => "—".into(),
        }
    }

    fn details(&self) -> (String, String) {
        let (title, mut body) = match self.mode {
            Mode::Language => (self.t("language"), self.t("language_help")),
            Mode::Devices => {
                let body = match self.devices.get(self.selected) {
                    None => self.t("no_devices"),
                    Some(device) => {
                        let state = self.t(if device.terminal_allowed {
                            "allowed"
                        } else {
                            "denied"
                        });
                        format!(
                            "{}\n\n{}\n{}\n\n{}: {}\n\n{}",
                            safe_text(&device.label),
                            self.t("device_id"),
                            safe_text(&device.device_id),
                            self.t("terminal"),
                            state,
                            self.t("devices_note")
                        )
                    }
                };
                (self.t("devices"), body)
            }
            Mode::Main => {
                let key = SECTIONS[self.selected];
                let mut body = self.t(&format!("{key}_help"));
                match key {
                    "terminal" | "files" => {
                        let flag = if key == "terminal" {
                            "BRIDGE_TERMINAL"
                        } else {
                            "BRIDGE_FILES"
                        };
                        body = format!(
                            "{}\n\n{}\n\n{}",
                            self.state_label(flag),
                            body,
                            self.t(&format!("{key}_note"))
                        );
                    }
                    "folder" => {
                        let folder = safe_text(&self.session.value("BRIDGE_FILES_ROOT"));
                        let folder = if folder.is_empty() {
                            self.t("not_set")
                        } else {
                            folder
                        };
                        body = format!("{folder}\n\n{body}");
                    }
                    "save" => {
                        for (flag, value) in &self.session.pending {
                            let name = match flag.as_str() {
                                "BRIDGE_TERMINAL" => "terminal",
                                "BRIDGE_FILES" => "files",
                                "BRIDGE_FILES_ROOT" => "folder",
                                _ => "language",
                            };
                            let display = if name == "folder" || name == "language" {
                                safe_text(value)
                            } else {
                                self.t(if value == "1" { "on" } else { "off" })
                            };
                            body.push_str(&format!("\n\n{} → {}", self.t(name), display));
                        }
                    }
                    _ => {}
                }
                (self.t(key), body)
            }
        };
        if self.session.restart_required {
            body.push_str(&format!(
                "\n\n{}\n{}",
                self.t("saved"),
                self.t("apply_restart_help")
            ));
        }
        (title, body)
    }

    fn confirm(&mut self, title: String, body: String, action_label: String, action: Confirmed) {
        self.dialog = Some(Dialog {
            title,
            body,
            field: None,
            hint: String::new(),
            buttons: vec![
                (self.t("cancel"), Button::Cancel),
                (action_label, Button::Confirm(action)),
            ],
            focus: 0,
        });
    }

    fn edit_folder(&mut self) {
        let text = self.session.value("BRIDGE_FILES_ROOT");
        let cursor = text.chars().count();
        self.dialog = Some(Dialog {
            title: self.t("folder"),
            body: self.t("folder_prompt"),
            field: Some((text, cursor)),
            hint: self.t("folder_hint"),
            buttons: vec![
                (self.t("cancel"), Button::Cancel),
                (self.t("apply_path"), Button::ApplyPath),
            ],
            focus: 2,
        });
    }

    fn save(&mut self) {
        if self.session.pending.is_empty() {
            self.set_notice("no_changes");
            return;
        }
        self.confirm(
            self.t("save_title"),
            self.t("save_confirm"),
            self.t("save"),
            Confirmed::Save,
        );
    }

    fn quit(&mut self) {
        self.dialog = None;
        if self.session.pending.is_empty() {
            self.exit = Some(0);
        } else {
            self.confirm(
                self.t("discard_title"),
                self.t("discard_help"),
                self.t("discard"),
                Confirmed::Discard,
            );
        }
    }

    fn run_confirmed(&mut self, action: Confirmed) -> Result<(), SetupError> {
        match action {
            Confirmed::Save => {
                self.session.save()?;
                self.set_notice(if self.session.restart_required {
                    "saved"
                } else {
                    "language_saved"
                });
            }
            Confirmed::Discard => self.exit = Some(0),
            Confirmed::Restart => {
                self.session.restart()?;
                self.set_notice("restarted");
            }
            Confirmed::Device { device_id, allowed } => {
                let changed = self.session.change_terminal(&device_id, allowed)?;
                match self.session.devices() {
                    Ok(devices) => self.devices = devices,
                    Err(_) if changed => {
                        self.set_notice("device_saved_refresh_failed");
                        return Ok(());
                    }
                    Err(error) => return Err(error),
                }
                self.selected = self.selected.min(self.devices.len().saturating_sub(1));
                self.set_notice(match (changed, allowed) {
                    (false, _) => "device_gone",
                    (true, true) => "granted",
                    (true, false) => "revoked",
                });
            }
        }
        Ok(())
    }

    fn press(&mut self, button: Button) {
        match button {
            Button::Cancel => self.dialog = None,
            Button::ApplyPath => {
                let text = self
                    .dialog
                    .as_ref()
                    .and_then(|d| d.field.as_ref())
                    .map(|f| f.0.trim().to_string());
                self.dialog = None;
                match self
                    .session
                    .set_value("BRIDGE_FILES_ROOT", &text.unwrap_or_default())
                {
                    Ok(()) => self.set_notice("ready"),
                    Err(error) => self.fail(error),
                }
            }
            Button::Confirm(action) => {
                self.dialog = None;
                if let Err(error) = self.run_confirmed(action) {
                    self.fail(error);
                }
            }
        }
    }

    fn activate(&mut self) -> Result<(), SetupError> {
        match self.mode {
            Mode::Language => {
                self.session.set_language(if self.selected == 0 {
                    Language::De
                } else {
                    Language::En
                })?;
                self.mode = Mode::Main;
                self.selected = SECTIONS.iter().position(|s| *s == "language").unwrap();
                self.set_notice("language_selected");
            }
            Mode::Devices => {
                let Some(device) = self.devices.get(self.selected) else {
                    return Ok(());
                };
                let allowed = !device.terminal_allowed;
                let verb = if allowed { "grant" } else { "revoke" };
                let body = format!(
                    "{}\n{}\n\n{}",
                    safe_text(&device.label),
                    safe_text(&device.device_id),
                    self.t(&format!("{verb}_help"))
                );
                let action = Confirmed::Device {
                    device_id: device.device_id.clone(),
                    allowed,
                };
                self.confirm(self.t(&format!("{verb}_title")), body, self.t(verb), action);
            }
            Mode::Main => match SECTIONS[self.selected] {
                section @ ("terminal" | "files") => {
                    self.session.toggle(if section == "terminal" {
                        "BRIDGE_TERMINAL"
                    } else {
                        "BRIDGE_FILES"
                    })?;
                    self.set_notice("ready");
                }
                "folder" => self.edit_folder(),
                "devices" => {
                    self.devices = self.session.devices()?;
                    self.mode = Mode::Devices;
                    self.selected = 0;
                    self.set_notice("ready");
                }
                "language" => {
                    self.mode = Mode::Language;
                    self.selected = if self.session.language == Language::De {
                        0
                    } else {
                        1
                    };
                }
                "save" => self.save(),
                "apply_restart" => {
                    if !self.session.pending.is_empty() || !self.session.restart_required {
                        self.set_notice("restart_save_first");
                    } else {
                        self.confirm(
                            self.t("apply_restart"),
                            self.t("restart_confirm"),
                            self.t("restart_action"),
                            Confirmed::Restart,
                        );
                    }
                }
                _ => self.quit(),
            },
        }
        Ok(())
    }

    fn back(&mut self) {
        if self.dialog.is_some() {
            self.dialog = None;
        } else if self.mode != Mode::Main {
            let section = if self.mode == Mode::Devices {
                "devices"
            } else {
                "language"
            };
            self.selected = SECTIONS.iter().position(|s| *s == section).unwrap();
            self.mode = Mode::Main;
        } else {
            self.quit();
        }
    }

    pub fn key(&mut self, key: KeyEvent) {
        if key.kind != KeyEventKind::Press {
            return;
        }
        let ctrl = key.modifiers.contains(KeyModifiers::CONTROL);
        match key.code {
            KeyCode::Char('q') | KeyCode::Char('c') if ctrl => return self.quit(),
            KeyCode::Esc => return self.back(),
            _ => {}
        }
        if let Some(dialog) = self.dialog.as_mut() {
            match key.code {
                KeyCode::Tab => dialog.cycle(1),
                KeyCode::BackTab => dialog.cycle(-1),
                KeyCode::Enter if dialog.field_focused() => self.press(Button::ApplyPath),
                KeyCode::Enter => {
                    let button = dialog.buttons[dialog.focus].1.clone();
                    self.press(button);
                }
                code if dialog.field_focused() => {
                    let (text, cursor) = dialog.field.as_mut().unwrap();
                    let byte = |text: &str, chars: usize| {
                        text.char_indices()
                            .nth(chars)
                            .map_or(text.len(), |(i, _)| i)
                    };
                    match code {
                        KeyCode::Char(c) if !ctrl && !c.is_control() => {
                            let at = byte(text, *cursor);
                            text.insert(at, c);
                            *cursor += 1;
                        }
                        KeyCode::Backspace if *cursor > 0 => {
                            let at = byte(text, *cursor - 1);
                            text.remove(at);
                            *cursor -= 1;
                        }
                        KeyCode::Delete if *cursor < text.chars().count() => {
                            let at = byte(text, *cursor);
                            text.remove(at);
                        }
                        KeyCode::Left => *cursor = cursor.saturating_sub(1),
                        KeyCode::Right => *cursor = (*cursor + 1).min(text.chars().count()),
                        KeyCode::Home => *cursor = 0,
                        KeyCode::End => *cursor = text.chars().count(),
                        _ => {}
                    }
                }
                KeyCode::Left | KeyCode::Up => dialog.focus = dialog.focus.saturating_sub(1),
                KeyCode::Right | KeyCode::Down => {
                    dialog.focus = (dialog.focus + 1).min(dialog.buttons.len() - 1)
                }
                _ => {}
            }
            return;
        }
        let count = self.choices().len().max(1);
        match key.code {
            KeyCode::Up | KeyCode::BackTab => self.selected = (self.selected + count - 1) % count,
            KeyCode::Down | KeyCode::Tab => self.selected = (self.selected + 1) % count,
            KeyCode::Enter => {
                if let Err(error) = self.activate() {
                    self.fail(error);
                }
            }
            KeyCode::Char('d') if ctrl => self.quit(),
            KeyCode::Char('q') if !ctrl => self.quit(),
            KeyCode::Char('s') if ctrl => self.save(),
            _ => {}
        }
    }

    // -- drawing ----------------------------------------------------------------

    pub fn draw(&self, frame: &mut Frame) {
        let base = style(0xd5e0ed, Some(0x101923));
        frame.render_widget(Block::default().style(base), frame.area());
        let [brand, subtitle, path, body, notice, footer] = Layout::vertical([
            Constraint::Length(1),
            Constraint::Length(1),
            Constraint::Length(1),
            Constraint::Min(3),
            Constraint::Length(2),
            Constraint::Length(2),
        ])
        .areas(frame.area());
        frame.render_widget(
            Paragraph::new(Line::from(vec![
                Span::styled(
                    "  ORSIK / BRIDGE  ",
                    style(0x70d9e5, Some(0x172637)).add_modifier(Modifier::BOLD),
                ),
                Span::styled(self.t("title"), style(0x9db3c7, Some(0x172637))),
            ]))
            .style(style(0x9db3c7, Some(0x172637))),
            brand,
        );
        frame.render_widget(
            Paragraph::new(format!("  {}", self.t("subtitle"))),
            subtitle,
        );
        frame.render_widget(
            Paragraph::new(format!(
                "  {}",
                safe_text(&self.session.settings.path.display().to_string())
            ))
            .style(style(0x89a0b5, None)),
            path,
        );
        let [nav, border, details] = Layout::horizontal([
            Constraint::Length(26),
            Constraint::Length(1),
            Constraint::Min(10),
        ])
        .areas(body);
        let nav_lines: Vec<Line> = self
            .choices()
            .iter()
            .enumerate()
            .map(|(index, label)| {
                let label: String = label.chars().take(23).collect();
                if index == self.selected {
                    Line::styled(
                        format!("{:<26}", format!(" ▸ {label}")),
                        style(0xffffff, Some(0x244b60)).add_modifier(Modifier::BOLD),
                    )
                } else {
                    Line::styled(
                        format!("{:<26}", format!("   {label}")),
                        style(0xa8bbcd, Some(0x142130)),
                    )
                }
            })
            .collect();
        frame.render_widget(
            Paragraph::new(nav_lines).style(style(0xa8bbcd, Some(0x142130))),
            nav,
        );
        frame.render_widget(
            Paragraph::new(vec![Line::from("│"); border.height as usize])
                .style(style(0x36546c, None)),
            border,
        );
        let (title, text) = self.details();
        let mut lines = vec![
            Line::from(""),
            Line::styled(
                format!("  {title}"),
                style(0x70d9e5, None).add_modifier(Modifier::BOLD),
            ),
            Line::from(""),
        ];
        lines.extend(text.split('\n').map(|l| Line::from(format!("  {l}"))));
        frame.render_widget(Paragraph::new(lines).wrap(Wrap { trim: false }), details);
        let message = match &self.last_error {
            Some(error) => error_text(error, self.session.language),
            None => self.t(self.notice_key),
        };
        let (state, state_style) = if self.session.pending.is_empty() {
            (self.t("clean"), style(0x94aabd, None))
        } else {
            (self.t("draft"), style(0xf0ce88, None))
        };
        let message_style = if self.last_error.is_some() {
            style(0xf5a0a0, None)
        } else {
            style(0x9cddba, None)
        };
        frame.render_widget(
            Paragraph::new(vec![
                Line::styled(format!("  {message}"), message_style),
                Line::styled(format!("  {state}"), state_style),
            ]),
            notice,
        );
        let keys = if self.dialog.is_some() {
            "dialog_keys"
        } else if self.mode == Mode::Devices {
            "device_hint"
        } else {
            "keys"
        };
        frame.render_widget(
            Paragraph::new(format!("  {}", self.t(keys)))
                .wrap(Wrap { trim: false })
                .style(style(0xb5c9db, Some(0x172637))),
            footer,
        );
        if let Some(dialog) = &self.dialog {
            self.draw_dialog(frame, dialog);
        }
    }

    fn draw_dialog(&self, frame: &mut Frame, dialog: &Dialog) {
        let area = frame.area();
        let width = area.width.min(72);
        let mut lines: Vec<Line> = dialog.body.split('\n').map(Line::from).collect();
        if let Some((text, cursor)) = &dialog.field {
            let focused = dialog.field_focused();
            let field_style = style(0xffffff, Some(0x0c1420)).add_modifier(Modifier::UNDERLINED);
            let (before, after): (String, String) = (
                text.chars().take(*cursor).collect(),
                text.chars().skip(*cursor).collect(),
            );
            let mut spans = vec![Span::styled(before, field_style)];
            if focused {
                spans.push(Span::styled(" ", style(0x101923, Some(0x70d9e5))));
            }
            spans.push(Span::styled(after, field_style));
            lines.push(Line::from(spans));
            lines.push(Line::from(dialog.hint.clone()));
        }
        lines.push(Line::from(""));
        let mut buttons = Vec::new();
        for (index, (label, _)) in dialog.buttons.iter().enumerate() {
            let focused = index == dialog.focus;
            let button_style = if focused {
                style(0x101923, Some(0x70d9e5)).add_modifier(Modifier::BOLD)
            } else {
                style(0xd5e0ed, Some(0x263e52))
            };
            buttons.push(Span::styled(format!(" < {label} > "), button_style));
            buttons.push(Span::raw("  "));
        }
        lines.push(Line::from(buttons));
        let inner_width = width.saturating_sub(4).max(1) as usize;
        let height = lines
            .iter()
            .map(|l| l.width().max(1).div_ceil(inner_width) as u16)
            .sum::<u16>()
            .saturating_add(4)
            .min(area.height);
        let rect = Rect {
            x: area.x + area.width.saturating_sub(width) / 2,
            y: area.y + area.height.saturating_sub(height) / 2,
            width,
            height,
        };
        frame.render_widget(Clear, rect);
        let block = Block::default()
            .borders(Borders::ALL)
            .border_style(style(0x36546c, Some(0x1b2c3d)))
            .title(Span::styled(
                format!(" {} ", dialog.title),
                style(0x70d9e5, Some(0x1b2c3d)).add_modifier(Modifier::BOLD),
            ))
            .style(style(0xd5e0ed, Some(0x1b2c3d)));
        let inner = block.inner(rect);
        frame.render_widget(block, rect);
        let padded = Rect {
            x: inner.x + 1,
            width: inner.width.saturating_sub(2),
            ..inner
        };
        frame.render_widget(
            Paragraph::new(Text::from(lines)).wrap(Wrap { trim: false }),
            padded,
        );
    }
}

/// Runs the screen until the operator leaves it; restores the terminal.
pub fn run(session: SetupSession) -> std::io::Result<i32> {
    let mut terminal = ratatui::try_init()?;
    let mut app = App::new(session);
    let result = (|| -> std::io::Result<i32> {
        loop {
            terminal.draw(|frame| app.draw(frame))?;
            if let Event::Key(key) = event::read()? {
                app.key(key);
            }
            if let Some(code) = app.exit {
                return Ok(code);
            }
        }
    })();
    ratatui::try_restore()?;
    result
}

#[derive(clap::Parser)]
#[command(name = "omb setup")]
struct SetupArgs {
    /// Path to this installation's bridge.env
    #[arg(long = "env-file")]
    env_file: Option<String>,
    /// Interface language (de/en), otherwise saved preference or English
    #[arg(long, alias = "lang", value_parser = ["de", "en"])]
    language: Option<String>,
}

/// `omb setup`.
pub fn setup_main(argv: Vec<String>) -> i32 {
    use clap::Parser;
    use std::io::IsTerminal;
    let args = match SetupArgs::try_parse_from(std::iter::once("omb setup".to_string()).chain(argv))
    {
        Ok(args) => args,
        Err(error) => {
            let _ = error.print();
            return error.exit_code();
        }
    };
    let language =
        crate::settings::command_language(args.language.as_deref(), args.env_file.as_deref());
    let t = |key: &str| crate::i18n::text(language, key).to_string();
    if !std::io::stdin().is_terminal() || !std::io::stdout().is_terminal() {
        println!("{}", t("tty"));
        return 1;
    }
    let environ = std::env::vars().collect();
    let path = crate::settings::config_path(args.env_file.as_deref());
    let settings = match crate::settings::SettingsFile::open(path, environ) {
        Ok(settings) => settings,
        Err(error) => {
            println!("{}", error_text(&error, language));
            return 1;
        }
    };
    match run(SetupSession::new(settings, language)) {
        Ok(code) => code,
        Err(_) => {
            println!("{}", t("ended"));
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Env;
    use crate::settings::SettingsFile;
    use ratatui::Terminal;
    use ratatui::backend::TestBackend;
    use std::os::unix::fs::PermissionsExt;

    fn app(text: &str) -> (std::path::PathBuf, App) {
        let dir = std::env::temp_dir().join(format!("omb-tui-{}", uuid::Uuid::new_v4().simple()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("bridge.env");
        std::fs::write(&path, text).unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).unwrap();
        let mut env = Env::new();
        env.insert(
            "BRIDGE_STATE_DB".into(),
            dir.join("state.db").display().to_string(),
        );
        let session =
            SetupSession::new(SettingsFile::open(path.clone(), env).unwrap(), Language::En);
        (path, App::new(session))
    }

    fn press(app: &mut App, code: KeyCode) {
        app.key(KeyEvent::new(code, KeyModifiers::NONE));
    }

    fn screen(app: &App) -> String {
        let mut terminal = Terminal::new(TestBackend::new(100, 30)).unwrap();
        terminal.draw(|f| app.draw(f)).unwrap();
        let buffer = terminal.backend().buffer().clone();
        buffer
            .content
            .iter()
            .map(|c| c.symbol())
            .collect::<Vec<_>>()
            .join("")
    }

    #[test]
    fn a_toggle_is_a_draft_until_saved() {
        let (path, mut app) = app("BRIDGE_FILES=0\n");
        press(&mut app, KeyCode::Down);
        press(&mut app, KeyCode::Enter);
        assert!(screen(&app).contains("Unsaved changes"));
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "BRIDGE_FILES=0\n");
        // Esc on the main screen asks before discarding the draft.
        press(&mut app, KeyCode::Esc);
        assert!(screen(&app).contains("Discard changes?"));
        press(&mut app, KeyCode::Enter);
        assert_eq!(app.exit, None, "Cancel is focused first");
        app.quit();
        press(&mut app, KeyCode::Right);
        press(&mut app, KeyCode::Enter);
        assert_eq!(app.exit, Some(0));
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "BRIDGE_FILES=0\n");
        std::fs::remove_dir_all(path.parent().unwrap()).unwrap();
    }

    #[test]
    fn the_language_is_saved_without_a_restart() {
        let (path, mut app) = app("A=1\n");
        app.selected = 4;
        press(&mut app, KeyCode::Enter);
        press(&mut app, KeyCode::Up);
        press(&mut app, KeyCode::Enter);
        assert!(screen(&app).contains("Sprache auf Deutsch umgestellt."));
        app.key(KeyEvent::new(KeyCode::Char('s'), KeyModifiers::CONTROL));
        press(&mut app, KeyCode::Right);
        press(&mut app, KeyCode::Enter);
        assert_eq!(
            std::fs::read_to_string(&path).unwrap(),
            "A=1\nOMB_LANGUAGE=\"de\"\n"
        );
        assert!(!app.session.restart_required);
        std::fs::remove_dir_all(path.parent().unwrap()).unwrap();
    }

    #[test]
    fn the_folder_field_edits_a_draft() {
        let (path, mut app) = app("BRIDGE_FILES_ROOT=/a\n");
        app.selected = 2;
        press(&mut app, KeyCode::Enter);
        press(&mut app, KeyCode::Backspace);
        for c in "srv".chars() {
            press(&mut app, KeyCode::Char(c));
        }
        press(&mut app, KeyCode::Enter);
        assert_eq!(
            app.session.pending,
            [("BRIDGE_FILES_ROOT".to_string(), "/srv".to_string())]
        );
        assert!(screen(&app).contains("/srv"));
        std::fs::remove_dir_all(path.parent().unwrap()).unwrap();
    }

    #[test]
    fn devices_show_an_empty_store() {
        let (path, mut app) = app("A=1\n");
        app.selected = 3;
        press(&mut app, KeyCode::Enter);
        assert!(screen(&app).contains("No active devices found."));
        press(&mut app, KeyCode::Esc);
        assert_eq!(app.mode, Mode::Main);
        std::fs::remove_dir_all(path.parent().unwrap()).unwrap();
    }
}
