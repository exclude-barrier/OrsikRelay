//! Per-run SSE buffers and their registry for the chat stream takeover.
//!
//! The bridge holds the upstream socket itself, keeps each run's frames
//! (bounded in count and bytes) and lets a client re-attach from an exclusive
//! `after_seq`. A replay that would leave a hole is refused (`gap`), and a run
//! without a buffer is never reconstructed. Every accumulator is bounded;
//! backpressure is drop-not-block: a slow subscriber gets one overflow marker
//! and is dropped.

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};

use bytes::Bytes;
use tokio::sync::{Notify, watch};

use crate::sse::Frame;
use crate::store::now;

pub const MAX_ACTIVE_RUNS: usize = 16;
pub const HUB_MAX_RETAINED_BYTES: usize = 64 * 1024 * 1024;
pub const BACKLOG_LIMIT: usize = 1000;
pub const BACKLOG_MAX_BYTES: usize = 4 * 1024 * 1024;
pub const SUBSCRIBER_QUEUE_LIMIT: usize = 256;
pub const SUBSCRIBER_QUEUE_MAX_BYTES: usize = 2 * 1024 * 1024;
pub const MAX_SUBSCRIBERS_PER_RUN: usize = 4;
const MAX_RUNS: usize = 256;
const RETENTION_SECONDS: f64 = 600.0;

#[derive(Clone)]
pub enum Item {
    Frame(Bytes),
    /// The run ended (or failed) while the subscriber was attached.
    End,
    /// The subscriber was too slow and has been dropped.
    Overflow,
}

struct SubscriberQueue {
    items: VecDeque<Item>,
    bytes: usize,
    max_items: usize,
}

/// A bounded per-subscriber queue with byte accounting.
pub struct Subscriber {
    queue: Mutex<SubscriberQueue>,
    notify: Notify,
}

impl Subscriber {
    fn new(max_items: usize) -> Arc<Self> {
        Arc::new(Subscriber {
            queue: Mutex::new(SubscriberQueue {
                items: VecDeque::new(),
                bytes: 0,
                max_items,
            }),
            notify: Notify::new(),
        })
    }

    /// Enqueues unless the item or byte cap would be exceeded; never blocks.
    fn put(&self, item: Item) -> bool {
        let mut queue = self.queue.lock().unwrap();
        if queue.items.len() >= queue.max_items {
            return false;
        }
        if let Item::Frame(raw) = &item {
            if queue.bytes + raw.len() > SUBSCRIBER_QUEUE_MAX_BYTES {
                return false;
            }
            queue.bytes += raw.len();
        }
        queue.items.push_back(item);
        drop(queue);
        self.notify.notify_one();
        true
    }

    /// Drops what is queued and leaves exactly one overflow marker.
    fn overflow(&self) {
        let mut queue = self.queue.lock().unwrap();
        queue.items.clear();
        queue.bytes = 0;
        queue.items.push_back(Item::Overflow);
        drop(queue);
        self.notify.notify_one();
    }

    pub async fn get(&self) -> Item {
        loop {
            let notified = self.notify.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            {
                let mut queue = self.queue.lock().unwrap();
                if let Some(item) = queue.items.pop_front() {
                    if let Item::Frame(raw) = &item {
                        queue.bytes -= raw.len();
                    }
                    return item;
                }
            }
            notified.await;
        }
    }

