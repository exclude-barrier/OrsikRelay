//! Read-only host metrics for the app's Home card (Python `hostmetrics.py`).
//! Fixed sources only: /proc, `nvidia-smi` with fixed arguments and
//! `systemctl --user is-active` for the operator's fixed service list. Every
//! failure degrades to "no value" instead of an error.

use std::process::Stdio;
use std::time::{Duration, Instant};

use axum::extract::Request;
use serde_json::{Value, json};
use tokio::process::Command;

use crate::server::{BodyError, Handled, State, closed, json_response, lookup, read_body};

const SUBPROCESS_TIMEOUT: Duration = Duration::from_secs(1);
/// A snapshot younger than this answers further requests.
pub const MAX_AGE: Duration = Duration::from_secs(5);
const GPU_QUERY: &str = "--query-gpu=name,utilization.gpu,memory.used,memory.total";

fn cpu_ticks(text: &str) -> Option<(u64, u64)> {
    let fields: Vec<u64> = text
        .lines()
        .next()?
        .split_whitespace()
        .skip(1)
        .map(str::parse)
        .collect::<Result<_, _>>()
        .ok()?;
    let idle = fields.get(3)? + fields.get(4).copied().unwrap_or(0);
    // user..steal only; guest/guest_nice are already in user/nice.
    Some((fields.iter().take(8).sum(), idle))
}

async fn cpu_percent() -> Option<f64> {
    let (total1, idle1) = cpu_ticks(&tokio::fs::read_to_string("/proc/stat").await.ok()?)?;
    tokio::time::sleep(Duration::from_millis(200)).await;
    let (total2, idle2) = cpu_ticks(&tokio::fs::read_to_string("/proc/stat").await.ok()?)?;
    let total = total2.checked_sub(total1).filter(|t| *t > 0)? as f64;
    let busy = total - idle2.saturating_sub(idle1) as f64;
    Some((1000.0 * busy / total).round() / 10.0)
}

fn memory(text: &str) -> Option<Value> {
    let mut total = None;
    let mut available = None;
    for line in text.lines() {
        let Some((key, rest)) = line.split_once(':') else {
            continue;
        };
        let value = || {
            rest.split_whitespace()
                .next()?
                .parse::<i64>()
                .ok()
                .map(|v| v * 1024)
        };
        match key {
            "MemTotal" => total = Some(value()?),
            "MemAvailable" => available = Some(value()?),
            _ => {}
        }
    }
    let (total, available) = (total?, available?);
    Some(json!({"total_bytes": total, "used_bytes": total - available}))
}

/// Runs a fixed argv with a 1 s bound; None on any failure or timeout.
async fn run(program: &std::path::Path, args: &[&str]) -> Option<std::process::Output> {
    let child = Command::new(program)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .ok()?;
    tokio::time::timeout(SUBPROCESS_TIMEOUT, child.wait_with_output())
        .await
        .ok()?
        .ok()
}

async fn gpu() -> Option<Value> {
    let binary = crate::config::which("nvidia-smi", &std::env::var("PATH").unwrap_or_default())?;
    let done = run(&binary, &[GPU_QUERY, "--format=csv,noheader,nounits"]).await?;
    if !done.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&done.stdout);
    let parts: Vec<&str> = text.lines().next()?.split(',').map(str::trim).collect();
    let [name, utilization, used, total] = parts.as_slice() else {
        return None;
    };
    Some(json!({
        "name": name.chars().take(64).collect::<String>(),
        "utilization_percent": utilization.parse::<i64>().ok()?,
        "memory_used_bytes": used.parse::<i64>().ok()? * 1024 * 1024,
        "memory_total_bytes": total.parse::<i64>().ok()? * 1024 * 1024,
    }))
}

/// `active`: true (exit 0), false (exit 3), null (could not be asked).
async fn services(names: &[String]) -> Vec<Value> {
    let path = std::env::var("PATH").unwrap_or_default();
    let systemctl = crate::config::which("systemctl", &path);
    let mut result = Vec::new();
    for name in names {
        let unit = format!("{name}.service");
        let code = match &systemctl {
            Some(exe) => run(exe, &["--user", "is-active", "--quiet", &unit])
                .await
                .and_then(|o| o.status.code()),
            None => None,
        };
        let active = match code {
            Some(0) => json!(true),
            Some(3) => json!(false),
            _ => Value::Null,
        };
        result.push(json!({"name": name, "active": active}));
    }
    result
}

fn cores() -> Value {
    let n = unsafe { libc::sysconf(libc::_SC_NPROCESSORS_ONLN) };
    if n > 0 { json!(n) } else { Value::Null }
}

pub async fn collect(service_names: &[String]) -> Value {
    let memory = tokio::fs::read_to_string("/proc/meminfo")
        .await
        .ok()
        .and_then(|t| memory(&t));
    json!({
        "object": "orsik.host.metrics",
        "cpu": {"percent": cpu_percent().await, "cores": cores()},
        "memory": memory,
        "gpu": gpu().await,
        "services": services(service_names).await,
        "collected_at": crate::store::now(),
    })
}

pub async fn handle_metrics(state: &State, request: Request) -> Handled {
    if lookup(state, request.headers()).await?.is_none() {
        return Ok(closed(401, "invalid_credentials"));
    }
    if request.uri().query().is_some_and(|q| !q.is_empty()) {
        return Ok(closed(403, "query_not_allowed"));
    }
    match read_body(request.into_body()).await {
        Err(BodyError::TooLarge) => return Ok(closed(413, "body_too_large")),
        Ok(body) if !body.is_empty() => return Ok(closed(400, "body_not_allowed")),
        _ => {}
    }
    let mut cache = state.host_metrics.lock().await;
    let fresh = cache
        .as_ref()
        .is_some_and(|(at, _)| at.elapsed() <= MAX_AGE);
    if !fresh {
        let snapshot = collect(&state.host_services).await;
        *cache = Some((Instant::now(), snapshot));
    }
    Ok(json_response(200, &cache.as_ref().unwrap().1))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn proc_parsers_follow_the_python_rules() {
        assert_eq!(cpu_ticks("cpu  1 2 3 4 5 6 7 8 9 10\n"), Some((36, 9)));
        assert_eq!(cpu_ticks("cpu x\n"), None);
        assert_eq!(
            memory("MemTotal: 10 kB\nMemFree: 1 kB\nMemAvailable: 4 kB\n"),
            Some(json!({"total_bytes": 10240, "used_bytes": 6144}))
        );
        assert_eq!(memory("MemTotal: 10 kB\n"), None);
    }
}
