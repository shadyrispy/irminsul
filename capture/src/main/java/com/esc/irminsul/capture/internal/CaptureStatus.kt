package com.esc.irminsul.capture.internal

import android.util.Log
import com.esc.irminsul.capture.CaptureTraffic
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Module-owned capture state: whether a session is live, and how many packets
 * the queue has dropped. Written only by [CaptureService] (and by the facade
 * when it opens a session); read through the facade.
 *
 * Collection progress deliberately does not live here: that state arrives on
 * every [com.esc.irminsul.capture.DataStatus] publish, so mirroring it would
 * give the module a second, unsynchronised source of truth.
 */
internal object CaptureStatus {
    private const val TAG = "CaptureStatus"

    /** Log throttling: a saturated queue drops one packet per game packet. */
    private const val DROP_LOG_INTERVAL = 100

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    fun setCapturing(running: Boolean) {
        _isCapturing.value = running
        Log.d(TAG, "Capture status updated: $running")
    }

    private val _droppedPackets = MutableStateFlow(0L)
    private val dropped = AtomicLong(0)

    /**
     * A StateFlow conflates, so publishing every drop costs the writer one
     * comparison and cannot queue up work for a slow collector.
     */
    val droppedPackets: StateFlow<Long> = _droppedPackets.asStateFlow()

    fun resetDroppedPackets() {
        dropped.set(0)
        _droppedPackets.value = 0L
    }

    fun recordDroppedPacket() {
        val total = dropped.incrementAndGet()
        _droppedPackets.value = total
        if (total % DROP_LOG_INTERVAL == 1L) {
            Log.w(TAG, "Packet queue saturated, $total packets dropped so far")
        }
    }

    private val _traffic = MutableStateFlow(CaptureTraffic())

    /** Traffic the native loop accounted for; its movement is how we know the game is online. */
    val traffic: StateFlow<CaptureTraffic> = _traffic.asStateFlow()

    fun resetTraffic() {
        _traffic.value = CaptureTraffic()
    }

    /** Called from the capture thread's stats callback, at most once per second. */
    fun recordTraffic(sent: Long, received: Long, connections: Int) {
        _traffic.value = CaptureTraffic(
            sentBytes = sent,
            receivedBytes = received,
            connections = connections
        )
    }
}
