//! A local viewer for decoded game traffic, fed by a file, a pipe or the wire.
//!
//! `cargo run --release -- --file /tmp/e2e.pcap` then open http://127.0.0.1:1984/.
//! The point is that this binary is not a client: it owns no decoding, no payload
//! shape and no key logic. Everything a browser sees comes out of
//! `irminsul-decode`, so a parse fix lands here and on Android at the same moment.
//!
//! A dumped file needs no capture permission, no `/dev/bpf` and no root — the pcap
//! reader is the core's own, and the device produces the files through
//! `IrminsulCapture.dumpRawPackets` plus `adb pull`. `--live en0` reads the
//! interface straight through `tcpdump`, and `--file -` takes pcap bytes from
//! standard input so that whoever holds the privilege can be a different process
//! from whoever holds the browser.

mod events;
mod frame_source;
mod history;
mod http;
mod replay;

use std::collections::HashSet;
use std::io::{BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::Arc;
use std::thread;

use anyhow::{Context, Result, bail};
use irminsul_decode::Session;
use serde_json::json;
use tracing::info;

use crate::events::Fanout;
use crate::frame_source::{DEFAULT_BPF, FrameSource};
use crate::history::History;
use crate::http::{Response, event_frame};
use crate::replay::{Command, Replay, ReplayState, Status};

const DEFAULT_PORT: u16 = 1984;

/// The whole front end: one file, compiled in, no bundler and no CDN.
const PAGE: &[u8] = include_bytes!("../web/index.html");

#[derive(Debug)]
pub struct Config {
    pub source: Option<FrameSource>,
    pub port: u16,
    pub samples_dir: Option<PathBuf>,
    pub dump_jsonl: Option<PathBuf>,
    pub upload_dir: PathBuf,
    /// Replay and exit instead of serving. What `--dump-jsonl` is diffed with.
    pub once: bool,
}

fn main() -> Result<()> {
    // Quiet unless asked, and on stderr: stdout is for the tool's own output.
    // The decoder logs every command it reads and errors every packet it cannot
    // open, which on a replay of a gappy capture is thousands of lines — and what
    // a session cannot decrypt is an event now (`key_origin` on the stream), so
    // the state does not have to be inferred from log noise. `RUST_LOG=warn`
    // brings the library's own voice back when that noise is the point.
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "off,irminsul_viewer=info".into()),
        )
        .init();

    let mut config = Config::parse(std::env::args().skip(1).collect())?;
    let state = Arc::new(ReplayState {
        // A session with nothing to decrypt yet, so a body request before the
        // first frame answers "not cached" instead of failing to bind.
        session: std::sync::Mutex::new(Session::new(config.samples_dir.as_deref())?),
        status: std::sync::Mutex::new(Status {
            paused: true,
            ..Default::default()
        }),
    });
    let fanout = Arc::new(Fanout::new());
    let history = Arc::new(History::new());
    let (sender, receiver) = std::sync::mpsc::channel();

    let worker = Replay::spawn(
        state.clone(),
        fanout.clone(),
        history.clone(),
        receiver,
        config.samples_dir.clone(),
        config.dump_jsonl.clone(),
    )?;

    if let Some(source) = config.source.take() {
        sender.send(Command::Load(source)).context("replay thread gone")?;
    }

    if config.once {
        // Closing the channel is what ends the replay thread. With no server
        // waiting for the next request, its parked `recv` would never return, and
        // the tail of `--dump-jsonl` would never be flushed.
        drop(sender);
        worker
            .join()
            .map_err(|_| anyhow::anyhow!("replay thread panicked"))?;
        let status = state.status.lock().expect("status lock");
        println!(
            "records: {}, payloads: {} ({} with nothing in them), commands: {}, inside envelopes: {}",
            status.records,
            status.payloads,
            status.empty_payloads,
            status.commands,
            status.nested
        );
        // A replay that never got a source has no records to show, and exiting 0
        // would let a script diff an empty dump against a full one and call it a
        // pass.
        if let Some(error) = &status.error {
            bail!("--once failed: {error}");
        }
        return Ok(());
    }

    serve(config, state, fanout, history, sender)
}

