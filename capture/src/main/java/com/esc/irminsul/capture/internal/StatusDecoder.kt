package com.esc.irminsul.capture.internal

import com.esc.irminsul.capture.DataStatus
import com.esc.irminsul.capture.PacketRecord
import org.json.JSONObject

/** What one captured packet reported: collection progress, plus its commands. */
internal data class PacketUpdate(
    val status: DataStatus,
    val records: List<PacketRecord>
)

/**
 * Decodes the status JSON produced by the native `status_json` into Kotlin
 * types. Pure by design: it is the only place in the module that knows the
 * payload's key names, and `capture/testdata/summary_status.json` pins both
 * ends of that contract to one fixture. Malformed input yields null.
 */
internal object StatusDecoder {

    fun decode(json: String, timestampMillis: Long): PacketUpdate? {
        val obj = try {
            JSONObject(json)
        } catch (e: org.json.JSONException) {
            return null
        }
        val packetId = obj.optLong("packet_id", -1L)
        return PacketUpdate(
            status = statusOf(obj),
            records = commandsOf(obj, packetId, timestampMillis)
        )
    }

    private fun statusOf(obj: JSONObject): DataStatus {
        // Weapons are only reported once, inside the same store notify as items,
        // so an item-backed packet means both arrived.
        val hasItems = obj.optBoolean("has_items", false)
        val hasAvatars = obj.optBoolean("has_avatars", false)
        val hasAchievements = obj.optBoolean("has_achievements", false)
        return DataStatus(
            itemsLoaded = hasItems,
            charactersLoaded = hasAvatars,
            weaponsLoaded = hasItems,
            achievementsLoaded = hasAchievements,
            artifactsCount = obj.optInt("artifact_count", 0),
            weaponsCount = obj.optInt("weapon_count", 0),
            materialsCount = obj.optInt("material_count", 0),
            charactersCount = obj.optInt("character_count", 0),
            achievementsCount = obj.optInt("achievement_count", 0)
        )
    }

    private fun commandsOf(obj: JSONObject, packetId: Long, timestampMillis: Long): List<PacketRecord> {
        val commands = obj.optJSONArray("commands") ?: return emptyList()
        return ArrayList<PacketRecord>(commands.length()).apply {
            for (i in 0 until commands.length()) {
                val cmd = commands.optJSONObject(i) ?: continue
                add(
                    PacketRecord(
                        packetId = packetId,
                        commandIndex = i,
                        cmdId = cmd.optInt("cmd_id", 0),
                        name = cmd.optString("name", "unknown"),
                        isSent = cmd.optString("direction", "") == "sent",
                        sizeBytes = cmd.optInt("size", 0),
                        fieldCount = if (cmd.isNull("field_count")) null else cmd.optInt("field_count"),
                        briefKeys = stringList(cmd.optJSONArray("brief_keys")),
                        parseError = cmd.optBoolean("parse_error", false),
                        timestampMillis = timestampMillis
                    )
                )
            }
        }
    }

    private fun stringList(array: org.json.JSONArray?): List<String> {
        if (array == null) return emptyList()
        return ArrayList<String>(array.length()).apply {
            for (i in 0 until array.length()) add(array.optString(i))
        }
    }
}
