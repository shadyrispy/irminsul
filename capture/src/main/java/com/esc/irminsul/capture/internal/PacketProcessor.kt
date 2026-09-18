package com.esc.irminsul.capture.internal

import android.util.Log
import com.esc.irminsul.capture.DataStatusSink

import java.io.FileInputStream
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
    private val dataStore: DataStatusSink,
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
            val statusJson = NativeLib.processPacket(packet.data) ?: return
            val update = StatusDecoder.decode(statusJson, packet.timestampMillis) ?: return
            packetLog.appendAll(update.records)
            dataStore.publish(update.status)
            with(update.status) {
                if (itemsLoaded || charactersLoaded || achievementsLoaded) {
                    onDataUpdate(itemsLoaded, charactersLoaded, achievementsLoaded)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error processing packet", e)
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

    /**
     * Feeds a saved pcap into the queue, blocking while the queue is full so a
     * complete import never drops packets. Call off the main thread.
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
        } catch (e: Exception) {
            Log.e(TAG, "Error reading PCAP file", e)
        }
        return fed
    }
}
