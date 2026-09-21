//! The replay loop, and the only place a [`Session`] is held on this side.
//!
//! The session is stateful and single-owner: a capture cannot be re-read from an
//! arbitrary offset without losing the keys its earlier frames carried, so exactly
//! one thread feeds it, and every request to change what is being fed arrives over
//! a channel instead. Reading that source is a second thread, because the two wait
//! for different things: a live interface can go quiet for as long as the player
//! stays in a menu, and a loop that spent that time blocked in a read could not
//! hear an operator press stop. Frames cross a short queue between them, which is
//! also what tells a capture to slow down when the decoder cannot keep up.

use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::PathBuf;
use std::sync::mpsc::{Receiver, TryRecvError};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use irminsul_decode::{KeyOrigin, PacketOutcome, Session, key_origin_name};
use serde_json::json;
use tracing::{info, warn};

use crate::events::Fanout;
use crate::frame_source::{FrameSource, Reading, Tick};
use crate::history::History;
use crate::http::event_frame;

pub enum Command {
    /// Start over: a fresh session reading `source` from its first record.
    Load(FrameSource),
    /// Continue the current replay.
    Resume,
    /// Stop feeding, keeping the session and everything decoded so far.
    Pause,
}

/// How long to look at the source for before looking at the commands again. Only a
/// source with no traffic waits the whole of it, and that is the point: it is how a
/// paused or stopped replay is noticed by the thread reading a quiet interface.
const IDLE_POLL: Duration = Duration::from_millis(20);

/// Where a replay stands, readable by a browser without touching the session.
#[derive(Default)]
pub struct Status {
    pub source: Option<FrameSource>,
    pub paused: bool,
    pub finished: bool,
    pub records: u64,
    pub payloads: u64,
    /// Payloads whose `commands` array is empty: a KCP segment that arrived but
    /// held nothing decryptable. On a blind capture this is every payload, and
    /// "890 payloads, 0 commands" is the difference between a session that is
    /// quiet and one that never opened.
    pub empty_payloads: u64,
    pub commands: u64,
    pub nested: u64,
    /// How far into the file the replay has got, on the capture's own clock.
    pub at_capture_ms: u64,
    pub key_origin: Option<KeyOrigin>,
    pub completion: Option<serde_json::Value>,
    pub elapsed_ms: u64,
    /// The last thing that went wrong, kept because a failure that happened before
    /// a tab connected is otherwise invisible: only packets are replayed to a late
    /// reader, and this is not one of them. A source that failed to open at all
    /// looks, from a browser, exactly like one that has nothing to show.
    pub error: Option<String>,
}

impl Status {
    /// `history_depth` is how far back a tab that opens now can catch up to: at
    /// the ring's capacity the oldest payloads have already aged out, which is the
    /// difference between a wall that looks short and one that is short.
    pub fn json(&self, clients: usize, dropped: u64, history_depth: usize) -> serde_json::Value {
        json!({
            "source": self.source.as_ref().map(FrameSource::name),
            "paused": self.paused,
            "finished": self.finished,
            "records": self.records,
            "payloads": self.payloads,
            "empty_payloads": self.empty_payloads,
            "commands": self.commands,
            "nested_commands": self.nested,
            "at_capture_ms": self.at_capture_ms,
            "elapsed_ms": self.elapsed_ms,
            "key_origin": self.key_origin.map(key_origin_name),
            "completion": self.completion,
            "error": self.error,
            "stream_clients": clients,
            "dropped_events": dropped,
            "history_depth": history_depth,
        })
    }
}

/// The state the HTTP threads and the replay thread share.
pub struct ReplayState {
    pub session: Mutex<Session>,
    pub status: Mutex<Status>,
}

pub struct Replay {
    state: Arc<ReplayState>,
    fanout: Arc<Fanout>,
    history: Arc<History>,
    receiver: Receiver<Command>,
    samples_dir: Option<PathBuf>,
    dump: Option<BufWriter<File>>,
    started: Instant,
}

impl Replay {
    /// Start the replay thread. `samples_dir` is where known command bodies live,
    /// exactly as on the device: without it a blind session stays blind.
    pub fn spawn(
        state: Arc<ReplayState>,
        fanout: Arc<Fanout>,
        history: Arc<History>,
        receiver: Receiver<Command>,
        samples_dir: Option<PathBuf>,
        dump_path: Option<PathBuf>,
    ) -> anyhow::Result<JoinHandle<()>> {
        let dump = match &dump_path {
            Some(path) => Some(BufWriter::new(
                File::create(path)
                    .map_err(|e| anyhow::anyhow!("cannot write {}: {e}", path.display()))?,
            )),
            None => None,
        };
        std::thread::Builder::new()
            .name("irminsul-replay".into())
            .spawn(move || {
                let mut replay = Replay {
                    state,
                    fanout,
                    history,
                    receiver,
                    samples_dir,
                    dump,
                    started: Instant::now(),
                };
                replay.run();
            })
            .map_err(Into::into)
    }

