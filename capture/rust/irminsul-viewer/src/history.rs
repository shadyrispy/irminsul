//! The recent stream history, so a tab opened late still shows the replay.
//!
//! SSE alone cannot do this: an event published while nobody is watching is gone.
//! Opening a second tab, or reloading after a laptop slept, would then show an
//! empty wall next to a status bar reporting thousands of commands — the same
//! "looks healthy while hiding everything" failure the batch envelopes had.
//!
//! Every payload gets a sequence number, which is what lets a client both catch up
//! (`GET /api/history?after=N`) and drop the duplicate that arrives while it is
//! catching up.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, MutexGuard};

/// Payloads kept for a late or reconnecting tab. A 12k-frame capture fits; the
/// point is a browser-sized window, not the whole file, which `--dump-jsonl` covers.
pub const CAP: usize = 5000;

#[derive(Default)]
pub struct History {
    items: Mutex<VecDeque<(u64, String)>>,
    next_seq: AtomicU64,
}

/// What [`History::after`] hands back: the window itself, plus the two numbers a
/// client needs to know whether the window is the whole capture.
pub struct Slice {
    /// Payloads with a sequence greater than the request's `after`, oldest first.
    pub items: Vec<(u64, String)>,
    /// The oldest sequence still held, or `None` when the history is empty.
    pub oldest_seq: Option<u64>,
    /// The next sequence that will be assigned.
    pub next_seq: u64,
}

impl History {
    pub fn new() -> Self {
        Self::default()
    }

    /// Record a payload and hand back the number a client will ask for it with.
    pub fn push(&self, payload: String) -> u64 {
        let seq = self.next_seq.fetch_add(1, Ordering::Relaxed);
        let mut items = self.items.lock().expect("history lock");
        items.push_back((seq, payload));
        while items.len() > CAP {
            items.pop_front();
        }
        seq
    }

    /// Everything after `after`, oldest first. A gap because something was evicted
    /// is reported rather than papered over: [`Slice::oldest_seq`] tells the client
    /// it is looking at a window, not the whole capture.
    pub fn after(&self, after: u64) -> Slice {
        let items = self.lock();
        Slice {
            items: items
                .iter()
                .filter(|(seq, _)| *seq > after)
                .cloned()
                .collect(),
            oldest_seq: items.front().map(|(seq, _)| *seq),
            next_seq: self.next_seq.load(Ordering::Relaxed),
        }
    }

    fn lock(&self) -> MutexGuard<'_, VecDeque<(u64, String)>> {
        self.items.lock().expect("history lock")
    }

    pub fn len(&self) -> usize {
        self.lock().len()
    }

    /// A reloaded file is a new numbering and a new session, so the old window has
    /// to go with it — otherwise a tab catching up would splice two captures
    /// together under one sequence.
    pub fn clear(&self) {
        self.lock().clear();
        self.next_seq.store(0, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_payload_is_numbered_in_the_order_it_arrived() {
        let history = History::new();
        assert_eq!(history.push("one".into()), 0);
        assert_eq!(history.push("two".into()), 1);

        let slice = history.after(0);
        assert_eq!(slice.oldest_seq, Some(0));
        assert_eq!(slice.next_seq, 2);
        assert_eq!(slice.items, vec![(1, "two".to_string())]);
    }

    #[test]
    fn catching_up_from_zero_returns_everything() {
        let history = History::new();
        for i in 0..5 {
            history.push(format!("{i}"));
        }
        assert!(
            history.after(u64::MAX).items.is_empty(),
            "nothing is newer than the last sequence"
        );
        assert_eq!(history.after(0).items.len(), 4);
    }

    #[test]
    fn an_older_payload_is_dropped_before_a_newer_one() {
        let history = History::new();
        for i in 0..=(CAP as u64) {
            history.push(format!("payload-{i}"));
        }
        assert_eq!(history.len(), CAP);

        let slice = history.after(0);
        assert_eq!(slice.oldest_seq, Some(1), "the very first payload aged out");
        assert_eq!(slice.next_seq, (CAP + 1) as u64);
        assert_eq!(slice.items.len(), CAP);
        assert_eq!(slice.items[0].1, "payload-1");
    }
}
