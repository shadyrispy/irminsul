//! The status payload a front end reads per packet.
//!
//! These JSON keys are a contract with `StatusDecoder` in the Kotlin half of the
//! capture module and with whatever a viewer draws.
//! `capture/testdata/summary_status.json` is the single fixture both halves test
//! against, so a rename on either side fails a test instead of silently reading
//! back as zero.

pub struct StatusPayload<'a> {
    pub packet_id: u64,
    pub has_items: bool,
    pub has_avatars: bool,
    pub has_achievements: bool,
    pub artifact_count: usize,
    pub weapon_count: usize,
    pub material_count: usize,
    pub character_count: usize,
    pub achievement_count: usize,
    pub commands: &'a [serde_json::Value],
}

pub fn status_json(payload: &StatusPayload) -> serde_json::Value {
    serde_json::json!({
        "packet_id": payload.packet_id,
        "has_items": payload.has_items,
        "has_avatars": payload.has_avatars,
        "has_achievements": payload.has_achievements,
        "artifact_count": payload.artifact_count,
        "weapon_count": payload.weapon_count,
        "material_count": payload.material_count,
        "character_count": payload.character_count,
        "achievement_count": payload.achievement_count,
        "commands": payload.commands,
    })
}

#[cfg(test)]
mod contract_tests {
    use super::*;
    use auto_artifactarium::{GameCommand, PacketDirection};

    /// Shared with the Kotlin `StatusDecoderTest`: whoever changes the payload
    /// must update this one file, and both halves then re-verify against it.
    const FIXTURE: &str = include_str!("../../../testdata/summary_status.json");

    fn sample_payload<'a>(commands: &'a [serde_json::Value]) -> StatusPayload<'a> {
        StatusPayload {
            packet_id: 7,
            has_items: true,
            has_avatars: true,
            has_achievements: false,
            artifact_count: 1200,
            weapon_count: 150,
            material_count: 2307,
            character_count: 90,
            achievement_count: 1712,
            commands,
        }
    }

    fn keys(value: &serde_json::Value) -> Vec<String> {
        value
            .as_object()
            .unwrap_or_else(|| panic!("expected a JSON object, got {value}"))
            .keys()
            .cloned()
            .collect()
    }

    fn sorted(mut values: Vec<String>) -> Vec<String> {
        values.sort();
        values
    }

    fn fixture() -> serde_json::Value {
        serde_json::from_str(FIXTURE).expect("fixture parses")
    }

    #[test]
    fn status_json_emits_exactly_the_fixture_keys() {
        let produced = status_json(&sample_payload(&[]));
        assert_eq!(
            sorted(keys(&produced)),
            sorted(keys(&fixture())),
            "top-level payload keys drifted from capture/testdata/summary_status.json"
        );
    }

    #[test]
    fn command_summary_has_the_fixture_command_keys() {
        // An unresolvable command id takes the producer's early-return path, so
        // its key set is the stable base the decoder relies on.
        let command = GameCommand {
            command_id: 0,
            header_len: 10,
            data_len: 0,
            ext_header: vec![],
            proto_data: vec![],
            direction: PacketDirection::Received,
        };
        assert_eq!(
            sorted(keys(&command.summary_json())),
            sorted(keys(&fixture()["commands"][0])),
            "command summary keys drifted from the fixture"
        );
    }

    #[test]
    fn fixture_values_have_the_types_the_decoder_reads() {
        let fixture = fixture();
        assert!(fixture["packet_id"].is_number());
        for flag in ["has_items", "has_avatars", "has_achievements"] {
            assert!(fixture[flag].is_boolean(), "{flag} must be a bool");
        }
        for count in [
            "artifact_count",
            "weapon_count",
            "material_count",
            "character_count",
            "achievement_count",
        ] {
            assert!(fixture[count].is_number(), "{count} must be a number");
        }
        let command = &fixture["commands"][0];
        assert!(command["cmd_id"].is_number());
        assert!(command["size"].is_number());
        assert!(command["brief_keys"].is_array());
        assert_eq!(command["direction"], "received");
    }
}
