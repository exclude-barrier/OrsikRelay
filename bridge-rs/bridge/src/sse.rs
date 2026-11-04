//! Minimal SSE decoder and the few frame rewrites the bridge allows itself.
//!
//! Frames keep their raw bytes so they are re-emitted byte-for-byte; only the
//! `data:` JSON is parsed for correlation. The deliberate exceptions are the
//! rewrites below (echo redaction, approval narrowing, unused fields), which
//! re-serialise the one `data:` line and keep `id:`/`event:` lines and order.
//! A frame block over the bound ends the stream instead of growing memory,
//! and the decoder never resynchronises (that would be a hole).

use serde_json::{Map, Value};

/// Relay bound for one decoded frame block (after CR/LF normalisation).
pub const MAX_FRAME_BYTES: usize = 1024 * 1024;

#[derive(Clone, Debug)]
pub struct Frame {
    pub event: Option<String>,
    pub json: Option<Map<String, Value>>,
    pub raw: Vec<u8>,
}

/// A frame block exceeded the bound; `frames` completed before it.
#[derive(Debug)]
pub struct TooLarge {
    pub limit: usize,
    pub frames: Vec<Frame>,
}

pub struct Decoder {
    buf: Vec<u8>,
    hold_cr: bool,
    max: usize,
    closed: bool,
}

fn find_blank_line(buf: &[u8], from: usize) -> Option<usize> {
    buf.get(from..)?
        .windows(2)
        .position(|w| w == b"\n\n")
        .map(|i| i + from)
}

impl Decoder {
    pub fn new(max_frame_bytes: usize) -> Self {
        Decoder {
            buf: Vec::new(),
            hold_cr: false,
            max: max_frame_bytes,
            closed: false,
        }
    }

    pub fn feed(&mut self, chunk: &[u8]) -> Result<Vec<Frame>, TooLarge> {
        if self.closed {
            return Err(TooLarge {
                limit: self.max,
                frames: Vec::new(),
            });
        }
        if chunk.is_empty() {
            return Ok(Vec::new());
        }
        let mut data = Vec::with_capacity(chunk.len() + 1);
        if self.hold_cr {
            data.push(b'\r');
            self.hold_cr = false;
        }
        data.extend_from_slice(chunk);
        // A trailing lone CR may pair with an LF in the next chunk.
        if data.last() == Some(&b'\r') {
            self.hold_cr = true;
            data.pop();
        }
        let scan_from = self.buf.len().saturating_sub(1);
        // CRLF and lone CR become LF.
        let mut i = 0;
        while i < data.len() {
            if data[i] == b'\r' {
                self.buf.push(b'\n');
                if data.get(i + 1) == Some(&b'\n') {
                    i += 1;
                }
            } else {
                self.buf.push(data[i]);
            }
            i += 1;
        }
        let mut frames = Vec::new();
        let mut consumed = 0;
        let mut scan = scan_from;
        while let Some(end) = find_blank_line(&self.buf, scan) {
            let block = self.buf[consumed..end].to_vec();
            consumed = end + 2;
            scan = consumed;
            if block.len() > self.max {
                return Err(self.overflow(frames));
            }
            if let Some(frame) = parse(&block) {
                frames.push(frame);
            }
        }
        if consumed > 0 {
            self.buf.drain(..consumed);
        }
        // A trailing LF is half of the terminating blank line, not frame body.
        let pending = self.buf.len() - usize::from(self.buf.last() == Some(&b'\n'));
        if pending > self.max {
            return Err(self.overflow(frames));
        }
        Ok(frames)
    }

    fn overflow(&mut self, frames: Vec<Frame>) -> TooLarge {
        self.closed = true;
        self.buf.clear();
        self.hold_cr = false;
        TooLarge {
            limit: self.max,
            frames,
        }
    }
}

fn parse(block: &[u8]) -> Option<Frame> {
    let mut event = None;
    let mut data_lines: Vec<String> = Vec::new();
    let mut has_data = false;
    for line in block.split(|b| *b == b'\n') {
        let text = String::from_utf8_lossy(line);
        if text.is_empty() || text.starts_with(':') {
            continue;
        }
        let (field, value) = match text.split_once(':') {
            Some((field, value)) => (
                field.to_string(),
                value.strip_prefix(' ').unwrap_or(value).to_string(),
            ),
            None => (text.to_string(), String::new()),
        };
        match field.as_str() {
            "event" => event = Some(value),
            "data" => {
                has_data = true;
                data_lines.push(value);
            }
            _ => {}
        }
    }
    if event.is_none() && !has_data {
        return None;
    }
    let json = if has_data {
        serde_json::from_str::<Value>(&data_lines.join("\n"))
            .ok()
            .and_then(|v| match v {
                Value::Object(map) => Some(map),
                _ => None,
            })
    } else {
        None
    };
    let mut raw = block.to_vec();
    raw.extend_from_slice(b"\n\n");
    Some(Frame { event, json, raw })
}

