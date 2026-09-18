package com.esc.irminsul

import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.BlockingQueue
import org.json.JSONObject

/** A raw L7 packet with the wall-clock time it was seen (live) or captured (pcap). */
class RawPacket(val data: ByteArray, val timestampMillis: Long)

class PacketProcessor(
    private val dataStore: DataStatusSink,
    private val packetLog: PacketLog,
    private val packetQueue: BlockingQueue<RawPacket>,
    private val onDataUpdate: (items: Boolean, characters: Boolean, achievements: Boolean) -> Unit
) : Thread() {

    private companion object {
        private const val TAG = "PacketProcessor"
        private const val PCAP_HDR_SIZE = 24
        private const val PCAP_REC_HDR_SIZE = 16
        private const val MAX_PACKET_SIZE = 65535
        private const val PCAP_MAGIC_LITTLE_ENDIAN = 0xA1B2C3D4.toInt()
        private const val PCAP_MAGIC_NSEC_LITTLE_ENDIAN = 0xA1B23C4D.toInt()
        private const val PCAP_MAGIC_NSEC_BIG_ENDIAN = 0x4D3CB2A1.toInt()
        private val PCAP_HDR_START_BYTES = ByteBuffer.wrap(hexToBytes("d4c3b2a1020004000000000000000000"))

        private fun hexToBytes(s: String): ByteArray {
            val len = s.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(s[i], 16) shl 4)
                        + Character.digit(s[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }
    }

    @Volatile
    private var running = true

    init {
        name = "PacketProcessor"
    }

    override fun run() {
        Log.d(TAG, "PacketProcessor started")
        while (running) {
            try {
                val packet = packetQueue.take()
                processPacket(packet)
            } catch (e: InterruptedException) {
                currentThread().interrupt()
                break
            }
        }
        Log.d(TAG, "PacketProcessor stopped")
    }

    private fun processPacket(packet: RawPacket) {
        try {
            val statusJson = NativeLib.processPacket(packet.data)
            if (statusJson != null) {
                parseStatusJson(statusJson, packet.timestampMillis)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error processing packet", e)
        }
    }

    private fun parseStatusJson(json: String, timestampMillis: Long) {
        try {
            val obj = JSONObject(json)
            val hasItems = obj.optBoolean("has_items", false)
            val hasAvatars = obj.optBoolean("has_avatars", false)
            val hasAchievements = obj.optBoolean("has_achievements", false)
            val artifactCount = obj.optInt("artifact_count", 0)
            val weaponCount = obj.optInt("weapon_count", 0)
            val materialCount = obj.optInt("material_count", 0)
            val characterCount = obj.optInt("character_count", 0)
            val achievementCount = obj.optInt("achievement_count", 0)

            val packetId = obj.optLong("packet_id", -1L)
            obj.optJSONArray("commands")?.let { commands ->
                val batch = ArrayList<PacketRecord>(commands.length())
                for (i in 0 until commands.length()) {
                    val cmd = commands.optJSONObject(i) ?: continue
                    val fieldCount = if (cmd.isNull("field_count")) null else cmd.optInt("field_count")
                    val briefKeys = buildList {
                        cmd.optJSONArray("brief_keys")?.let { keys ->
                            for (j in 0 until keys.length()) add(keys.optString(j))
                        }
                    }
                    batch.add(
                        PacketRecord(
                            packetId = packetId,
                            commandIndex = i,
                            cmdId = cmd.optInt("cmd_id", 0),
                            name = cmd.optString("name", "unknown"),
                            isSent = cmd.optString("direction", "") == "sent",
                            sizeBytes = cmd.optInt("size", 0),
                            fieldCount = fieldCount,
                            briefKeys = briefKeys,
                            parseError = cmd.optBoolean("parse_error", false),
                            timestampMillis = timestampMillis
                        )
                    )
                }
                packetLog.appendAll(batch)
            }

            dataStore.updateStatus(
                itemsLoaded = hasItems,
                charactersLoaded = hasAvatars,
                achievementsLoaded = hasAchievements,
                artifactsCount = artifactCount,
                weaponsCount = weaponCount,
                materialsCount = materialCount,
                charactersCount = characterCount,
                achievementsCount = achievementCount
            )

            if (hasItems || hasAvatars || hasAchievements) {
                onDataUpdate(hasItems, hasAvatars, hasAchievements)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing status JSON", e)
        }
    }

    fun stopProcessor() {
        running = false
        interrupt()
        try {
            join()
        } catch (e: InterruptedException) {
            currentThread().interrupt()
        }
    }

    // --- PCAP file reading ---

    fun readPcapFile(pcapPath: String) {
        try {
            FileInputStream(pcapPath).use { inputStream ->
                val header = ByteArray(PCAP_HDR_SIZE)
                val read = inputStream.read(header)
                if (read != PCAP_HDR_SIZE) return

                val hdrBuf = ByteBuffer.wrap(header)
                val magic = hdrBuf.int
                var byteOrder = if (magic == PCAP_MAGIC_LITTLE_ENDIAN) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                // Nanosecond-resolution pcap variant (magic 0xA1B23C4D in either
                // byte order); the fraction field is nanos instead of micros.
                val isNanosecond = magic == PCAP_MAGIC_NSEC_LITTLE_ENDIAN ||
                        magic == PCAP_MAGIC_NSEC_BIG_ENDIAN

                while (running) {
                    val recHeader = ByteArray(PCAP_REC_HDR_SIZE)
                    val recRead = inputStream.read(recHeader)
                    if (recRead != PCAP_REC_HDR_SIZE) break

                    val recBuf = ByteBuffer.wrap(recHeader)
                    recBuf.order(byteOrder)
                    var inclLen = recBuf.getInt(8)

                    if (inclLen <= 0 || inclLen > MAX_PACKET_SIZE) {
                        recBuf.rewind()
                        recBuf.order(if (byteOrder == ByteOrder.LITTLE_ENDIAN) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
                        inclLen = recBuf.getInt(8)

                        if (inclLen <= 0 || inclLen > MAX_PACKET_SIZE) {
                            inputStream.skip(Math.abs(inclLen).toLong())
                            continue
                        }
                        byteOrder = if (byteOrder == ByteOrder.LITTLE_ENDIAN) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                    }

                    val tsSec = recBuf.getInt(0).toLong() and 0xFFFFFFFFL
                    val tsFraction = recBuf.getInt(4).toLong() and 0xFFFFFFFFL
                    val packetTimestamp = if (isNanosecond) {
                        tsSec * 1000 + tsFraction / 1_000_000
                    } else {
                        tsSec * 1000 + tsFraction / 1000
                    }

                    val packetData = ByteArray(inclLen)
                    if (inputStream.read(packetData) != inclLen) break
                    // Block until space is available instead of silently dropping
                    // packets, so a complete PCAP import never loses data.
                    packetQueue.put(RawPacket(packetData, packetTimestamp))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading PCAP file", e)
        }
    }
}
