package com.esc.irminsul.capture

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.esc.irminsul.CaptureService
import com.esc.irminsul.CaptureStatus
import com.esc.irminsul.DataStatusSink
import com.esc.irminsul.NativeLib
import com.esc.irminsul.PacketLog
import com.esc.irminsul.PacketProcessor
import com.esc.irminsul.PermissionHelper
import com.esc.irminsul.RawPacket
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.flow.StateFlow

/**
 * Single entry point for hosts that just want "capture → decoded packets".
 *
 * Owns the decode pipeline (packet queue, processor thread, command ring
 * buffer) and the VPN foreground service; the host supplies a
 * [DataStatusSink] to receive collection progress. Typical host flow:
 * `initNative()` at startup, request [vpnPermissionIntent], then [start].
 */
object IrminsulCapture {

    /** Decoded commands, newest last. Shared ring buffer, survives stop. */
    val packets: PacketLog = PacketLog()

    /** Whether the VPN capture service is running. */
    val isCapturing: StateFlow<Boolean>
        get() = CaptureStatus.isCapturing

    data class Config(
        val queueCapacity: Int = 10_000,
        /** Fired when items/avatars/achievement data first show up. */
        val onDataUpdated: (items: Boolean, characters: Boolean, achievements: Boolean) -> Unit =
            { _, _, _ -> }
    )

    private var processor: PacketProcessor? = null

    /** @return 0 when the native sniffer is ready, negative otherwise. */
    fun initNative(): Int {
        NativeLib.initLogging()
        return NativeLib.createSniffer()
    }

    /** Recreates the sniffer, e.g. after clearing collected data. */
    fun resetNative(): Int {
        NativeLib.destroySniffer()
        return NativeLib.createSniffer()
    }

    fun hasVpnPermission(context: Context): Boolean =
        PermissionHelper.isVpnPermissionGranted(context)

    /** Null when consent was already granted, otherwise the system intent. */
    fun vpnPermissionIntent(context: Context): Intent? =
        PermissionHelper.getVpnPermissionIntent(context)

    /** Restarts the decode pipeline with a fresh queue and an empty log. */
    @Synchronized
    fun startPipeline(sink: DataStatusSink, config: Config = Config()) {
        stopPipeline()
        packets.clear()
        val queue = LinkedBlockingQueue<RawPacket>(config.queueCapacity)
        val worker = PacketProcessor(sink, packets, queue, config.onDataUpdated)
        processor = worker
        CaptureService.setPacketQueue(queue)
        worker.start()
    }

    @Synchronized
    fun stopPipeline() {
        processor?.stopProcessor()
        processor = null
        CaptureService.setPacketQueue(null)
    }

    /** Feeds a saved pcap through the current pipeline; needs [startPipeline]. */
    fun importPcap(path: String) {
        processor?.readPcapFile(path)
    }

    /** Starts capturing; call once VPN consent from [vpnPermissionIntent] is granted. */
    fun start(context: Context, sink: DataStatusSink, config: Config = Config()) {
        CaptureStatus.resetParsingProgress()
        startPipeline(sink, config)
        ContextCompat.startForegroundService(
            context,
            Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_START)
        )
        CaptureStatus.updateCapturingStatus(true)
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
        )
        stopPipeline()
        CaptureStatus.updateCapturingStatus(false)
    }
}