    pub fn is_empty(&self) -> bool {
        self.queue.lock().unwrap().items.is_empty()
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum RunState {
    Pending,
    Streaming,
    Failed,
}

struct BufferInner {
    run_id: Option<String>,
    updated_at: f64,
    terminal: bool,
    backlog: VecDeque<(i64, Bytes)>,
    backlog_bytes: usize,
    evicted_through: Option<i64>,
    subscribers: Vec<Arc<Subscriber>>,
    state: RunState,
    error: (u16, String, Bytes),
    abort_reason: Option<String>,
}

pub struct RunBuffer {
    pub session_id: String,
    inner: Mutex<BufferInner>,
    ready: watch::Sender<bool>,
}

/// What a client gets for its attach.
pub enum Attached {
    Failed {
        status: u16,
        content_type: String,
        body: Bytes,
    },
    Gap,
    Stream {
        subscriber: Arc<Subscriber>,
        replay: Vec<Bytes>,
        terminal: bool,
    },
}

impl RunBuffer {
    fn new(session_id: &str) -> Arc<Self> {
        let (ready, _) = watch::channel(false);
        Arc::new(RunBuffer {
            session_id: session_id.into(),
            inner: Mutex::new(BufferInner {
                run_id: None,
                updated_at: now(),
                terminal: false,
                backlog: VecDeque::new(),
                backlog_bytes: 0,
                evicted_through: None,
                subscribers: Vec::new(),
                state: RunState::Pending,
                error: (502, "application/json".into(), Bytes::new()),
                abort_reason: None,
            }),
            ready,
        })
    }

    pub fn run_id(&self) -> Option<String> {
        self.inner.lock().unwrap().run_id.clone()
    }

    pub fn is_pending(&self) -> bool {
        self.inner.lock().unwrap().state == RunState::Pending
    }

    pub fn abort_reason(&self) -> Option<String> {
        self.inner.lock().unwrap().abort_reason.clone()
    }

    pub fn set_abort_reason(&self, reason: &str) {
        self.inner.lock().unwrap().abort_reason = Some(reason.into());
    }

    fn fan_out(inner: &mut BufferInner, item: Item) {
        inner.subscribers.retain(|subscriber| {
            if subscriber.put(item.clone()) {
                true
            } else {
                subscriber.overflow();
                false
            }
        });
    }

    pub fn append(&self, frame: &Frame) {
        let mut inner = self.inner.lock().unwrap();
        let data = frame.json.as_ref();
        let seq = data
            .and_then(|d| d.get("seq"))
            .filter(|v| v.is_i64() || v.is_u64())
            .and_then(|v| v.as_i64())
            .unwrap_or_else(|| inner.backlog.back().map(|(s, _)| s + 1).unwrap_or(0));
        if inner.run_id.is_none()
            && let Some(run_id) = data
                .and_then(|d| d.get("run_id"))
                .and_then(|v| v.as_str())
                .filter(|s| !s.is_empty())
        {
            inner.run_id = Some(run_id.to_string());
        }
        let raw = Bytes::from(frame.raw.clone());
        inner.backlog.push_back((seq, raw.clone()));
        inner.backlog_bytes += raw.len();
        // The newest frame is always kept, so one frame over the byte cap still streams.
        while (inner.backlog.len() > BACKLOG_LIMIT || inner.backlog_bytes > BACKLOG_MAX_BYTES)
            && inner.backlog.len() > 1
        {
            let (old_seq, old_raw) = inner.backlog.pop_front().unwrap();
            inner.backlog_bytes -= old_raw.len();
            inner.evicted_through = Some(inner.evicted_through.map_or(old_seq, |e| e.max(old_seq)));
        }
        inner.updated_at = now();
        if inner.state == RunState::Pending {
            inner.state = RunState::Streaming;
            self.ready.send_replace(true);
        }
        Self::fan_out(&mut inner, Item::Frame(raw));
        // Only the explicit `done` event ends the run; EOF is handled by finish().
        if frame.event.as_deref() == Some("done") {
            Self::finish_locked(&mut inner);
            drop(inner);
            self.ready.send_replace(true);
        }
    }

    pub fn fail(&self, status: u16, content_type: &str, body: Bytes) {
        let mut inner = self.inner.lock().unwrap();
        inner.state = RunState::Failed;
        let media = content_type.split(';').next().unwrap_or("").trim();
        inner.error = (
            status,
            if media.is_empty() {
                "application/json".into()
            } else {
                media.into()
            },
            body,
        );
        Self::finish_locked(&mut inner);
        drop(inner);
        self.ready.send_replace(true);
    }

    fn finish_locked(inner: &mut BufferInner) {
        if !inner.terminal {
            inner.terminal = true;
            inner.updated_at = now();
        }
        Self::fan_out(inner, Item::End);
    }

    pub fn finish(&self) {
        let mut inner = self.inner.lock().unwrap();
        Self::finish_locked(&mut inner);
        drop(inner);
        self.ready.send_replace(true);
    }

    /// Waits until the first frame or a failure, then attaches.
    pub async fn attach(&self, after_seq: i64) -> Attached {
        let mut ready = self.ready.subscribe();
        let _ = ready.wait_for(|r| *r).await;
        let mut inner = self.inner.lock().unwrap();
        if inner.state == RunState::Failed {
            let (status, content_type, body) = inner.error.clone();
            return Attached::Failed {
                status,
                content_type,
                body,
            };
        }
        if inner.evicted_through.is_some_and(|e| after_seq < e) {
            return Attached::Gap;
        }
        if inner.subscribers.len() >= MAX_SUBSCRIBERS_PER_RUN {
            let oldest = inner.subscribers.remove(0);
            oldest.overflow();
        }
        let replay: Vec<Bytes> = inner
            .backlog
            .iter()
            .filter(|(seq, _)| *seq > after_seq)
            .map(|(_, raw)| raw.clone())
            .collect();
        let subscriber = Subscriber::new(SUBSCRIBER_QUEUE_LIMIT + replay.len());
        inner.subscribers.push(subscriber.clone());
        Attached::Stream {
            subscriber,
            replay,
            terminal: inner.terminal,
        }
    }

    pub fn detach(&self, subscriber: &Arc<Subscriber>) {
        self.inner
            .lock()
            .unwrap()
            .subscribers
            .retain(|s| !Arc::ptr_eq(s, subscriber));
    }

    fn snapshot(&self) -> (bool, f64, usize) {
        let inner = self.inner.lock().unwrap();
        (inner.terminal, inner.updated_at, inner.backlog_bytes)
    }
}

pub struct Busy;

#[derive(Default)]
struct HubInner {
    by_run: HashMap<String, Arc<RunBuffer>>,
    pending: Vec<Arc<RunBuffer>>,
}

/// Bounded registry of live and recent run buffers, keyed by run id.
#[derive(Default)]
pub struct RunHub {
    inner: Mutex<HubInner>,
}

impl RunHub {
    pub fn create_pending(&self, session_id: &str) -> Result<Arc<RunBuffer>, Busy> {
        let mut inner = self.inner.lock().unwrap();
        Self::prune(&mut inner);
        let active = inner
            .pending
            .iter()
            .chain(inner.by_run.values())
            .filter(|b| !b.snapshot().0)
            .count();
        if active >= MAX_ACTIVE_RUNS {
            return Err(Busy);
        }
        let buffer = RunBuffer::new(session_id);
        inner.pending.push(buffer.clone());
        Ok(buffer)
    }

    pub fn register(&self, buffer: &Arc<RunBuffer>) {
        let mut inner = self.inner.lock().unwrap();
        inner.pending.retain(|b| !Arc::ptr_eq(b, buffer));
        if let Some(run_id) = buffer.run_id() {
            if !inner
                .by_run
                .get(&run_id)
                .is_some_and(|b| Arc::ptr_eq(b, buffer))
            {
                inner.by_run.insert(run_id, buffer.clone());
                Self::prune(&mut inner);
            }
            return;
        }
        Self::prune(&mut inner);
    }

    pub fn lookup(&self, session_id: &str, run_id: &str) -> Option<Arc<RunBuffer>> {
        let inner = self.inner.lock().unwrap();
        inner
            .by_run
            .get(run_id)
            .filter(|b| b.session_id == session_id)
            .cloned()
    }

    pub fn belongs_to_other_session(&self, session_id: &str, run_id: &str) -> bool {
        let inner = self.inner.lock().unwrap();
        inner
            .by_run
            .get(run_id)
            .is_some_and(|b| b.session_id != session_id)
    }

    fn prune(inner: &mut HubInner) {
        let now = now();
        inner.pending.retain(|b| !b.snapshot().0);
        inner.by_run.retain(|_, b| {
            let (terminal, updated_at, _) = b.snapshot();
            !(terminal && now - updated_at > RETENTION_SECONDS)
        });
        if inner.by_run.len() > MAX_RUNS {
            let mut ordered: Vec<(String, bool, f64)> = inner
                .by_run
                .iter()
                .map(|(k, b)| {
                    let (t, u, _) = b.snapshot();
                    (k.clone(), t, u)
                })
                .collect();
            ordered.sort_by(|a, b| a.2.total_cmp(&b.2));
            for (run_id, terminal, _) in &ordered {
                if inner.by_run.len() <= MAX_RUNS {
                    break;
                }
                if *terminal {
                    inner.by_run.remove(run_id);
                }
            }
            for (run_id, _, _) in &ordered {
                if inner.by_run.len() <= MAX_RUNS {
                    break;
                }
                inner.by_run.remove(run_id);
            }
        }
        let mut retained: usize = inner
            .by_run
            .values()
            .map(|b| b.snapshot())
            .filter(|s| s.0)
            .map(|s| s.2)
            .sum();
        if retained > HUB_MAX_RETAINED_BYTES {
            let mut ordered: Vec<(String, bool, f64, usize)> = inner
                .by_run
                .iter()
                .map(|(k, b)| {
                    let (t, u, n) = b.snapshot();
                    (k.clone(), t, u, n)
                })
                .collect();
            ordered.sort_by(|a, b| a.2.total_cmp(&b.2));
            for (run_id, terminal, _, bytes) in ordered {
                if retained <= HUB_MAX_RETAINED_BYTES {
                    break;
                }
                if terminal {
                    retained -= bytes;
                    inner.by_run.remove(&run_id);
                }
            }
        }
        inner
            .pending
            .retain(|b| now - b.snapshot().1 <= RETENTION_SECONDS);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sse::Decoder;

    fn frames(text: &str) -> Vec<Frame> {
        Decoder::new(1 << 20).feed(text.as_bytes()).ok().unwrap()
    }

    #[tokio::test]
    async fn attach_replays_after_seq_and_follows() {
        let hub = RunHub::default();
        let buffer = hub.create_pending("s1").ok().unwrap();
        for frame in frames("data: {\"run_id\":\"r\",\"seq\":0}\n\ndata: {\"seq\":1}\n\n") {
            buffer.append(&frame);
            hub.register(&buffer);
        }
        assert!(hub.lookup("s1", "r").is_some());
        assert!(hub.lookup("s2", "r").is_none());
        assert!(hub.belongs_to_other_session("s2", "r"));
        let Attached::Stream {
            subscriber,
            replay,
            terminal,
        } = buffer.attach(0).await
        else {
            panic!()
        };
        assert_eq!(replay.len(), 1);
        assert!(!terminal);
        buffer.append(&frames("event: done\ndata: {\"seq\":2}\n\n")[0]);
        assert!(matches!(subscriber.get().await, Item::Frame(_)));
        assert!(matches!(subscriber.get().await, Item::End));
    }

    #[tokio::test]
    async fn an_evicted_range_is_a_gap() {
        let hub = RunHub::default();
        let buffer = hub.create_pending("s1").ok().unwrap();
        for seq in 0..(BACKLOG_LIMIT as i64 + 5) {
            buffer.append(&frames(&format!("data: {{\"run_id\":\"r\",\"seq\":{seq}}}\n\n"))[0]);
        }
        assert!(matches!(buffer.attach(-1).await, Attached::Gap));
        assert!(matches!(buffer.attach(4).await, Attached::Stream { .. }));
    }

    #[test]
    fn the_active_run_cap_is_enforced() {
        let hub = RunHub::default();
        let buffers: Vec<_> = (0..MAX_ACTIVE_RUNS)
            .map(|_| hub.create_pending("s").ok().unwrap())
            .collect();
        assert!(hub.create_pending("s").is_err());
        buffers[0].finish();
        assert!(hub.create_pending("s").is_ok());
    }
}
