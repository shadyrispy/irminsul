package com.esc.irminsul.capture

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.esc.irminsul.capture.internal.CaptureNotifier
import com.esc.irminsul.capture.internal.CaptureService
import com.esc.irminsul.capture.internal.CaptureStatus
import com.esc.irminsul.capture.internal.NativeLib
import com.esc.irminsul.capture.internal.PacketProcessor
import com.esc.irminsul.capture.internal.PacketRingBuffer
import com.esc.irminsul.capture.internal.PermissionHelper
import com.esc.irminsul.capture.internal.RawPacket
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The capture module's interface: everything a host needs to turn a device's
 * game traffic into decoded commands.
 *
 * A host crosses exactly one seam — this object plus [DataStatus],
 * [DataStatusSink], [PacketRecord], [CaptureSource], [CaptureResult],
 * [PermissionSnapshot] and [Completion]. Everything else in the module is
 * `internal` and lives in `…capture.internal`.
 *
 * Typical flow: [initNative] once at startup, [refreshPermissions] /
 * [openFixSettings] until [PermissionKind.Vpn] is granted, then
 * `start(context, CaptureSource.Vpn, sink)`.
 *
 * There is one capture at a time: the native sniffer is stateful (session keys,
 * stream reassembly) and process-wide, so this is an object rather than a
 * constructible session. Calling [start] while a session runs ends the previous
 * one first.
 */
object IrminsulCapture {

    private const val TAG = "IrminsulCapture"

    private val ring = PacketRingBuffer()

    /** Decoded commands, newest last. Read-only: the module is the only writer. */
    val packets: StateFlow<List<PacketRecord>> = ring.records

    /** Whether the VPN capture service is running. Only the module writes it. */
    val isCapturing: StateFlow<Boolean>
        get() = CaptureStatus.isCapturing

    /**
     * Packets dropped because [Config.queueCapacity] filled up since the
     * session started. Live capture never blocks the capture thread, so a
     * decoder that falls behind shows up here rather than silently.
     */
    val droppedPackets: StateFlow<Long>
        get() = CaptureStatus.droppedPackets

    /** Diagnostic lines forwarded from the native stack. No replay. */
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    /** Set once, when the native stack first reports all game data collected. */
    private val _completion = MutableStateFlow<Completion?>(null)
    val completion: StateFlow<Completion?> = _completion.asStateFlow()

    /** Latest [refreshPermissions] result. */
    private val _permissions = MutableStateFlow(PermissionSnapshot())
    val permissions: StateFlow<PermissionSnapshot> = _permissions.asStateFlow()

    data class Config(
        /**
         * Pending packets. Live capture drops what does not fit (see
         * [droppedPackets]); a [CaptureSource.File] replay blocks instead, so an
         * import never loses data.
         */
        val queueCapacity: Int = 10_000,
        /** Post the "all data collected" notification when the native stack reports it. */
        val completionNotification: Boolean = true,
        /** Fired whenever items / characters / achievements first show up. */
        val onDataUpdated: (items: Boolean, characters: Boolean, achievements: Boolean) -> Unit =
            { _, _, _ -> }
    )