fn serve(
    config: Config,
    state: Arc<ReplayState>,
    fanout: Arc<Fanout>,
    history: Arc<History>,
    sender: std::sync::mpsc::Sender<Command>,
) -> Result<()> {
    let address = format!("127.0.0.1:{}", config.port);
    let listener = TcpListener::bind(&address)
        .with_context(|| format!("cannot listen on {address} — is another viewer running?"))?;
    let local = listener
        .local_addr()
        .context("the listener should know its own address")?;
    info!(address = %local, "viewer listening");
    println!("http://{local}/   (upload dir {})", config.upload_dir.display());

    for stream in listener.incoming() {
        let mut stream = match stream {
            Ok(stream) => stream,
            Err(e) => {
                tracing::warn!("connection failed: {e}");
                continue;
            }
        };
        let state = state.clone();
        let fanout = fanout.clone();
        let history = history.clone();
        let sender = sender.clone();
        let upload_dir = config.upload_dir.clone();
        thread::spawn(move || {
            if let Err(e) = handle(&mut stream, state, fanout, history, sender, upload_dir) {
                // A browser hanging up mid-response is normal; say it once, quietly.
                tracing::debug!("connection closed: {e}");
            }
        });
    }
    Ok(())
}

fn handle(
    stream: &mut TcpStream,
    state: Arc<ReplayState>,
    fanout: Arc<Fanout>,
    history: Arc<History>,
    sender: std::sync::mpsc::Sender<Command>,
    upload_dir: PathBuf,
) -> anyhow::Result<()> {
    let mut reader = BufReader::new(stream.try_clone()?);
    let request = match http::read_request(&mut reader) {
        None => return Ok(()),
        Some(Ok(request)) => request,
        // A rejected request still gets an answer with the status that explains
        // it, because a silent connection close is indistinguishable from a crash.
        Some(Err(reject)) => {
            Response::text(reject.status, reject.message).write_to(stream)?;
            return Ok(());
        }
    };

    match (request.method.as_str(), request.path.as_str()) {
        ("GET", "/api/stream") => stream_events(stream, fanout)?,
        ("GET", "/api/state") => {
            let status = state.status.lock().expect("status lock");
            let value = status.json(fanout.client_count(), fanout.dropped_count(), history.len());
            Response::json(200, value).write_to(stream)?;
        }
        ("GET", "/api/body") => command_body(stream, &state, &request)?,
        ("GET", "/api/history") => recent_history(stream, &history, &request)?,
        ("GET", "/api/raw") => command_raw(stream, &state, &request)?,
        ("POST", "/api/start") => {
            let path = json_field(&request.body, "file")?;
            let command = match &path {
                // A browser loads files; only the command line starts a capture,
                // because that is the one thing here that needs another user's
                // privileges and a filter of someone else's choosing.
                Some(path) => Command::Load(FrameSource::File(PathBuf::from(path))),
                None => Command::Resume,
            };
            sender.send(command).context("replay thread gone")?;
            let accepted = if path.is_some() { "load" } else { "resume" };
            Response::json(202, json!({"accepted": accepted})).write_to(stream)?;
        }
        ("POST", "/api/stop") => {
            sender
                .send(Command::Pause)
                .context("replay thread gone")?;
            Response::json(202, json!({"accepted": "pause"})).write_to(stream)?;
        }
        ("POST", "/api/upload") => upload(stream, &request.body, &upload_dir)?,
        ("GET", "/") => Response::html(PAGE).write_to(stream)?,
        (_, path) if path.starts_with("/api/") => Response::text(
            405,
            format!("{path}: {} is not a method here. {}", request.method, endpoints()),
        )
        .write_to(stream)?,
        _ => Response::text(404, endpoints()).write_to(stream)?,
    }
    Ok(())
}

fn stream_events(stream: &mut TcpStream, fanout: Arc<Fanout>) -> anyhow::Result<()> {
    let client = fanout.subscribe();
    http::write_stream_head(stream)?;
    // Say something first so a browser's `onopen` means the stream is live rather
    // than that a response merely started, and name the queue it belongs to: a
    // tab that later sees `dropped_events` rise in `/api/state` can tell whether
    // it was the slow one.
    let hello = json!({"event": "hello", "client": client.id(), "clients": fanout.client_count()});
    stream.write_all(event_frame("system", &hello.to_string()).as_bytes())?;
    stream.flush()?;

    while let Some(event) = client.next() {
        stream.write_all(event.as_bytes())?;
        stream.flush()?;
    }
    Ok(())
}

