//! Where the viewer's frames come from.
//!
//! Three kinds of source, one shape behind them: something that hands over
//! `(packet, timestamp)` pairs until it is told to stop. A dumped file and a pipe
//! are read by the core's pcap reader; a live interface is read by libpcap, which
//! gives up the same pairs without a byte stream in between.
//!
//! What differs is only what happens at the end. A file can be opened again from the
//! top, a live capture can be started afresh, and standard input has been read as
//! far as it goes.
//!
//! Feeding a replay is this module's business too, including the thread that does
//! it: a source that blocks until bytes arrive cannot share a thread with the loop
//! that has to answer `stop`, and that is the only reason either of them exists.

use std::io::{self, BufReader};
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{sync_channel, Receiver, SyncSender};

use anyhow::{Context, Result, bail};
use irminsul_decode::{AnyFrames, PcapFrames};
use tracing::warn;

#[cfg(feature = "live")]
use anyhow::anyhow;
#[cfg(feature = "live")]
use tracing::info;

#[cfg(feature = "live")]
use pcap::{Activated, Active, Capture, ConnectionStatus, Device};

/// The filter a live capture asks for: the game's two ports, over udp.
///
/// Those numbers are written down twice in this workspace — here and inside the
/// sniffer, which keeps its own pair private — the same way the reference desktop
/// app declares its own `capture::PORT_RANGE`. A filter wider than necessary costs
/// noise and nothing else, because the decoder still drops every packet not on
/// them; a filter too narrow loses data without saying so, which is why this is a
/// default to override rather than a fact to enforce.
pub const DEFAULT_BPF: &str = "udp and portrange 22101-22102";

/// Bytes taken from each packet. The largest command body seen on the wire is a
/// little over 160 KiB, and a truncated packet simply fails to decode, so this is
/// not left to whatever the platform's libpcap happens to default to.
#[cfg(feature = "live")]
const SNAPLEN: i32 = 262_144;

/// How long a live read waits for a packet before coming back empty-handed. Each of
/// those returns is a chance to notice that the replay stopped listening, which is
/// what bounds how long `stop` takes on an interface with no traffic.
#[cfg(feature = "live")]
const READ_TIMEOUT_MS: i32 = 20;

#[derive(Clone, Debug, PartialEq)]
pub enum FrameSource {
    File(PathBuf),
    /// Classic pcap on standard input. This is the escape hatch for every transport
    /// the viewer does not know about — a phone's dump over `adb exec-out`, a
    /// capture run under `sudo` while the viewer stays unprivileged.
    Stdin,
    Live {
        iface: Option<String>,
        bpf: String,
    },
}

impl FrameSource {
    /// What to call this source in a status line and in every error it raises.
    pub fn name(&self) -> String {
        match self {
            FrameSource::File(path) => path.display().to_string(),
            FrameSource::Stdin => "standard input".into(),
            FrameSource::Live { iface, .. } => match iface {
                Some(iface) => format!("live capture on {iface}"),
                None => "live capture on every connected interface".into(),
            },
        }
    }

    /// Whether opening this source again means anything. A file gives the same
    /// frames from the top and a live capture starts a fresh one, but standard
    /// input has already been read as far as it goes.
    pub fn can_reopen(&self) -> bool {
        !matches!(self, FrameSource::Stdin)
    }

    /// Whether this source keeps producing whether anyone is watching or not. A live
    /// capture does, so pausing it has to end it rather than stop looking at it.
    pub fn is_live(&self) -> bool {
        matches!(self, FrameSource::Live { .. })
    }

    /// Open the source, before anything is read from it. A bad interface name or a
    /// missing permission is worth answering with the error libpcap actually gave,
    /// rather than with a replay that appears to be running and never produces a
    /// frame.
    pub fn open(&self) -> Result<Opened> {
        let name = self.name();
        match self {
            FrameSource::File(path) => {
                let frames = PcapFrames::open(path).with_context(|| format!("read {name}"))?;
                Ok(Opened::stream(frames.boxed()))
            }
            FrameSource::Stdin => {
                let frames = AnyFrames::from_stream(BufReader::new(io::stdin()), &name)
                    .with_context(|| format!("read {name}"))?;
                Ok(Opened::stream(frames))
            }
            FrameSource::Live { iface, bpf } => live(&name, iface.as_deref(), bpf),
        }
    }
}