    private var processor: PacketProcessor? = null
    private var appContext: Context? = null
    private var config = Config()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        NativeLib.setLogCallback(object : NativeLib.LogCallback {
            override fun onLog(message: String) {
                _logs.tryEmit(message)
            }
        })
        NativeLib.setDataCompleteCallback(object : NativeLib.DataCompleteCallback {
            override fun onDataComplete(
                artifactCount: Int,
                weaponCount: Int,
                materialCount: Int,
                characterCount: Int,
                achievementCount: Int
            ) {
                _completion.value = Completion(
                    charactersCount = characterCount,
                    artifactsCount = artifactCount,
                    weaponsCount = weaponCount,
                    achievementsCount = achievementCount,
                    materialsCount = materialCount
                )
                if (config.completionNotification) {
                    appContext?.let {
                        CaptureNotifier.showCompletion(
                            it, characterCount, artifactCount, weaponCount, achievementCount
                        )
                    }
                }
            }
        })
    }

    /**
     * Loads the native library and creates the sniffer. Call once at startup;
     * [start] will not decode anything until this returns [CaptureResult.Ok].
     */
    fun initNative(context: Context): CaptureResult<Unit> {
        appContext = context.applicationContext
        NativeLib.initLogging()
        if (!NativeLib.isAvailable()) {
            return CaptureResult.Err(CaptureError.NativeUnavailable)
        }
        return createSniffer()
    }

    /** Recreates the sniffer, e.g. after clearing collected data. */
    fun resetNative(): CaptureResult<Unit> = createSniffer()

    /** Releases the native sniffer. Pair with [initNative]. */
    fun close() {
        NativeLib.destroySniffer()
    }

    private fun createSniffer(): CaptureResult<Unit> =
        when (val code = NativeLib.createSniffer()) {
            0 -> CaptureResult.Ok(Unit)
            else -> CaptureResult.Err(CaptureError.SnifferInitFailed(code))
        }

    /** Re-reads every permission the flow can be blocked on. */
    fun refreshPermissions(context: Context): PermissionSnapshot {
        val state = PermissionHelper.checkPermissions(context)
        val snapshot = PermissionSnapshot(
            notificationGranted = state.notificationGranted,
            headsUpEnabled = state.headsUpEnabled,
            vpnPermissionGranted = state.vpnPermissionGranted,
            batteryOptimizationExempt = state.batteryOptimizationExempt,
            needsAutoStart = state.needsAutoStart,
            romHint = PermissionHelper.romHint()
        )
        _permissions.value = snapshot
        return snapshot
    }

    /**
     * Opens the best settings page that can grant [kind], falling back through
     * ROM-specific and stock-Android pages.
     */
    fun openFixSettings(context: Context, kind: PermissionKind): CaptureResult<Unit> {
        for (intent in PermissionHelper.fixIntents(context, kind)) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return CaptureResult.Ok(Unit)
            } catch (e: ActivityNotFoundException) {
                Log.d(TAG, "settings page unavailable for $kind: ${intent.component ?: intent.action}")
            } catch (e: SecurityException) {
                Log.d(TAG, "settings page blocked for $kind: ${intent.component ?: intent.action}")
            }
        }
        Log.w(TAG, "no settings page could be opened for $kind")
        return CaptureResult.Err(CaptureError.NoSettingsPage)
    }

    /** The VPN consent intent to launch, or null when already granted. */
    fun vpnConsentIntent(context: Context): Intent? =
        PermissionHelper.getVpnPermissionIntent(context)

    /**
     * Begins a capture session from [source] and ends the previous one. Live
     * progress arrives on [packets] and through [sink]; a session that fails
     * after this point is reported on [logs] and by [isCapturing] going false.
     */
    fun start(
        context: Context,
        source: CaptureSource,
        sink: DataStatusSink,
        config: Config = Config()
    ) {
        appContext = context.applicationContext
        startPipeline(sink, config)
        when (source) {
            CaptureSource.Vpn -> ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_START)
            )
            is CaptureSource.File -> replay(source.path)
        }
    }

    /** Stops the current session, whether it came from VPN or a pcap replay. */
    fun stop(context: Context) {
        if (isCapturing.value) {
            context.startService(
                Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
            )
        }
        stopPipeline()
    }

    /** Gives up a start that never got consent; the only non-service state write. */
    fun abortStart() {
        CaptureStatus.setCapturing(false)
    }

    /** Drops the decoded-command list, e.g. after the host resets collected data. */
    fun clearPackets() {
        ring.clear()
    }

    /** GOOD v3 export of the collected data. `settingsJson` is the module's JSON schema. */
    fun exportGood(settingsJson: String): CaptureResult<String> =
        nativeCall { NativeLib.exportGood(settingsJson) }

    /** Achievement export as defined by the native export format codes. */
    fun exportAchievements(format: Int): CaptureResult<String> =
        nativeCall { NativeLib.exportAchievements(format) }

    /** Full proto body JSON for one decoded command. */
    fun commandBody(packetId: Long, commandIndex: Int): CaptureResult<String> {
        if (!NativeLib.isAvailable()) {
            return CaptureResult.Err(CaptureError.NativeUnavailable)
        }
        val body = NativeLib.commandBody(packetId, commandIndex)
            ?: return CaptureResult.Err(CaptureError.PayloadUnavailable)
        return CaptureResult.Ok(body)
    }

    /** Posts the completion notification with sample counts, for testing heads-up. */
    fun showCompletionPreview(context: Context) {
        CaptureNotifier.showCompletion(context, 1, 2, 3, 4)
        scope.launch {
            delay(5_000)
            CaptureNotifier.cancelCompletion(context)
        }
    }

    private fun nativeCall(call: () -> String?): CaptureResult<String> = when {
        !NativeLib.isAvailable() -> CaptureResult.Err(CaptureError.NativeUnavailable)
        else -> call()?.let { CaptureResult.Ok(it) }
            ?: CaptureResult.Err(CaptureError.NativeRefused)
    }

    @Synchronized
    private fun startPipeline(sink: DataStatusSink, config: Config) {
        stopPipeline()
        this.config = config
        _completion.value = null
        ring.clear()
        CaptureStatus.resetDroppedPackets()
        val queue = LinkedBlockingQueue<RawPacket>(config.queueCapacity)
        val worker = PacketProcessor(sink, ring, queue, config.onDataUpdated)
        processor = worker
        CaptureService.setPacketQueue(queue)
        worker.start()
    }

    @Synchronized
    private fun stopPipeline() {
        processor?.stopProcessor()
        processor = null
        CaptureService.setPacketQueue(null)
    }

    /** Replays a pcap on a module thread; the queue fills faster than it drains. */
    private fun replay(path: String) {
        val worker = processor ?: return
        scope.launch(Dispatchers.IO) {
            val packets = worker.readPcapFile(path)
            _logs.tryEmit("pcap replay finished: $packets packets from $path")
        }
    }
}
