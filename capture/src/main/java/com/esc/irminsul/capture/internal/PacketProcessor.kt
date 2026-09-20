package com.esc.irminsul.capture.internal

import android.util.Log
import com.esc.irminsul.capture.DataStatus
import com.esc.irminsul.capture.DataStatusSink

import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.BlockingQueue

/** A raw L7 packet with the wall-clock time it was seen (live) or captured (pcap). */
internal class RawPacket(val data: ByteArray, val timestampMillis: Long)

/**
 * Consumes the packet queue on its own thread: hands each packet to the native
 * decoder and publishes what comes back. Owns no state a host can write.
 */
internal class PacketProcessor(
    private val sink: DataStatusSink,
    private val packetLog: PacketRingBuffer,
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
    }

    @Volatile
    private var running = true

    /** Diagnostic raw-packet sink (pcap file), armed by the facade. */
    @Volatile
    private var dump: FileOutputStream? = null

    private val dumpLock = Any()

    /** Writes every captured packet to [path] as a pcap (raw-IP link type). */
    fun startDump(path: String) {
        synchronized(dumpLock) {
            stopDump()
            val out = FileOutputStream(path)
            val hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            hdr.putInt(0xa1b2c3d4.toInt())   // magic, microseconds
            hdr.putShort(2).putShort(4)      // version
            hdr.putInt(0)                    // thiszone
            hdr.putInt(0)                    // sigfigs
            hdr.putInt(65535)                // snaplen
            hdr.putInt(101)                  // LINKTYPE_RAW — tun packets are bare IP
            out.write(hdr.array())
            dump = out
        }
    }

    fun stopDump() {
        synchronized(dumpLock) {
            dump?.let { runCatching { it.flush(); it.close() } }
            dump = null
        }
    }

    private fun dumpPacket(data: ByteArray) {
        val out = dump ?: return
        val ts = System.currentTimeMillis()
        val rec = ByteBuffer.allocate(16 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        rec.putInt((ts / 1000).toInt())
        rec.putInt(((ts % 1000) * 1000).toInt())
        rec.putInt(data.size)
        rec.putInt(data.size)
        rec.put(data)
        synchronized(dumpLock) { runCatching { out.write(rec.array()) } }
    }

    private var notifiedItems = false
    private var notifiedCharacters = false
    private var notifiedAchievements = false

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
            dumpPacket(packet.data)
            val statusJson = NativeLib.processPacket(packet.data) ?: return
            val update = StatusDecoder.decode(statusJson, packet.timestampMillis) ?: return
            packetLog.appendAll(update.records)
            sink.publish(update.status)
            notifyNewCategories(update.status)
        } catch (e: Exception) {
            Log.w(TAG, "Error processing packet", e)
        }
    }

    /**
     * [com.esc.irminsul.capture.IrminsulCapture.Config.onDataUpdated] means
     * "this category has just arrived", so the callback carries only the
     * categories that are new — passing the current state instead made a host
     * that logs each true flag repeat itself on every later arrival. This
     * worker lives for exactly one session, so its edges are session edges.
     */
    private fun notifyNewCategories(status: DataStatus) {
        val newItems = status.itemsLoaded && !notifiedItems
        val newCharacters = status.charactersLoaded && !notifiedCharacters
        val newAchievements = status.achievementsLoaded && !notifiedAchievements
        if (!newItems && !newCharacters && !newAchievements) return
        if (newItems) notifiedItems = true
        if (newCharacters) notifiedCharacters = true
        if (newAchievements) notifiedAchievements = true
        onDataUpdate(newItems, newCharacters, newAchievements)
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

    /**
     * Feeds a saved pcap into the queue, blocking while the queue is full so a
     * complete import never drops packets. Call off the main thread; interrupting
     * the caller's thread stops the replay.
     *
     * @return how many packets were handed to the decoder.
     */
    fun readPcapFile(pcapPath: String): Int {
        var fed = 0
        try {
            FileInputStream(pcapPath).use { inputStream ->
                val header = ByteArray(PCAP_HDR_SIZE)
                val read = inputStream.read(header)
                if (read != PCAP_HDR_SIZE) return 0

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
                    fed++
                }
            }
        } catch (e: InterruptedException) {
            // A cancelled replay is a normal end of session, not an error.
            currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading PCAP file", e)
        }
        return fed
    }
}