    fn run(&mut self) {
        let mut reading: Option<Reading> = None;
        // "Nobody can ask anything of us any more" is not the same event as
        // "stop reading the source": `--once` closes the command channel on purpose,
        // and the replay still owes that capture its whole length.
        let mut commands_closed = false;

        loop {
            match self.receiver.try_recv() {
                Ok(command) => self.handle(command, &mut reading),
                Err(TryRecvError::Empty) => {}
                Err(TryRecvError::Disconnected) => commands_closed = true,
            }

            if self.lock_status().paused {
                if commands_closed {
                    break;
                }
                // Parked: block until there is a command instead of spinning.
                match self.receiver.recv() {
                    Ok(command) => self.handle(command, &mut reading),
                    Err(_) => break,
                }
                continue;
            }

            let event = match reading.as_mut() {
                Some(reading) => reading.next(IDLE_POLL),
                // Asked to resume with nothing to read — a finished file, or none
                // ever loaded. Park rather than spin, and say nothing: the counters
                // already tell a view where the replay stands.
                None => {
                    self.lock_status().paused = true;
                    continue;
                }
            };

            match event {
                Tick::Waited => {}
                Tick::Frame { bytes, capture_ms } => self.feed(bytes, capture_ms),
                Tick::Failed(message) => self.finish(&mut reading, Some(message)),
                Tick::Ended => self.finish(&mut reading, None),
            }
        }

        if let Some(dump) = self.dump.as_mut() {
            let _ = dump.flush();
        }
    }

    fn handle(&mut self, command: Command, reading: &mut Option<Reading>) {
        match command {
            Command::Load(source) => match self.load(source) {
                Ok(current) => *reading = Some(current),
                Err(e) => {
                    let message = format!("{e:#}");
                    warn!("{message}");
                    self.report(&message);
                    self.lock_status().paused = true;
                }
            },
            Command::Resume => {
                let (source, finished) = {
                    let status = self.lock_status();
                    (status.source.clone(), status.finished)
                };
                let Some(source) = source else {
                    self.report("nothing loaded: POST /api/upload, or start with --file / --live");
                    return;
                };
                // A source that has run out cannot be "resumed"; opening it again is
                // what an operator means, and it needs a fresh session for the keys.
                // Standard input is the one source that has no second opening, so
                // say so instead of pretending to restart it.
                if finished {
                    if !source.can_reopen() {
                        self.report(&format!(
                            "{} cannot be read again: it holds no more frames. Load a file or \
                             start a capture.",
                            source.name()
                        ));
                        return;
                    }
                    match self.load(source) {
                        Ok(current) => *reading = Some(current),
                        Err(e) => {
                            let message = format!("{e:#}");
                            warn!("{message}");
                            self.report(&message);
                            self.lock_status().paused = true;
                        }
                    }
                    return;
                }
                self.lock_status().paused = false;
            }
            Command::Pause => {
                // A live capture has to actually stop. Leaving `tcpdump` running
                // would keep a privileged process reading the interface, and "it
                // paused" from the other side is just its pipe filling up.
                let live = self
                    .lock_status()
                    .source
                    .as_ref()
                    .is_some_and(FrameSource::is_live);
                if live {
                    self.finish(reading, None);
                } else {
                    self.lock_status().paused = true;
                }
            }
        }
    }

    /// The end of a source, or of trying to read one.
    fn finish(&mut self, reading: &mut Option<Reading>, error: Option<String>) {
        // Dropping this is what stops a live capture: the queue goes, so the reader
        // thread lets go, and the child is killed here rather than by whoever is
        // blocked waiting for a packet that may never come.
        *reading = None;
        let value = {
            let mut status = self.lock_status();
            status.paused = true;
            status.finished = true;
            json!({
                "event": "replay_finished",
                "records": status.records,
                "commands": status.commands,
                "nested_commands": status.nested,
            })
        };
        if let Some(message) = error {
            warn!("{message}");
            self.report(&message);
        }
        // The dump is buffered, and a replay that has run out of file can sit
        // parked for as long as the viewer stays up: without this its last lines
        // only appeared when the process died.
        if let Some(dump) = self.dump.as_mut() {
            let _ = dump.flush();
        }
        self.fanout.publish(system_event(value));
    }

    /// Start a source reading on its own thread, and reset everything the replay
    /// owns. The counters go first: a view that sees the new source in
    /// `/api/state` should never see frames from the old one beside them.
    fn load(&mut self, source: FrameSource) -> anyhow::Result<Reading> {
        let name = source.name();
        let opened = source.open()?;
        let linktype = opened.linktype();
        let devices = opened.devices();
        let session = Session::new(self.samples_dir.as_deref())?;
        *self.state.session.lock().expect("session lock") = session;
        self.started = Instant::now();
        self.history.clear();

        *self.lock_status() = Status {
            source: Some(source),
            ..Default::default()
        };
        // `load` replaced the whole status above, so the error field is already clean;
        // saying it here is the reason a reader of this function will not wonder.
        info!(source = %name, linktype, devices = ?devices, "source open");
        self.fanout.publish(system_event(json!({
            "event": "replay_loaded",
            "source": name,
            "linktype": linktype,
            "devices": devices,
        })));
        opened.spawn()
    }

