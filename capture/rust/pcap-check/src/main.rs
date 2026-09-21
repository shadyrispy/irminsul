//! Offline verification tool: replay a pcap exported by the app through the same
//! pipeline the app runs, and report what came out.
//!
//! Usage:
//!   `cargo run --release -- <capture.pcap> [dump_cmd_id]`
//!     Per-command decode health: names, sizes, parse errors, envelope contents.
//!   `cargo run --release -- --status [--samples DIR] <capture.pcap>`
//!     One status JSON per packet, exactly as a front end receives it.
//!
//! The first mode drives [`GameSniffer`] directly so its counters stay comparable
//! across refactors; the second drives [`Session`], so what it prints is the
//! payload contract under real traffic.

use std::path::PathBuf;
use std::collections::HashMap;

use anyhow::{Context, Result, bail};
use auto_artifactarium::{
    matches_achievement_packet, matches_avatar_packet, matches_item_packet, GameCommand, GamePacket,
    GameSniffer, PacketDirection,
};
use irminsul_decode::{PcapFrames, Session, dispatch_keys, key_origin_name, prepare_frame};
use serde_json::Value;
use std::path::Path;

/// Commands carried inside batch envelopes, counting envelopes inside envelopes.
/// Reported alongside the visible total because a list that only showed the
/// envelopes would look healthy while hiding most of the traffic.
fn count_inner(cmd: &GameCommand) -> usize {
    let children = cmd.children();
    children.len() + children.iter().map(count_inner).sum::<usize>()
}

fn dump_body(cmd_id: u16, json: &Value) {
    let Some(data) = json.get("data") else {
        println!("  cmd {cmd_id}: (no data field)");
        return;
    };
    let Some(obj) = data.as_object() else {
        println!("  cmd {cmd_id} data: {data}");
        return;
    };
    println!(
        "  cmd {cmd_id} ({}):",
        json.get("name").and_then(Value::as_str).unwrap_or("?")
    );
    for (k, v) in obj {
        match v {
            Value::Array(a) => println!("    {k}: [{}]", a.len()),
            Value::Object(_) => println!("    {k}: {{...}}"),
            _ => println!("    {k}: {v}"),
        }
    }
}

fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .init();

    let mut raw = std::env::args().skip(1);
    let mut status_mode = false;
    let mut samples: Option<PathBuf> = None;
    let mut positional: Vec<String> = Vec::new();
    while let Some(arg) = raw.next() {
        match arg.as_str() {
            "--status" => status_mode = true,
            "--samples" => {
                let dir = raw.next().context("--samples needs a directory")?;
                samples = Some(PathBuf::from(dir));
            }
            other if other.starts_with("--") => bail!("unknown option {other}"),
            other => positional.push(other.to_owned()),
        }
    }
    let mut positional = positional.into_iter();
    let Some(pcap_path) = positional.next().map(PathBuf::from) else {
        bail!("usage: pcap-check [--status [--samples DIR]] <capture.pcap> [dump_cmd_id]");
    };

    if status_mode {
        return replay_as_status(&pcap_path, samples.as_deref());
    }
    let dump_cmd: Option<u16> = positional.next().and_then(|s| s.parse().ok());
    replay_command_health(&pcap_path, dump_cmd)
}