fn command_body(
    stream: &mut TcpStream,
    state: &ReplayState,
    request: &http::Request,
) -> anyhow::Result<()> {
    let parse = |key: &str| request.param(key).and_then(|value| value.parse::<u64>().ok());
    let response = match (parse("packet"), parse("index")) {
        (Some(packet), Some(index)) => {
            let body = state
                .session
                .lock()
                .expect("session lock")
                .command_body(packet, index as usize);
            match body {
                Some(value) => Response::json(200, value),
                // The one failure a viewer hits for real: the ring evicted that
                // packet, or the id belongs to a replay since reloaded.
                None => Response::json(
                    404,
                    json!({"error": format!("packet {packet} command {index} is not cached")}),
                ),
            }
        }
        _ => Response::json(
            400,
            json!({"error": "packet and index are both required numbers"}),
        ),
    };
    response.write_to(stream)?;
    Ok(())
}

/// A pcap becomes a replayable file on disk; the name is ours, never the
/// client's, so an upload cannot point a write anywhere.
fn upload(stream: &mut TcpStream, body: &[u8], upload_dir: &PathBuf) -> anyhow::Result<()> {
    if body.len() < 24 {
        return Response::json(400, json!({"error": "that is too short to be a pcap file"}))
            .write_to(stream)
            .map_err(Into::into);
    }
    std::fs::create_dir_all(upload_dir)
        .with_context(|| format!("cannot create {}", upload_dir.display()))?;
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|since| since.as_millis())
        .unwrap_or_default();
    let path = upload_dir.join(format!("upload-{stamp}.pcap"));
    std::fs::write(&path, body).with_context(|| format!("cannot write {}", path.display()))?;

    let magic = u32::from_be_bytes(body[0..4].try_into().expect("four bytes checked"));
    let known = matches!(magic, 0xa1b2c3d4 | 0xa1b23c4d | 0xd4c3b2a1 | 0x4d3cb2a1);
    info!(file = %path.display(), bytes = body.len(), known_magic = known, "uploaded");
    Response::json(
        200,
        json!({
            "file": path.display().to_string(),
            "bytes": body.len(),
            // Say it now rather than letting the replay fail with a magic-number
            // error two requests later.
            "warning": if known { serde_json::Value::Null } else { json!("not a classic pcap header") },
        }),
    )
    .write_to(stream)?;
    Ok(())
}

/// The payloads a tab missed, each tagged with its sequence number so the client
/// can drop the ones that also arrive live while it is catching up.
fn recent_history(
    stream: &mut TcpStream,
    history: &History,
    request: &http::Request,
) -> anyhow::Result<()> {
    let after = request
        .param("after")
        .and_then(|value| value.parse::<u64>().ok())
        .unwrap_or(0);
    let slice = history.after(after);
    let payloads = slice.items
        .into_iter()
        .map(|(seq, payload)| format!("{{\"seq\":{seq},\"payload\":{payload}}}"))
        .collect::<Vec<_>>()
        .join(",");
    let oldest = match slice.oldest_seq {
        Some(seq) => seq.to_string(),
        None => "null".to_string(),
    };
    let body = format!("{{\"next_seq\":{},\"oldest_seq\":{},\"payloads\":[{payloads}]}}", slice.next_seq, oldest);
    Response {
        status: 200,
        content_type: "application/json",
        body: body.into_bytes(),
    }
    .write_to(stream)?;
    Ok(())
}

fn command_raw(
    stream: &mut TcpStream,
    state: &ReplayState,
    request: &http::Request,
) -> anyhow::Result<()> {
    let parse = |key: &str| request.param(key).and_then(|value| value.parse::<u64>().ok());
    let response = match (parse("packet"), parse("index")) {
        (Some(packet), Some(index)) => {
            let raw = state
                .session
                .lock()
                .expect("session lock")
                .command_body_base64(packet, index as usize);
            match raw {
                Some(body) => Response::json(200, json!({"proto_data_base64": body})),
                None => Response::json(
                    404,
                    json!({"error": format!("packet {packet} command {index} is not cached")}),
                ),
            }
        }
        _ => Response::json(
            400,
            json!({"error": "packet and index are both required numbers"}),
        ),
    };
    response.write_to(stream)?;
    Ok(())
}

fn json_field(body: &[u8], key: &str) -> Result<Option<String>> {
    if body.is_empty() {
        return Ok(None);
    }
    let value: serde_json::Value = serde_json::from_slice(body)
        .with_context(|| format!("a JSON body was not parsable: {}", String::from_utf8_lossy(&body[..body.len().min(120)])))?;
    Ok(value
        .get(key)
        .and_then(serde_json::Value::as_str)
        .map(str::to_owned))
}