/// A source that is open and ready to produce.
pub enum Opened {
    /// A pcap byte stream: a file, or a pipe someone else fills.
    Stream { frames: AnyFrames },
    /// One handle per interface, all carrying the same filter. The game's traffic can
    /// be on any of them, which is why this is a list.
    #[cfg(feature = "live")]
    Live { handles: Vec<Handle<Active>> },
}

impl Opened {
    fn stream(frames: AnyFrames) -> Self {
        Opened::Stream { frames }
    }

    pub fn linktype(&self) -> u32 {
        match self {
            Opened::Stream { frames, .. } => frames.linktype(),
            #[cfg(feature = "live")]
            Opened::Live { handles } => handles.first().map_or(0, Handle::linktype),
        }
    }

    /// The interfaces a live source ended up reading. Empty for a stream, where the
    /// source name already says everything a view needs.
    pub fn devices(&self) -> Vec<String> {
        match self {
            Opened::Stream { .. } => Vec::new(),
            #[cfg(feature = "live")]
            Opened::Live { handles } => {
                handles.iter().map(|handle| handle.name.clone()).collect()
            }
        }
    }
}

/// How far a source may run ahead of the decoder. Big enough to ride out a burst
/// without the kernel dropping anything; small enough that a replay which stopped
/// reading is felt by the capture as backpressure rather than as a memory leak.
const FRAME_QUEUE: usize = 256;

/// One frame out of a source, or the reason the source has stopped giving them.
///
/// Private to this module on purpose: it is the wire between a source's threads, and
/// what the replay sees is [`Tick`].
#[derive(Debug)]
enum SourceEvent {
    Frame { bytes: Vec<u8>, capture_ms: u64 },
    /// Reading failed: a stream that is not a pcap, or one that stopped mid-record.
    Failed(String),
}

impl Opened {
    /// Put this source on threads of its own and hand back what to read from.
    ///
    /// Dropping the receiver is what stops the source — every loop here checks for
    /// that between packets — so a replay that is paused, finished or gone leaves no
    /// capture running behind it. A live source is one thread per interface because
    /// a read that waits out its timeout on a quiet interface must not hold up a
    /// busy one standing behind it.
    pub fn spawn(self) -> anyhow::Result<Reading> {
        let (sender, frames) = sync_channel::<SourceEvent>(FRAME_QUEUE);
        let stop = Stop::default();
        match self {
            Opened::Stream { frames: reader } => {
                std::thread::Builder::new()
                    .name("irminsul-source".into())
                    .spawn(pump_stream(reader, sender.clone()))?;
            }
            #[cfg(feature = "live")]
            Opened::Live { handles } => {
                for handle in handles {
                    let name = format!("irminsul-source-{}", handle.name);
                    let sender = sender.clone();
                    let stop = stop.clone();
                    std::thread::Builder::new()
                        .name(name)
                        .spawn(move || pump_handle(handle, sender, stop))?;
                }
            }
        }
        // The clones inside the source threads are what keeps the queue open now.
        drop(sender);
        Ok(Reading {
            frames,
            _stop: stop,
        })
    }
}

/// Set when the replay stops reading, and polled by a source thread each time it
/// comes back empty-handed.
///
/// A bounded `SyncSender` can only notice a vanished receiver by sending, and a
/// quiet interface gives it no reason to send. So the flag carries that question
/// instead, and holding it is how a source thread knows it may stop asking.
#[derive(Clone, Default)]
struct Stop(Arc<AtomicBool>);

impl Stop {
    /// Whether the reader on the other side of the queue has gone away. Only a live
    /// source has anything to ask it.
    #[cfg(feature = "live")]
    fn stopped(&self) -> bool {
        self.0.load(Ordering::Relaxed)
    }

    /// Tell every source thread watching this to finish up. Dropping a `Reading` is
    /// the only way it is ever said.
    fn halt(&self) {
        self.0.store(true, Ordering::Relaxed);
    }
}

/// A source on its own thread(s), and the queue its frames come out of.
pub struct Reading {
    frames: Receiver<SourceEvent>,
    /// Dropping the reading is what stops the capture; nothing here reads the flag.
    _stop: Stop,
}

