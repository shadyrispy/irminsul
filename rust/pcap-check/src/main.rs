//! Offline verification tool: replay a pcap exported by the app through the
//! same pipeline as `nativeProcessPacket` (PCAPdroid trailer strip → fake
//! Ethernet header → `GameSniffer`) and report command parse health.
//!
//! Usage: `cargo run --release -- <capture.pcap> [dump_cmd_id]`

use std::{collections::HashMap, fs, path::PathBuf};

use anyhow::{bail, Context, Result};
use auto_artifactarium::{
    matches_achievement_packet, matches_avatar_packet, matches_item_packet, GamePacket, GameSniffer,
};
use base64::Engine;
use serde_json::Value;

fn load_keys() -> Result<HashMap<u16, Vec<u8>>> {
    let keys_json: HashMap<u16, String> = serde_json::from_slice(include_bytes!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../irminsul-core/keys/gi.json"
    ))).context("parse keys/gi.json")?;
    keys_json
        .into_iter()
        .map(|(k, v)| {
            let decoded = base64::engine::general_purpose::STANDARD
                .decode(&v)
                .map_err(|e| anyhow::anyhow!("decode key {k}: {e}"))?;
            Ok((k, decoded))
        })
        .collect()
}

const TRAILER_SIZE: usize = 32;

/// Identical to irminsul-jni's `extract_and_prepare_packet`.
fn prepare(data: &[u8]) -> Option<Vec<u8>> {
    if data.len() < TRAILER_SIZE {
        return None;
    }
    let mut packet_data = data;
    let trailer = &packet_data[packet_data.len() - TRAILER_SIZE..];
    if trailer[0] == 0x01 && trailer[1] == 0x00 {
        packet_data = &packet_data[..packet_data.len() - TRAILER_SIZE];
    }
    if packet_data.is_empty() {
        return None;
    }
    let first_byte = packet_data[0];
    let ether_type: [u8; 2] = if (first_byte >> 4) == 0x04 {
        [0x08, 0x00]
    } else if (first_byte & 0xF0) == 0x60 {
        [0x86, 0xDD]
    } else {
        return Some(packet_data.to_vec());
    };
    let mut frame = Vec::with_capacity(14 + packet_data.len());
    frame.extend_from_slice(&[0x00; 12]);
    frame.extend_from_slice(&ether_type);
    frame.extend_from_slice(packet_data);
    Some(frame)
}

struct PcapRecords<'a> {
    data: &'a [u8],
    little_endian: bool,
    offset: usize,
}

impl<'a> Iterator for PcapRecords<'a> {
    type Item = &'a [u8];
    fn next(&mut self) -> Option<Self::Item> {
        let hdr = 16usize;
        if self.offset + hdr > self.data.len() {
            return None;
        }
        let get = |o: usize| -> u32 {
            let b = [self.data[self.offset + o], self.data[self.offset + o + 1], self.data[self.offset + o + 2], self.data[self.offset + o + 3]];
            if self.little_endian { u32::from_le_bytes(b) } else { u32::from_be_bytes(b) }
        };
        let incl_len = get(8) as usize;
        self.offset += hdr;
        if incl_len > self.data.len() - self.offset {
            return None;
        }
        let rec = &self.data[self.offset..self.offset + incl_len];
        self.offset += incl_len;
        Some(rec)
    }
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
    println!("  cmd {cmd_id} ({}):", json.get("name").and_then(Value::as_str).unwrap_or("?"));
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
    let mut args: Vec<String> = std::env::args().skip(1).collect();
    let Some(pcap_path) = (!args.is_empty()).then(|| PathBuf::from(args.remove(0))) else {
        bail!("usage: pcap-check <capture.pcap> [dump_cmd_id]");
    };
    let dump_cmd: Option<u16> = args.first().and_then(|s| s.parse().ok());

    let raw = fs::read(&pcap_path).with_context(|| format!("read {}", pcap_path.display()))?;
    if raw.len() < 24 {
        bail!("not a pcap file (too short)");
    }
    let magic = u32::from_be_bytes([raw[0], raw[1], raw[2], raw[3]]);
    let little_endian = match magic {
        0xa1b2c3d4 | 0xa1b23c4d => false,
        0xd4c3b2a1 | 0x4d3cb2a1 => true,
        _ => bail!("not a classic pcap file (magic {magic:#x})"),
    };
    let linktype = if little_endian {
        u32::from_le_bytes(raw[20..24].try_into()?)
    } else {
        u32::from_be_bytes(raw[20..24].try_into()?)
    };
    println!("pcap: {} bytes, linktype {linktype}", raw.len());

    let mut sniffer = GameSniffer::new().set_initial_keys(load_keys()?);

    let mut total_records = 0usize;
    let mut fed = 0usize;
    let mut total_commands = 0usize;
    let mut parse_errors: Vec<(u16, String, u16, u32)> = Vec::new();
    let mut unknown = 0usize;
    let mut got_items = false;
    let mut got_avatars = false;
    let mut got_achievements = false;
    let mut dump_seen = 0usize;

    for record in (PcapRecords { data: &raw, little_endian, offset: 24 }) {
        total_records += 1;
        let Some(frame) = prepare(record) else { continue };
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
                    let s = cmd.summary_json();
                    println!(
                        "[cmd] #{fed} id={} {} header_len={} size={} field_count={:?} err={}",
                        cmd.command_id,
                        s.get("name").and_then(Value::as_str).unwrap_or("?"),
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
                        let json = cmd.to_json().unwrap_or(Value::Null);
                        dump_body(cmd.command_id, &json);
                    }
                    got_items |= matches_item_packet(&cmd).is_some();
                    got_avatars |= matches_avatar_packet(&cmd).is_some();
                    got_achievements |= matches_achievement_packet(&cmd).is_some();
                    if s.get("parse_error") == Some(&Value::Bool(true)) {
                        parse_errors.push((
                            cmd.command_id,
                            s.get("name").and_then(Value::as_str).unwrap_or("?").to_owned(),
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

    println!("\nrecords: {total_records}, fed to sniffer: {fed}, commands decoded: {total_commands}");
    println!("data collection: items={got_items} avatars={got_avatars} achievements={got_achievements}");
    println!("parse errors: {}   unknown commands: {unknown}", parse_errors.len());
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
