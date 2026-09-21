//! Per-client SSE fan-out.
//!
//! Each browser tab gets its own bounded queue. A single shared channel would make
//! one slow tab hold the whole replay's memory (or block the decode thread), and a
//! ring the publisher reads from cannot tell a lagging client from a finished one.
//! So: bounded queue per client, and overflow is counted where a front end can
//! show it rather than being silently dropped.

use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{Receiver, SyncSender, TrySendError};
use std::collections::HashMap;
use std::sync::Mutex;

/// Events held for a tab that is not reading. Past this it starts losing them.
const QUEUE_LEN: usize = 1024;

/// One tab's stream, deregistered when the handler thread drops it.
pub struct Client {
    id: u64,
    receiver: Receiver<String>,
    fanout: Arc<Fanout>,
}

impl Drop for Client {
    fn drop(&mut self) {
        self.fanout.unsubscribe(self.id);
    }
}

impl Client {
    /// The next event, or `None` once the publisher has gone away.
    pub fn next(&self) -> Option<String> {
        self.receiver.recv().ok()
    }

    pub fn id(&self) -> u64 {
        self.id
    }
}

#[derive(Default)]
pub struct Fanout {
    next_id: AtomicU64,
    clients: Mutex<HashMap<u64, SyncSender<String>>>,
    dropped: AtomicU64,
}

impl Fanout {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn subscribe(self: &Arc<Self>) -> Client {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (sender, receiver) = std::sync::mpsc::sync_channel(QUEUE_LEN);
        self.clients
            .lock()
            .expect("fan-out lock")
            .insert(id, sender);
        Client {
            id,
            receiver,
            fanout: self.clone(),
        }
    }

    /// Hand `event` to every tab willing to take it right now. Never blocks: a tab
    /// that cannot keep up loses events, and the loss is counted.
    pub fn publish(&self, event: String) {
        let mut gone = Vec::new();
        {
            let mut clients = self.clients.lock().expect("fan-out lock");
            for (id, sender) in clients.iter() {
                match sender.try_send(event.clone()) {
                    Ok(()) => {}
                    Err(TrySendError::Full(_)) => {
                        self.dropped.fetch_add(1, Ordering::Relaxed);
                    }
                    Err(TrySendError::Disconnected(_)) => gone.push(*id),
                }
            }
            for id in gone {
                clients.remove(&id);
            }
        }
    }

    fn unsubscribe(&self, id: u64) {
        self.clients
            .lock()
            .expect("fan-out lock")
            .remove(&id);
    }

    pub fn client_count(&self) -> usize {
        self.clients.lock().map(|c| c.len()).unwrap_or(0)
    }

    pub fn dropped_count(&self) -> u64 {
        self.dropped.load(Ordering::Relaxed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_subscriber_gets_every_event() {
        let fanout = Arc::new(Fanout::new());
        let first = fanout.subscribe();
        let second = fanout.subscribe();

        fanout.publish("one".into());
        fanout.publish("two".into());

        assert_eq!(first.next().as_deref(), Some("one"));
        assert_eq!(second.next().as_deref(), Some("one"));
        assert_eq!(first.next().as_deref(), Some("two"));
        assert_eq!(second.next().as_deref(), Some("two"));
    }

    #[test]
    fn a_tab_that_stops_reading_loses_events_and_says_so() {
        let fanout = Arc::new(Fanout::new());
        let lagging = fanout.subscribe();
        let published = QUEUE_LEN + 5;

        for i in 0..published {
            fanout.publish(format!("{i}"));
        }

        assert_eq!(
            fanout.dropped_count(),
            (published - QUEUE_LEN) as u64,
            "overflow must be counted, not hidden"
        );
        assert_eq!(
            lagging.next().as_deref(),
            Some("0"),
            "what the tab did not read is the newest, not the oldest"
        );
    }

    #[test]
    fn a_closed_tab_is_deregistered_and_stops_counting() {
        let fanout = Arc::new(Fanout::new());
        let temporary = fanout.subscribe();
        assert_eq!(fanout.client_count(), 1);

        drop(temporary);
        fanout.publish("nobody is watching".into());

        assert_eq!(fanout.client_count(), 0);
        assert_eq!(fanout.dropped_count(), 0);
    }
}