/// What the replay side gets to hear from a source: a frame, a failure, or that
/// nothing happened — which is the answer that lets it look at its commands again.
#[derive(Debug)]
pub enum Tick {
    Frame { bytes: Vec<u8>, capture_ms: u64 },
    Failed(String),
    /// The queue was empty for the whole wait. Not an end: an interface with no
    /// traffic looks like this forever, and the capture is fine.
    Waited,
    /// Every source thread let go, which is how a capture that ran out is announced.
    Ended,
}

impl Reading {
    /// The next thing the source has to say, waiting no longer than `quiet` for it.
    pub fn next(&mut self, quiet: std::time::Duration) -> Tick {
        match self.frames.recv_timeout(quiet) {
            Ok(SourceEvent::Frame { bytes, capture_ms }) => Tick::Frame { bytes, capture_ms },
            Ok(SourceEvent::Failed(message)) => Tick::Failed(message),
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => Tick::Waited,
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => Tick::Ended,
        }
    }
}

impl Drop for Reading {
    fn drop(&mut self) {
        self._stop.halt();
    }
}

/// A pcap byte stream, read as fast as it can.
///
/// There is no waiting here for the listener to come back: a pipe delivers bytes or
/// it does not, and a read that asks `is anyone listening?` halfway through one
/// would have to unblock the read first. That is what a live handle is for.
fn pump_stream(mut frames: AnyFrames, sender: SyncSender<SourceEvent>) -> impl FnOnce() {
    move || {
        for frame in &mut frames {
            match frame {
                Ok((bytes, capture_ms)) => {
                    if sender
                        .send(SourceEvent::Frame { bytes, capture_ms })
                        .is_err()
                    {
                        return;
                    }
                }
                // The reader's own errors already name the source they came from.
                Err(e) => {
                    let message = format!("{e}");
                    warn!("{message}");
                    let _ = sender.send(SourceEvent::Failed(message));
                    return;
                }
            }
        }
        // Letting go of the sender is how a source that ran out is announced.
    }
}

/// One capture handle, polled until it breaks, ends, or is no longer wanted.
///
/// Each `Quiet` is the loop's chance to look up: an interface with no traffic
/// otherwise looks exactly like a replay that stopped reading.
#[cfg(feature = "live")]
fn pump_handle<S: Activated>(
    mut handle: Handle<S>,
    sender: SyncSender<SourceEvent>,
    stop: Stop,
) {
    loop {
        if stop.stopped() {
            info!(interface = %handle.name, "nobody is reading this capture any more");
            return;
        }
        match handle.next() {
            Next::Frame(bytes, capture_ms) => {
                if sender
                    .send(SourceEvent::Frame { bytes, capture_ms })
                    .is_err()
                {
                    return;
                }
            }
            Next::Quiet => {}
            Next::Spent => return,
            Next::Broken(e) => {
                let message = format!("{}: {e}", handle.name);
                warn!("{message}");
                let _ = sender.send(SourceEvent::Failed(message));
                return;
            }
        }
    }
}

/// What one read of a capture handle produced.
#[cfg(feature = "live")]
#[derive(Debug)]
pub enum Next {
    /// A packet, and the time the kernel put on it — milliseconds since the epoch,
    /// the same base and rounding the pcap file reader reports.
    Frame(Vec<u8>, u64),
    /// Nothing arrived within the read timeout: the capture is alive, the interface
    /// is quiet. This is where a producer gets to ask whether it is still wanted.
    Quiet,
    /// The handle has no more packets, which only a savefile-backed one can say.
    Spent,
    /// The handle is broken, with libpcap's own words about it.
    Broken(io::Error),
}

/// A capture handle with the name it was opened under, so a failure can say which
/// interface it came from.
#[cfg(feature = "live")]
pub struct Handle<S: Activated> {
    name: String,
    capture: Capture<S>,
}

#[cfg(feature = "live")]
impl<S: Activated> Handle<S> {
    fn new(name: impl Into<String>, capture: Capture<S>) -> Self {
        Handle {
            name: name.into(),
            capture,
        }
    }