fn endpoints() -> String {
    [
        "GET  /                           the page",
        "GET  /api/stream                 packet payloads + system events (SSE)",
        "GET  /api/state                  where the replay stands",
        "GET  /api/history?after=N        payloads a tab missed, with their numbers",
        "GET  /api/body?packet=N&index=N  one command's decoded JSON",
        "GET  /api/raw?packet=N&index=N   one command's proto bytes, base64",
        "POST /api/start                  resume; or {\"file\":\"/path.pcap\"} to load",
        "POST /api/stop                   stop feeding; a live capture stops too",
        "POST /api/upload                 raw pcap body -> a file to load",
        "",
        "A live capture is started from the command line (--live), never over HTTP.",
    ]
    .join("\n")
}

impl Config {
    fn parse(args: Vec<String>) -> Result<Self> {
        let mut config = Config {
            source: None,
            port: DEFAULT_PORT,
            samples_dir: None,
            dump_jsonl: None,
            upload_dir: std::env::temp_dir().join("irminsul-viewer"),
            once: false,
        };
        let mut seen = HashSet::new();
        let mut bpf = DEFAULT_BPF.to_string();
        // `--live` on its own lets `tcpdump` pick the interface, so it is tracked
        // apart from a file source and turned into one at the end.
        let mut live = None::<Option<String>>;
        let mut args = args.into_iter().peekable();

        while let Some(arg) = args.next() {
            let mut value = |name: &str| -> Result<String> {
                if !seen.insert(name.to_string()) {
                    bail!("{name} given twice");
                }
                args.next()
                    .with_context(|| format!("{name} needs a value"))
            };
            match arg.as_str() {
                // `-` is the whole difference between a file and a pipe here: the
                // reader is the same, and so is everything downstream of it.
                "--file" => match value("--file")?.as_str() {
                    "-" => config.source = Some(FrameSource::Stdin),
                    path => config.source = Some(FrameSource::File(PathBuf::from(path))),
                },
                "--live" => {
                    if !seen.insert("--live".to_string()) {
                        bail!("--live given twice");
                    }
                    live = Some(match args.peek() {
                        Some(next) if !next.starts_with('-') => args.next(),
                        _ => None,
                    });
                }
                "--bpf" => bpf = value("--bpf")?,
                "--port" => {
                    let raw = value("--port")?;
                    config.port = raw
                        .parse()
                        .with_context(|| format!("--port is not a number: {raw}"))?;
                }
                "--samples" => config.samples_dir = Some(PathBuf::from(value("--samples")?)),
                "--dump-jsonl" => config.dump_jsonl = Some(PathBuf::from(value("--dump-jsonl")?)),
                "--out" => config.upload_dir = PathBuf::from(value("--out")?),
                "--once" => config.once = true,
                "--help" | "-h" => {
                    println!(
                        "usage: irminsul-viewer [--file <pcap|-> | --live [iface] [--bpf <expr>]]\n\
                         \t\t [--port {DEFAULT_PORT}] [--samples <dir>]\n\
                         \t\t [--dump-jsonl <path>] [--out <dir>] [--once]\n\n\
                         --file - reads a pcap from standard input, which is how a capture can\n\
                         \x20 run under one privilege while the viewer stays unprivileged:\n\
                         \x20 sudo tcpdump -U -n -s0 -w - udp | irminsul-viewer --file -\n\
                         --live captures an interface through libpcap, which on macOS means\n\
                         \x20 root or the access_bpf group. --bpf replaces the filter, which\n\
                         \x20 defaults to {DEFAULT_BPF}.\n\
                         --once replays and exits, which is what makes --dump-jsonl comparable\n\
                         \x20 line-for-line with the Android side's output.\n\
                         With no source the viewer still serves; load one with POST /api/upload."
                    );
                    std::process::exit(0);
                }
                other => bail!("unknown option {other} — see --help"),
            }
        }

        if let Some(iface) = live {
            if config.source.is_some() {
                bail!("--live and --file pick different sources; give one");
            }
            config.source = Some(FrameSource::Live { iface, bpf });
        }

        if let Some(FrameSource::File(path)) = &config.source {
            if !path.is_file() {
                bail!("--file {} is not a readable file", path.display());
            }
        }
        if config.once {
            match &config.source {
                None => {
                    bail!("--once needs a source to replay: --file <pcap>, --file - or --live")
                }
                Some(FrameSource::Live { .. }) => {
                    bail!("--once cannot replay a live capture: it has no end")
                }
                Some(_) => {}
            }
        }
        Ok(config)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config(args: &[&str]) -> Result<Config> {
        Config::parse(args.iter().map(|arg| arg.to_string()).collect())
    }

    #[test]
    fn an_upload_dir_is_still_the_default_when_nothing_is_given() {
        let parsed = config(&[]).unwrap();
        assert_eq!(parsed.port, DEFAULT_PORT);
        assert!(parsed.source.is_none());
        assert!(!parsed.once);
        assert!(parsed.upload_dir.ends_with("irminsul-viewer"));
    }

    #[test]
    fn a_dash_is_a_pipe_and_anything_else_is_a_file() {
        assert_eq!(config(&["--file", "-"]).unwrap().source, Some(FrameSource::Stdin));

        let path = std::env::temp_dir().join("irminsul-viewer-source-test.pcap");
        std::fs::write(&path, []).unwrap();
        assert_eq!(
            config(&["--file", &path.display().to_string()]).unwrap().source,
            Some(FrameSource::File(path.clone()))
        );
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn an_interface_after_live_is_optional_and_an_option_is_not_one() {
        assert_eq!(
            config(&["--live"]).unwrap().source,
            Some(FrameSource::Live {
                iface: None,
                bpf: DEFAULT_BPF.to_string(),
            })
        );
        let parsed = config(&["--live", "en0", "--port", "2000"]).unwrap();
        assert_eq!(
            parsed.source,
            Some(FrameSource::Live {
                iface: Some("en0".into()),
                bpf: DEFAULT_BPF.to_string(),
            })
        );
        assert_eq!(parsed.port, 2000, "an option after --live is still an option");
    }

    #[test]
    fn a_bpf_expression_replaces_the_default_filter() {
        let parsed = config(&["--live", "--bpf", "udp port 22101"]).unwrap();
        assert_eq!(
            parsed.source,
            Some(FrameSource::Live {
                iface: None,
                bpf: "udp port 22101".into(),
            })
        );
    }

    #[test]
    fn one_option_picks_one_source() {
        let error = config(&["--file", "-", "--live"]).unwrap_err().to_string();
        assert!(error.contains("give one"), "{error}");
    }

    #[test]
    fn a_flag_without_its_value_is_named_rather_than_ignored() {
        let error = config(&["--samples"]).unwrap_err().to_string();
        assert!(error.contains("--samples"), "{error}");
    }

    #[test]
    fn a_replay_and_exit_needs_something_that_ends() {
        assert!(config(&["--once"]).unwrap_err().to_string().contains("--file"));
        assert!(
            config(&["--once", "--live"])
                .unwrap_err()
                .to_string()
                .contains("no end")
        );
        assert!(config(&["--once", "--file", "-"]).is_ok());
        assert!(
            config(&["--once", "--file", "/definitely/not/here.pcap"])
                .unwrap_err()
                .to_string()
                .contains("not a readable file")
        );
    }

    #[test]
    fn a_missing_file_is_caught_before_anything_listens() {
        assert!(config(&["--file", "/definitely/not/here.pcap"]).is_err());
    }

    #[test]
    fn an_option_given_twice_is_an_error_not_a_last_one_wins() {
        let dir = std::env::temp_dir();
        let second = dir.join("irminsul-viewer-test-b.pcap");
        std::fs::write(&second, []).unwrap();
        let error = config(&["--file", "/nope.pcap", "--file", &second.display().to_string()])
            .unwrap_err()
            .to_string();
        assert!(error.contains("--file given twice"), "{error}");
        let _ = std::fs::remove_file(&second);
    }

    #[test]
    fn a_start_body_may_carry_a_file_or_nothing() {
        assert_eq!(json_field(b"", "file").unwrap(), None);
        assert_eq!(
            json_field(br#"{"file":"/tmp/a.pcap"}"#, "file").unwrap(),
            Some("/tmp/a.pcap".to_string())
        );
        assert_eq!(json_field(br#"{"other":1}"#, "file").unwrap(), None);
        assert!(json_field(b"{not json", "file").is_err());
    }
}
