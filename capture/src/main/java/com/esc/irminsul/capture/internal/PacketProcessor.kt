package com.esc.irminsul.capture.internal

import android.util.Log
import com.esc.irminsul.capture.DataStatus
import com.esc.irminsul.capture.DataStatusSink
import com.esc.irminsul.capture.KeyOrigin

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
            val statusJson = NativeLib.processPacket(packet.data)
            // Read every packet, not just the ones that decoded: the frames a
            // session cannot open produce no status at all, and losing the key is
            // exactly the thing worth noticing.
            CaptureStatus.recordKeyOrigin(KeyOrigin.fromWire(NativeLib.keyOrigin()))
            if (statusJson == null) return
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
     * The file itself is read natively — the decode core's reader is the only
     * thing in this stack that knows the pcap format, so the Android and desktop
     * halves cannot disagree about it.
     *
     * @return how many packets were handed to the decoder, or `null` when the file
     *   could not be opened at all. "Could not open" and "opened, held nothing" are
     *   different problems to fix, and a caller that gets `0` for both cannot tell
     *   which one it has.
     */
    fun readPcapFile(pcapPath: String): Int? {
        val handle = NativeLib.pcapOpen(pcapPath)
        if (handle < 0) return null

        val timestamp = LongArray(1)
        var fed = 0
        try {
            while (running) {
                val frame = NativeLib.pcapNext(handle, timestamp) ?: break
                // Block until space is available instead of silently dropping
                // packets, so a complete PCAP import never loses data.
                packetQueue.put(RawPacket(frame, timestamp[0]))
                fed++
            }
        } catch (e: InterruptedException) {
            // A cancelled replay is a normal end of session, not an error.
            currentThread().interrupt()
        } finally {
            NativeLib.pcapClose(handle)
        }
        return fed
    }
}