/// Re-serialise `payload` as the frame's single `data:` line.
fn rebuild(frame: &Frame, payload: Map<String, Value>) -> Frame {
    let data = Value::Object(payload.clone()).to_string();
    let data_line = format!("data: {data}").into_bytes();
    let mut lines: Vec<Vec<u8>> = Vec::new();
    let mut replaced = false;
    let body = frame.raw.strip_suffix(b"\n\n").unwrap_or(&frame.raw);
    let body = {
        let mut b = body;
        while let Some(rest) = b.strip_suffix(b"\n") {
            b = rest;
        }
        b
    };
    for line in body.split(|b| *b == b'\n') {
        if !line.starts_with(b"data:") {
            lines.push(line.to_vec());
        } else if !replaced {
            lines.push(data_line.clone());
            replaced = true;
        }
    }
    if !replaced {
        lines.push(data_line);
    }
    let mut raw = lines.join(&b'\n');
    raw.extend_from_slice(b"\n\n");
    Frame {
        event: frame.event.clone(),
        json: Some(payload),
        raw,
    }
}

/// `run.started` without its `user_message` echo (the whole image body).
pub fn redact_echo(frame: Frame) -> Frame {
    match (&frame.event, &frame.json) {
        (Some(event), Some(payload))
            if event == "run.started" && payload.contains_key("user_message") =>
        {
            let mut payload = payload.clone();
            payload.shift_remove("user_message");
            rebuild(&frame, payload)
        }
        _ => frame,
    }
}

fn unused_fields(event: &str) -> &'static [&'static str] {
    match event {
        "run.completed" | "run.cancelled" | "run.failed" => &["messages", "usage", "runtime"],
        "assistant.completed" => &["runtime"],
        "tool.started" | "tool.completed" | "tool.failed" => &["preview", "args"],
        "tool.progress" => &["delta"],
        "assistant.commentary" => &["text"],
        _ => &[],
    }
}

/// Drops payload fields a Mobile-v1 client never reads.
pub fn strip_unused(frame: Frame) -> Frame {
    let fields = unused_fields(frame.event.as_deref().unwrap_or(""));
    match &frame.json {
        Some(payload) if fields.iter().any(|f| payload.contains_key(*f)) => {
            let payload: Map<String, Value> = payload
                .iter()
                .filter(|(k, _)| !fields.contains(&k.as_str()))
                .map(|(k, v)| (k.clone(), v.clone()))
                .collect();
            rebuild(&frame, payload)
        }
        _ => frame,
    }
}

pub const APPROVAL_SAFE_DESCRIPTION: &str = "A dangerous action requires your approval.";
const APPROVAL_V1_COPIED: [&str; 5] = ["session_id", "run_id", "seq", "ts", "request_id"];

/// Narrows a core `approval.request` to the Mobile-v1 shape; a frame missing
/// a v1 field is relayed unchanged (the client rejects it closed).
pub fn normalize_approval(frame: Frame) -> Frame {
    match (&frame.event, &frame.json) {
        (Some(event), Some(payload))
            if event == "approval.request"
                && APPROVAL_V1_COPIED.iter().all(|k| payload.contains_key(*k)) =>
        {
            let mut narrowed = Map::new();
            for key in APPROVAL_V1_COPIED {
                narrowed.insert(key.into(), payload[key].clone());
            }
            narrowed.insert(
                "description".into(),
                Value::String(APPROVAL_SAFE_DESCRIPTION.into()),
            );
            narrowed.insert("choices".into(), serde_json::json!(["once", "deny"]));
            rebuild(&frame, narrowed)
        }
        _ => frame,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_split_across_chunks_and_crlf_are_decoded() {
        let mut decoder = Decoder::new(MAX_FRAME_BYTES);
        assert!(decoder.feed(b"event: a\r").unwrap().is_empty());
        let frames = decoder
            .feed(b"\ndata: {\"x\":1}\r\n\r\n: c\n\ndata: 2\n\n")
            .unwrap();
        assert_eq!(frames.len(), 2);
        assert_eq!(frames[0].event.as_deref(), Some("a"));
        assert_eq!(frames[0].json.as_ref().unwrap()["x"], 1);
        assert_eq!(frames[0].raw, b"event: a\ndata: {\"x\":1}\n\n");
        assert!(frames[1].json.is_none(), "non-object data stays unparsed");
    }

    #[test]
    fn an_oversized_block_closes_the_decoder() {
        let mut decoder = Decoder::new(10);
        let error = decoder
            .feed(b"data: 1\n\ndata: 0123456789\n\n")
            .err()
            .unwrap();
        assert_eq!(error.frames.len(), 1);
        assert!(decoder.feed(b"data: 1\n\n").is_err());
    }

    #[test]
    fn a_rewrite_keeps_id_and_event_lines() {
        let frame =
            parse(b"id: 3\nevent: run.started\ndata: {\"run_id\":\"r\",\"user_message\":\"x\"}")
                .unwrap();
        let redacted = redact_echo(frame);
        assert_eq!(
            redacted.raw,
            b"id: 3\nevent: run.started\ndata: {\"run_id\":\"r\"}\n\n"
        );
    }
}