    fn feed(&mut self, frame: Vec<u8>, capture_ms: u64) {
        let (outcome, origin) = {
            let mut session = self.state.session.lock().expect("session lock");
            (session.feed(&frame), session.key_origin())
        };

        {
            let mut status = self.lock_status();
            status.records += 1;
            status.at_capture_ms = capture_ms;
            status.elapsed_ms = self.started.elapsed().as_millis() as u64;
            // Reported even when nothing decoded: a session that goes blind
            // produces no payload, and the origin is the only trace of it.
            if status.key_origin != origin {
                status.key_origin = origin;
                let value = json!({
                    "event": "key_origin",
                    "origin": origin.map(key_origin_name),
                });
                drop(status);
                self.fanout.publish(system_event(value));
            }
        }

        let Some(outcome) = outcome else { return };
        self.publish(outcome);
    }

    fn publish(&mut self, outcome: PacketOutcome) {
        let payload = outcome.status.to_string();
        let commands = outcome
            .status
            .get("commands")
            .and_then(serde_json::Value::as_array);
        let total = commands.map_or(0, |commands| commands.len());
        let nested = commands.map_or(0, |commands| {
            commands
                .iter()
                .filter(|command| {
                    !command
                        .get("parent_index")
                        .is_none_or(serde_json::Value::is_null)
                })
                .count()
        });

        let completion = outcome.completed.map(|counts| {
            json!({
                "event": "complete",
                "artifacts": counts.artifacts,
                "weapons": counts.weapons,
                "materials": counts.materials,
                "characters": counts.characters,
                "achievements": counts.achievements,
            })
        });

        {
            let mut status = self.lock_status();
            status.payloads += 1;
            if total == 0 {
                status.empty_payloads += 1;
            }
            status.commands += total as u64;
            status.nested += nested as u64;
            // Sticky: the completion belongs to the session, and the packets that
            // follow it must not erase it from `/api/state`.
            if completion.is_some() {
                status.completion = completion.clone();
            }
        }

        // The packet comes first, and always: a completion is reported *about* the
        // packet that finished collection, so dropping that packet out of the
        // stream made the achievement dump vanish from a view that asked for all
        // of them.
        if let Some(dump) = self.dump.as_mut() {
            let _ = writeln!(dump, "{payload}");
        }
        let seq = self.history.push(payload.clone());
        self.fanout.publish(packet_event(seq, payload));

        if let Some(completion) = completion {
            self.fanout.publish(system_event(completion));
        }
    }

    fn report(&mut self, message: &str) {
        self.lock_status().error = Some(message.to_string());
        self.fanout
            .publish(system_event(json!({"event": "error", "message": message})));
    }

    fn lock_status(&self) -> MutexGuard<'_, Status> {
        self.state.status.lock().expect("status lock")
    }
}

/// The Android contract, verbatim, on an SSE channel named `packet`. The `id` is
/// the history sequence, which is what lets a tab that reconnected ask for exactly
/// what it missed.
fn packet_event(seq: u64, payload: String) -> String {
    format!("id: {seq}\n{}", event_frame("packet", &payload))
}

/// Everything that is not a packet: what opened the session, how the replay is
/// doing, and every failure. A front end should not have to guess at these.
fn system_event(value: serde_json::Value) -> String {
    event_frame("system", &value.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_status_names_every_counter_a_view_needs() {
        let status = Status {
            source: Some(FrameSource::File(PathBuf::from("/tmp/a.pcap"))),
            records: 10,
            payloads: 4,
            empty_payloads: 1,
            commands: 37,
            nested: 30,
            key_origin: Some(KeyOrigin::KnownBody),
            ..Default::default()
        };
        let value = status.json(2, 5, 4_000);
        assert_eq!(value["source"], "/tmp/a.pcap");
        assert_eq!(value["payloads"], 4);
        assert_eq!(value["empty_payloads"], 1);
        assert_eq!(value["commands"], 37);
        assert_eq!(value["nested_commands"], 30);
        assert_eq!(value["key_origin"], "known_body");
        assert_eq!(value["stream_clients"], 2);
        assert_eq!(value["dropped_events"], 5);
        assert_eq!(value["history_depth"], 4_000);
        assert_eq!(value["paused"], false);
        assert!(value["completion"].is_null(), "no completion yet");
    }

    #[test]
    fn a_packet_event_carries_the_payload_untouched_and_numbered() {
        let event = packet_event(7, r#"{"packet_id":7}"#.into());
        assert_eq!(
            event,
            "id: 7\nevent: packet\ndata: {\"packet_id\":7}\n\n",
            "the payload must be the contract bytes, with the history number alongside it"
        );
    }
}
