//! Slims a `GET /api/sessions/{id}/messages` page to what the app reads
//! (Python `core.strip_history_images`).

use bytes::Bytes;
use serde_json::{Map, Value, json};

const IMAGE_PART_TYPES: [&str; 3] = ["image", "image_url", "input_image"];
const IMAGE_HINT_SEPARATOR: &str = "\n\n[Image attached";
const SCREENSHOT_PLACEHOLDER: &str = "[screenshot]";
const DEFAULT_IMAGE_CAPTION: &str = "What do you see in this image?";
const UNUSED_FIELDS: [&str; 3] = ["tool_calls", "reasoning", "reasoning_content"];

/// `\[Image attached(?: at)?: [^\n\]]+\]` as a full match.
fn is_image_hint(line: &str) -> bool {
    let Some(rest) = line.strip_prefix("[Image attached") else {
        return false;
    };
    let rest = rest.strip_prefix(" at").unwrap_or(rest);
    let Some(inner) = rest.strip_prefix(": ").and_then(|r| r.strip_suffix(']')) else {
        return false;
    };
    !inner.is_empty() && !inner.contains(['\n', ']'])
}

/// The content-part list for a core image-hint text, or None if it is not one.
fn image_hint_parts(text: &str) -> Option<Vec<Value>> {
    let index = text.find(IMAGE_HINT_SEPARATOR)?;
    let (caption, rest) = (&text[..index], &text[index + 2..]);
    let mut lines: Vec<&str> = rest.split('\n').collect();
    let mut placeholders = 0;
    while lines.last() == Some(&SCREENSHOT_PLACEHOLDER) {
        lines.pop();
        placeholders += 1;
    }
    if lines.is_empty() || !lines.iter().all(|l| is_image_hint(l)) {
        return None;
    }
    if placeholders != 0 && placeholders != lines.len() {
        return None;
    }
    let mut parts = Vec::new();
    if !caption.is_empty() && caption != DEFAULT_IMAGE_CAPTION {
        parts.push(json!({"type": "text", "text": caption}));
    }
    parts.extend(lines.iter().map(|_| json!({"type": "image_url"})));
    Some(parts)
}

fn truthy(value: &Value) -> bool {
    match value {
        Value::Null => false,
        Value::Bool(b) => *b,
        Value::Number(n) => n.as_f64() != Some(0.0),
        Value::String(s) => !s.is_empty(),
        Value::Array(a) => !a.is_empty(),
        Value::Object(o) => !o.is_empty(),
    }
}

fn slim(message: &mut Map<String, Value>) -> bool {
    let mut changed = false;
    for key in UNUSED_FIELDS {
        changed |= message.shift_remove(key).is_some();
    }
    let role = message
        .get("role")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    if role == "tool" && message.get("content").is_some_and(truthy) {
        message.insert("content".into(), Value::String(String::new()));
        changed = true;
    }
    match message.get_mut("content") {
        Some(Value::String(text)) if role == "user" => {
            if let Some(parts) = image_hint_parts(text) {
                message.insert("content".into(), Value::Array(parts));
                changed = true;
            }
        }
        Some(Value::Array(parts)) => {
            for part in parts.iter_mut() {
                let kind = part
                    .get("type")
                    .and_then(Value::as_str)
                    .filter(|t| IMAGE_PART_TYPES.contains(t));
                if let Some(kind) = kind {
                    *part = json!({"type": kind});
                    changed = true;
                }
            }
        }
        _ => {}
    }
    changed
}

/// A body that is not the expected JSON shape is returned unchanged.
pub fn strip_history_images(body: Bytes) -> Bytes {
    let Ok(mut page) = serde_json::from_slice::<Value>(&body) else {
        return body;
    };
    let Some(Value::Array(data)) = page.get_mut("data") else {
        return body;
    };
    let mut changed = false;
    for message in data.iter_mut() {
        if let Value::Object(message) = message {
            changed |= slim(message);
        }
    }
    if !changed {
        return body;
    }
    Bytes::from(page.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(page: Value) -> Value {
        serde_json::from_slice(&strip_history_images(Bytes::from(page.to_string()))).unwrap()
    }

    #[test]
    fn images_tool_output_and_reasoning_are_dropped() {
        let out = run(json!({"data": [
            {"role": "user", "content": [{"type": "text", "text": "hi"}, {"type": "image_url", "image_url": {"url": "data:x"}}]},
            {"role": "tool", "content": "secret output", "tool_calls": []},
            {"role": "assistant", "content": "ok", "reasoning": "r"}
        ]}));
        assert_eq!(out["data"][0]["content"][1], json!({"type": "image_url"}));
        assert_eq!(out["data"][1], json!({"role": "tool", "content": ""}));
        assert_eq!(
            out["data"][2],
            json!({"role": "assistant", "content": "ok"})
        );
    }

    #[test]
    fn a_hint_text_becomes_parts() {
        let text = "look\n\n[Image attached at: /srv/a.png]\n[Image attached: b]\n[screenshot]\n[screenshot]";
        let out = run(json!({"data": [{"role": "user", "content": text}]}));
        assert_eq!(
            out["data"][0]["content"],
            json!([{"type": "text", "text": "look"}, {"type": "image_url"}, {"type": "image_url"}])
        );
        let odd = "x\n\n[Image attached: a]\n[screenshot]\n[screenshot]";
        let out = run(json!({"data": [{"role": "user", "content": odd}]}));
        assert_eq!(out["data"][0]["content"], odd);
    }

    #[test]
    fn other_shapes_pass_unchanged() {
        let raw = Bytes::from_static(b"{\"data\": 1}");
        assert_eq!(strip_history_images(raw.clone()), raw);
    }
}