fn replay_command_health(pcap_path: &Path, dump_cmd: Option<u16>) -> Result<()> {
    let mut frames =
        PcapFrames::open(pcap_path).with_context(|| format!("read {}", pcap_path.display()))?;
    println!("pcap: linktype {}", frames.linktype());

    let mut sniffer = GameSniffer::new().set_initial_keys(dispatch_keys()?);

    let mut total_records = 0usize;
    let mut fed = 0usize;
    let mut total_commands = 0usize;
    let mut inner_commands = 0usize;
    let mut parse_errors: Vec<(u16, String, u16, u32)> = Vec::new();
    let mut unknown = 0usize;
    let mut got_items = false;
    let mut got_avatars = false;
    let mut got_achievements = false;
    let mut dump_seen = 0usize;

    for record in &mut frames {
        let (record, _timestamp_ms) = match record {
            Ok(record) => record,
            // Reported rather than swallowed: a file that stops mid-record has
            // nothing to do with the game, and the totals below still matter.
            Err(e) => {
                println!("[warn] replay stopped: {e}");
                break;
            }
        };
        total_records += 1;
        let Some(frame) = prepare_frame(&record) else {
            continue;
        };
        fed += 1;
        match sniffer.receive_packet(frame) {
            None => {}
            Some(GamePacket::Connection(cp)) => {
                let kind = matches!(cp, auto_artifactarium::ConnectionPacket::HandshakeRequested);
                println!("[conn] packet #{fed}: handshake={kind}");
            }
            Some(GamePacket::Commands(commands)) => {
                for cmd in commands {
                    total_commands += 1;
                    inner_commands += count_inner(&cmd);
                    let s = cmd.summary_json();
                    println!(
                        "[cmd] #{fed} id={} {} {} header_len={} size={} field_count={:?} err={}",
                        cmd.command_id,
                        s.get("name").and_then(Value::as_str).unwrap_or("?"),
                        if cmd.direction == PacketDirection::Sent {
                            "C2S"
                        } else {
                            "S2C"
                        },
                        cmd.header_len,
                        cmd.data_len,
                        s.get("field_count"),
                        s.get("parse_error") == Some(&Value::Bool(true)),
                    );
                    if dump_cmd == Some(cmd.command_id) && dump_seen < 3 {
                        dump_seen += 1;
                        if dump_seen == 1 {
                            std::fs::write(
                                format!("/tmp/head_{:#06x}.bin", cmd.command_id),
                                &cmd.ext_header,
                            )
                            .ok();
                            std::fs::write(
                                format!("/tmp/body_{:#06x}.bin", cmd.command_id),
                                &cmd.proto_data,
                            )
                            .ok();
                            println!(
                                "dumped ext_header({}B) + proto_data({}B) of cmd {}",
                                cmd.ext_header.len(),
                                cmd.proto_data.len(),
                                cmd.command_id
                            );
                        }
                        let json = cmd.to_json();
                        dump_body(cmd.command_id, &json);
                    }
                    got_items |= matches_item_packet(&cmd).is_some();
                    got_avatars |= matches_avatar_packet(&cmd).is_some();
                    got_achievements |= matches_achievement_packet(&cmd).is_some();
                    if s.get("parse_error") == Some(&Value::Bool(true)) {
                        parse_errors.push((
                            cmd.command_id,
                            s.get("name")
                                .and_then(Value::as_str)
                                .unwrap_or("?")
                                .to_owned(),
                            cmd.header_len,
                            cmd.data_len,
                        ));
                    } else if s.get("name").and_then(Value::as_str) == Some("unknown") {
                        unknown += 1;
                    }
                }
            }
        }
    }

    println!(
        "\nrecords: {total_records}, fed to sniffer: {fed}, commands decoded: {total_commands}, inside batch envelopes: {inner_commands}"
    );
    println!("data collection: items={got_items} avatars={got_avatars} achievements={got_achievements}");
    println!(
        "parse errors: {}   unknown commands: {unknown}",
        parse_errors.len()
    );
    if let Some(id) = dump_cmd {
        if dump_seen == 0 {
            println!("cmd {id}: not found in capture");
        }
    }
    for (id, name, header_len, size) in parse_errors.iter().take(15) {
        println!("  ERR cmd {id} ({name}) header_len={header_len} size={size}");
    }
    if parse_errors.len() > 15 {
        println!("  ... {} more", parse_errors.len() - 15);
    }
    Ok(())
}

/// Drive the app's own [`Session`] and print each packet's status payload as a
/// JSON line, plus a tally of what the front end would have seen.
///
/// `--samples` points at a directory of persisted command bodies, which is what
/// lets a blind session (one whose handshake was never captured) decode at all.
/// The session rewrites that file when it learns a new body, so point it at a
/// copy unless seeing the real one update is the point.
fn replay_as_status(pcap_path: &Path, samples_dir: Option<&Path>) -> Result<()> {
    let mut session = Session::new(samples_dir).context("open a decode session")?;
    let mut frames =
        PcapFrames::open(pcap_path).with_context(|| format!("read {}", pcap_path.display()))?;

    let mut packets = 0usize;
    let mut commands = 0usize;
    let mut nested = 0usize;
    let mut origins: HashMap<String, usize> = HashMap::new();

    for record in &mut frames {
        let (record, _timestamp_ms) = match record {
            Ok(record) => record,
            Err(e) => {
                println!("[warn] replay stopped: {e}");
                break;
            }
        };
        let Some(outcome) = session.feed(&record) else {
            continue;
        };
        packets += 1;

        let summaries = outcome
            .status
            .get("commands")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        for summary in summaries {
            commands += 1;
            if !summary.get("parent_index").is_none_or(Value::is_null) {
                nested += 1;
            }
        }
        // Sampled as state, at the moment this packet decoded: a frame that
        // decodes nothing carries no payload to read an origin off.
        let origin = session
            .key_origin()
            .map(key_origin_name)
            .unwrap_or("none")
            .to_owned();
        *origins.entry(origin).or_default() += 1;

        println!("{}", serde_json::to_string(&outcome.status)?);
    }

    let mut origins: Vec<(&String, &usize)> = origins.iter().collect();
    origins.sort();
    let origins = origins
        .iter()
        .map(|(origin, count)| format!("{origin}={count}"))
        .collect::<Vec<_>>()
        .join(" ");
    println!("\nstatus payloads: {packets}, commands: {commands}, inside envelopes: {nested}");
    println!("key origin per decoded packet: {origins}");
    Ok(())
}
