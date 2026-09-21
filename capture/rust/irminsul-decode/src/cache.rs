//! Bounded store of decoded commands, so a full body can be serialized on demand.
//!
//! The sniffer is stateful — session keys, stream reassembly — so a packet cannot
//! be re-decoded later. That means the only way to keep a packet list cheap to
//! produce, and a command body still available when someone taps it, is to cache
//! the raw decoded commands and defer serialization until they are asked for.

use std::collections::VecDeque;

use auto_artifactarium::GameCommand;

/// How many command-body bytes to hold before the oldest packet is evicted.
/// Sized for a full collection on a phone: thousands of commands, the largest of
/// them over 100KB.
pub const MAX_BYTES: usize = 64 * 1024 * 1024;

struct CachedPacket {
    id: u64,
    commands: Vec<GameCommand>,
}

fn body_bytes(commands: &[GameCommand]) -> usize {
    commands.iter().map(|c| c.proto_data.len()).sum()
}

pub struct DecodedCache {
    packets: VecDeque<CachedPacket>,
    bytes: usize,
    next_id: u64,
}

impl Default for DecodedCache {
    fn default() -> Self {
        Self::new()
    }
}

impl DecodedCache {
    pub fn new() -> Self {
        Self {
            packets: VecDeque::new(),
            bytes: 0,
            next_id: 0,
        }
    }

    /// Store a packet's commands and hand back the id that addresses them.
    /// Ids keep counting up across [`clear`](Self::clear), so a front end still
    /// holding an old id gets a miss rather than someone else's packet.
    pub fn push(&mut self, commands: Vec<GameCommand>) -> u64 {
        let id = self.next_id;
        self.next_id += 1;
        self.bytes += body_bytes(&commands);
        self.packets.push_back(CachedPacket { id, commands });
        while self.bytes > MAX_BYTES {
            let Some(evicted) = self.packets.pop_front() else {
                break;
            };
            self.bytes -= body_bytes(&evicted.commands);
        }
        id
    }

    /// The cached command at `index` of `id`, or `None` once it has been evicted.
    pub fn command(&self, id: u64, index: usize) -> Option<&GameCommand> {
        self.packets
            .iter()
            .find(|packet| packet.id == id)
            .and_then(|packet| packet.commands.get(index))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use auto_artifactarium::PacketDirection;

    fn command(body: &[u8]) -> GameCommand {
        GameCommand {
            command_id: 21967,
            header_len: 0,
            data_len: body.len() as u32,
            ext_header: vec![],
            proto_data: body.to_vec(),
            direction: PacketDirection::Received,
        }
    }

    #[test]
    fn a_body_comes_back_by_the_id_push_returned() {
        let mut cache = DecodedCache::new();
        let id = cache.push(vec![command(b"alpha"), command(b"beta")]);

        assert_eq!(cache.command(id, 0).unwrap().proto_data, b"alpha");
        assert_eq!(cache.command(id, 1).unwrap().proto_data, b"beta");
        assert!(cache.command(id, 2).is_none());
        assert!(cache.command(id + 99, 0).is_none());
    }

    #[test]
    fn the_oldest_packet_is_evicted_past_the_budget() {
        let mut cache = DecodedCache::new();
        let first = cache.push(vec![command(&vec![7u8; MAX_BYTES])]);
        let second = cache.push(vec![command(b"small")]);

        assert!(cache.command(second, 0).is_some(), "the newest packet stays");
        assert!(
            cache.command(first, 0).is_none(),
            "the oldest goes once past the budget"
        );

        // Only the over-budget oldest goes at a time, so a third small packet
        // must not take the second one with it.
        let third = cache.push(vec![command(b"tiny")]);
        assert!(cache.command(second, 0).is_some());
        assert!(cache.command(third, 0).is_some());
    }

    #[test]
    fn ids_are_never_handed_out_twice() {
        let mut cache = DecodedCache::new();
        let first = cache.push(vec![command(b"one")]);
        let second = cache.push(vec![command(b"two")]);

        assert_ne!(first, second);
        assert_eq!(cache.command(first, 0).unwrap().proto_data, b"one");
        assert_eq!(cache.command(second, 0).unwrap().proto_data, b"two");
    }
}
