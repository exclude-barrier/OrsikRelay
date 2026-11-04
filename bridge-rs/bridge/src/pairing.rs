//! Operator pairing helpers: the `orsik://pair` deep link, the display form
//! of a code, an optional terminal QR and the pairing rate limiter.

use std::collections::HashMap;
use std::io::Write;
use std::process::{Command, Stdio};

use percent_encoding::{AsciiSet, NON_ALPHANUMERIC, utf8_percent_encode};

/// Python `urllib.parse.quote(value, safe='')`: everything but `A-Za-z0-9_.-~`.
const QUOTE: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'_')
    .remove(b'.')
    .remove(b'-')
    .remove(b'~');

/// The deep link for an absolute HTTPS base URL with a host and no user info.
pub fn pairing_uri(base_url: &str, code: &str) -> Option<String> {
    let parsed = url::Url::parse(base_url).ok()?;
    if parsed.scheme() != "https"
        || parsed.host_str().is_none_or(str::is_empty)
        || !parsed.username().is_empty()
        || parsed.password().is_some()
    {
        return None;
    }
    Some(format!(
        "orsik://pair?server={}&code={}",
        utf8_percent_encode(base_url, QUOTE),
        utf8_percent_encode(code, QUOTE)
    ))
}

/// Six groups of four separated by `-`.
pub fn format_code(code: &str) -> String {
    let chars: Vec<char> = code.chars().collect();
    chars
        .chunks(4)
        .map(|c| c.iter().collect::<String>())
        .collect::<Vec<_>>()
        .join("-")
}

/// A QR block via the system `qrencode` (payload on stdin, never in argv,
/// because the link carries the single-use code), or None without it.
pub fn render_ascii_qr(text: &str) -> Option<String> {
    let path = std::env::var("PATH").unwrap_or_default();
    let exe = crate::config::which("qrencode", &path)?;
    let mut child = Command::new(exe)
        .args(["-t", "ANSIUTF8", "-o", "-", "-m", "2"])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .ok()?;
    child.stdin.take()?.write_all(text.as_bytes()).ok()?;
    let output = child.wait_with_output().ok()?;
    output
        .status
        .success()
        .then(|| String::from_utf8_lossy(&output.stdout).into_owned())
}

/// Fixed-window budgets with a hard cap on live identities: a full table
/// refuses new identities rather than evicting a live budget.
pub struct RateLimiter {
    max_events: usize,
    window: f64,
    max_keys: usize,
    events: HashMap<String, Vec<f64>>,
}

impl RateLimiter {
    pub fn new(max_events: usize, window: f64) -> Self {
        RateLimiter {
            max_events,
            window,
            max_keys: 1024,
            events: HashMap::new(),
        }
    }

    pub fn allow(&mut self, key: &str, now: f64) -> bool {
        let cutoff = now - self.window;
        let mut events = match self.events.get(key) {
            Some(existing) => existing.iter().copied().filter(|t| *t > cutoff).collect(),
            None => {
                self.events
                    .retain(|_, v| v.last().is_some_and(|last| *last > cutoff));
                if self.events.len() >= self.max_keys {
                    return false;
                }
                Vec::new()
            }
        };
        if events.len() >= self.max_events {
            return false;
        }
        events.push(now);
        self.events.insert(key.to_string(), events);
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_deep_link_quotes_like_python() {
        assert_eq!(
            pairing_uri("https://relay.example.ts.net:8443", "ABC").unwrap(),
            "orsik://pair?server=https%3A%2F%2Frelay.example.ts.net%3A8443&code=ABC"
        );
        assert!(pairing_uri("http://relay.example", "A").is_none());
        assert!(pairing_uri("https://u:p@relay.example", "A").is_none());
        assert!(pairing_uri("relay.example", "A").is_none());
    }

    #[test]
    fn a_code_is_shown_in_groups_of_four() {
        assert_eq!(
            format_code("ABCDEFGHJKMNPQRSTVWXYZ12"),
            "ABCD-EFGH-JKMN-PQRS-TVWX-YZ12"
        );
    }

    #[test]
    fn the_limiter_counts_per_key_and_window() {
        let mut limiter = RateLimiter::new(2, 60.0);
        assert!(limiter.allow("a", 0.0));
        assert!(limiter.allow("a", 1.0));
        assert!(!limiter.allow("a", 2.0));
        assert!(limiter.allow("b", 2.0));
        assert!(limiter.allow("a", 61.5));
    }
}