    /// The next packet this handle has, or why it does not have one yet.
    pub fn next(&mut self) -> Next {
        match self.capture.next_packet() {
            Ok(packet) => Next::Frame(
                packet.data.to_vec(),
                packet.header.ts.tv_sec as u64 * 1000 + packet.header.ts.tv_usec as u64 / 1000,
            ),
            Err(pcap::Error::TimeoutExpired) => Next::Quiet,
            Err(pcap::Error::NoMorePackets) => Next::Spent,
            Err(e) => Next::Broken(io::Error::other(e.to_string())),
        }
    }

    /// The link-layer type of what this handle captures: `1` Ethernet on a Mac's
    /// interfaces, which is the framing the decoder already understands.
    ///
    /// Worth knowing before comparing it with a pcap file's header: libpcap answers in
    /// its own `DLT_*` numbering, which for raw IP is `12` on this macOS and `101` in
    /// the file. Ethernet is `1` either way, which is the case a live capture is in.
    fn linktype(&self) -> u32 {
        self.capture.get_datalink().0 as u32
    }
}

/// A live capture through libpcap, which is what the reference desktop app uses;
/// see `docs/adr/0004` for why the viewer does not run a capture subprocess.
#[cfg(feature = "live")]
fn live(name: &str, iface: Option<&str>, bpf: &str) -> Result<Opened> {
    if bpf.trim().is_empty() {
        bail!("{name}: an empty filter would capture everything — pass `--bpf ip` to mean it");
    }

    let devices = Device::list().context("cannot list capture devices")?;
    let chosen = choose(&devices, iface)?;
    let mut handles = Vec::new();
    let mut refused = Vec::new();

    for device in chosen {
        let identifier = device.name.clone();
        let opened = Capture::from_device(device)
            .map_err(|e| anyhow!("{identifier}: {e}"))?
            .snaplen(SNAPLEN)
            .immediate_mode(true)
            .timeout(READ_TIMEOUT_MS)
            .open()
            .and_then(|mut capture| {
                capture.filter(bpf, true)?;
                Ok(capture)
            })
            .map_err(|e| anyhow!("{identifier}: {e}"));
        match opened {
            Ok(capture) => handles.push(Handle::new(identifier, capture)),
            // One interface refusing is normal — a bridge with no carrier or a
            // Thunderbolt port nobody plugs in. All of them failing is the news.
            Err(e) => refused.push(format!("{e:#}")),
        }
    }

    if handles.is_empty() {
        bail!(
            "{name}: nothing could be captured on. {}",
            if refused.is_empty() {
                "no interface matched the request.".into()
            } else {
                format!(
                    "libpcap said: {} — reading the capture device needs root on macOS (or \
                     the `access_bpf` group); an unprivileged way in is to let the capture run \
                     elsewhere and pipe it: sudo tcpdump -U -n -s0 -w - {bpf} | irminsul-viewer \
                     --file -",
                    refused.join("; ")
                )
            }
        );
    }

    Ok(Opened::Live { handles })
}

/// Without the feature that can open a device, `--live` is still something a caller
/// can ask for, and the answer should say why it is not available rather than
/// pretend to be capturing.
#[cfg(not(feature = "live"))]
fn live(name: &str, _iface: Option<&str>, _bpf: &str) -> Result<Opened> {
    bail!(
        "{name}: this viewer was built without live capture — `cargo build --features live`, \
         or pipe a capture in with --file -"
    )
}

/// The interfaces to capture on: the one that was asked for, or every connected one.
///
/// A name that is not there is answered with the list that is, because knowing to
/// run `tcpdump -D` first is not something capturing should require.
#[cfg(feature = "live")]
fn choose(devices: &[Device], iface: Option<&str>) -> Result<Vec<Device>> {
    if let Some(wanted) = iface {
        return match devices.iter().find(|device| device.name == wanted) {
            Some(device) => Ok(vec![device.clone()]),
            None => Err(anyhow!(
                "no capture interface named {wanted}. Available: {}",
                devices
                    .iter()
                    .map(|device| device.name.as_str())
                    .collect::<Vec<_>>()
                    .join(", ")
            )),
        };
    }
    // Upstream's rule, and its reason: an interface that is disconnected, or whose
    // state is unknown, either yields nothing or lies about being there.
    Ok(devices
        .iter()
        .filter(|device| device.flags.connection_status == ConnectionStatus::Connected)
        .cloned()
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn each_source_calls_itself_whatever_it_is_reading() {
        assert_eq!(
            FrameSource::File(PathBuf::from("/tmp/a.pcap")).name(),
            "/tmp/a.pcap"
        );
        assert_eq!(FrameSource::Stdin.name(), "standard input");
        assert_eq!(
            FrameSource::Live {
                iface: Some("en0".into()),
                bpf: DEFAULT_BPF.into(),
            }
            .name(),
            "live capture on en0"
        );
        assert_eq!(
            FrameSource::Live {
                iface: None,
                bpf: DEFAULT_BPF.into(),
            }
            .name(),
            "live capture on every connected interface"
        );
    }

    #[test]
    fn everything_but_a_drained_pipe_can_be_opened_again() {
        assert!(FrameSource::File(PathBuf::from("/tmp/a.pcap")).can_reopen());
        assert!(
            FrameSource::Live {
                iface: None,
                bpf: DEFAULT_BPF.into(),
            }
            .can_reopen()
        );
        assert!(!FrameSource::Stdin.can_reopen());
    }

    #[test]
    fn only_a_live_capture_is_stopped_rather_than_parked() {
        assert!(
            FrameSource::Live {
                iface: None,
                bpf: DEFAULT_BPF.into(),
            }
            .is_live()
        );
        assert!(!FrameSource::Stdin.is_live());
        assert!(!FrameSource::File(PathBuf::from("/tmp/a.pcap")).is_live());
    }

    #[test]
    fn a_source_that_cannot_be_opened_says_which_one() {
        let error = FrameSource::File(PathBuf::from("/definitely/not/here.pcap"))
            .open()
            .map(|_| ())
            .unwrap_err();
        assert!(
            error.to_string().contains("/definitely/not/here.pcap"),
            "the message should name what it could not open: {error}"
        );
    }

    #[test]
    fn an_empty_filter_is_refused_rather_than_capturing_everything() {
        let error = FrameSource::Live {
            iface: Some("lo0".into()),
            bpf: "   ".into(),
        }
        .open()
        .map(|_| ())
        .unwrap_err()
        .to_string();
        assert!(error.contains("empty filter"), "{error}");
    }

    #[cfg(feature = "live")]
    fn device(name: &str, status: ConnectionStatus) -> Device {
        Device {
            name: name.into(),
            desc: None,
            addresses: Vec::new(),
            flags: pcap::DeviceFlags {
                if_flags: pcap::IfFlags::empty(),
                connection_status: status,
            },
        }
    }

    #[cfg(feature = "live")]
    fn names(devices: &[Device]) -> Vec<String> {
        devices.iter().map(|device| device.name.clone()).collect()
    }

    #[cfg(feature = "live")]
    #[test]
    fn an_interface_that_is_not_there_is_answered_with_the_ones_that_are() {
        let devices = vec![
            device("lo0", ConnectionStatus::NotApplicable),
            device("en0", ConnectionStatus::Connected),
            device("en5", ConnectionStatus::Disconnected),
        ];

        assert_eq!(names(&choose(&devices, Some("en0")).unwrap()), vec!["en0"]);

        let error = choose(&devices, Some("utun9")).unwrap_err().to_string();
        assert!(error.contains("no capture interface named utun9"), "{error}");
        assert!(
            error.contains("lo0, en0, en5"),
            "and it should say what is there: {error}"
        );
    }

    #[cfg(feature = "live")]
    #[test]
    fn without_a_name_every_connected_interface_is_taken() {
        let devices = vec![
            device("lo0", ConnectionStatus::NotApplicable),
            device("en0", ConnectionStatus::Connected),
            device("en5", ConnectionStatus::Disconnected),
            device("utun3", ConnectionStatus::Connected),
        ];
        assert_eq!(names(&choose(&devices, None).unwrap()), vec!["en0", "utun3"]);
    }

    /// The one thing a live source can get wrong with no file reader in the way: the
    /// clock. `at_capture_ms` and the wall are shared between the two kinds of source,
    /// so a frame from an interface has to carry the same number the same packet
    /// would have carried in a pcap.
    ///
    /// A savefile handle goes through the same spawn, the same thread and the same
    /// read loop as a live one, with no privilege and no interface involved — which
    /// makes it the honest way to test the path that ends nowhere near a file.
    #[cfg(feature = "live")]
    #[test]
    fn a_capture_handle_feeds_a_replay_what_the_same_file_would() {
        use std::io::Write;
        use std::time::Duration;

        let dir = std::env::temp_dir().join("irminsul-viewer-live-frames");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("c.pcap");
        let frames = [
            ethernet(&[0x45, 0xaa, 0xbb]),
            ethernet(&[0x45, 0xcc, 0xdd]),
            ethernet(&[0x45, 0xee, 0xff]),
        ];
        std::fs::File::create(&path)
            .unwrap()
            .write_all(&pcap_bytes(&frames.iter().map(|f| f.as_slice()).collect::<Vec<_>>()))
            .unwrap();

        let mut from_file = Vec::new();
        let mut frames = PcapFrames::open(&path).unwrap();
        let linktype = frames.linktype();
        while let Some(frame) = frames.next() {
            from_file.push(frame.unwrap());
        }
        assert_eq!(from_file.len(), 3, "the fixture reads back");

        // `Opened::Live` holds live handles only, so the savefile goes straight to
        // the function every one of those threads runs.
        let capture = Capture::from_file(&path).map_err(|e| e.to_string()).unwrap();
        let handle = Handle::new("c.pcap", capture);
        assert_eq!(handle.linktype(), linktype, "both readers see the same framing");

        let (sender, frames) = sync_channel(FRAME_QUEUE);
        let stop = Stop::default();
        let mut reading = Reading {
            frames,
            _stop: stop.clone(),
        };
        let source = std::thread::spawn(move || pump_handle(handle, sender, stop));
        let from_handle: Vec<_> = std::iter::from_fn(|| match reading.next(Duration::from_secs(2)) {
            Tick::Frame { bytes, capture_ms } => Some((bytes, capture_ms)),
            Tick::Ended => None,
            other => panic!("a savefile produced {other:?}, and that is not its part"),
        })
        .collect();
        source.join().expect("the source thread panicked");
        let _ = std::fs::remove_dir_all(&dir);

        assert_eq!(
            from_handle, from_file,
            "libpcap's view of these bytes must match the reader's exactly, timestamps included"
        );
    }

    /// A source with nothing to say is the case the thread exists for: the replay
    /// that is waiting on it still has to come back and look at its commands.
    #[test]
    fn a_quiet_source_still_answers_in_one_poll() {
        use std::time::{Duration, Instant};

        // The replay's own patience, spelled out here because it is the caller's.
        const WAIT: Duration = Duration::from_millis(20);

        let (sender, frames) = std::sync::mpsc::sync_channel(FRAME_QUEUE);
        let mut reading = Reading {
            frames,
            _stop: Stop::default(),
        };

        let asked = Instant::now();
        assert!(
            matches!(reading.next(WAIT), Tick::Waited),
            "an empty queue is not an ended one"
        );
        assert!(
            asked.elapsed() < Duration::from_secs(1),
            "the wait is about {WAIT:?}, not however long the source takes to speak"
        );

        drop(sender);
        assert!(matches!(reading.next(WAIT), Tick::Ended));
    }

    /// A little-endian pcap in the framing a Mac's interface actually gives: Ethernet
    /// (`1`), which both readers agree on — and which is what `prepare_frame` passes
    /// through untouched rather than wrapping.
    fn pcap_bytes(records: &[&[u8]]) -> Vec<u8> {
        let mut out = vec![
            0xd4, 0xc3, 0xb2, 0xa1, // magic, little-endian
            0x02, 0x00, 0x04, 0x00, // version 2.4
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // thiszone, sigts
            0xff, 0xff, 0x00, 0x00, // snaplen
            1, 0x00, 0x00, 0x00, // linktype: Ethernet
        ];
        for record in records {
            let len = (record.len() as u32).to_le_bytes();
            out.extend_from_slice(&[0x11, 0x00, 0x00, 0x00, 0x22, 0x00, 0x00, 0x00]);
            out.extend_from_slice(&len);
            out.extend_from_slice(&len);
            out.extend_from_slice(record);
        }
        out
    }

    /// An Ethernet frame carrying a bare IPv4 packet, which is the shape a live
    /// capture hands over.
    fn ethernet(body: &[u8]) -> Vec<u8> {
        let mut frame = vec![0u8; 12];
        frame.extend_from_slice(&[0x08, 0x00]);
        frame.extend_from_slice(body);
        frame
    }
}
