package com.esc.irminsul.capture

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.esc.irminsul.capture.internal.CaptureService
import com.esc.irminsul.capture.internal.CaptureStatus
import com.esc.irminsul.capture.internal.NativeLib
import com.esc.irminsul.capture.internal.PacketProcessor
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
 * [DataStatusSink], [PacketRecord] and [PacketLog]. Everything else in the
 * module is `internal` and lives in `…capture.internal`.
 *
 * Typical flow: [initNative] once at startup, [refreshPermissions] /
 * [openFixSettings] until [PermissionKind.Vpn] is granted, then [start].
 */
object IrminsulCapture {

    private const val TAG = "IrminsulCapture"

    /** Decoded commands, newest last. Shared ring buffer, survives [stop]. */
    val packets: PacketLog = PacketLog()

    /** Whether the VPN capture service is running. Only the module writes it. */
    val isCapturing: StateFlow<Boolean>
        get() = CaptureStatus.isCapturing

    /** Diagnostic lines forwarded from the native stack. No replay. */
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    /** Set once the native stack reports all game data collected; cleared by [start]. */
    private val _completion = MutableStateFlow<Completion?>(null)
    val completion: StateFlow<Completion?> = _completion.asStateFlow()

    /** Latest [refreshPermissions] result. */
    private val _permissions = MutableStateFlow(PermissionSnapshot())
    val permissions: StateFlow<PermissionSnapshot> = _permissions.asStateFlow()

    data class Config(
        val queueCapacity: Int = 10_000,
        /** Post the "all data collected" notification when the native stack reports it. */
        val completionNotification: Boolean = true,
        /** Fired whenever items / avatars / achievements first show up. */
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
                        CaptureService.showCompletionNotification(
                            it, characterCount, artifactCount, weaponCount, achievementCount
                        )
                    }
                }
            }
        })
    }

    /**
     * Loads the native library and creates the sniffer. Call once at startup;
     * [start] will not decode anything until this reports [InitResult.Ready].
     */
    fun initNative(context: Context): InitResult {
        appContext = context.applicationContext
        NativeLib.initLogging()
        if (!NativeLib.isAvailable()) return InitResult.NotInstalled
        return createSniffer()
    }

    /** Recreates the sniffer, e.g. after clearing collected data. */
    fun resetNative(): InitResult = createSniffer()

    /** Releases the native sniffer. Pair with [initNative]. */
    fun close() {
        NativeLib.destroySniffer()
    }

    private fun createSniffer(): InitResult = when (val code = NativeLib.createSniffer()) {
        0 -> InitResult.Ready
        else -> InitResult.Failed(code)
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
     * ROM-specific and stock-Android pages. Returns false when none could open.
     */
    fun openFixSettings(context: Context, kind: PermissionKind): Boolean {
        for (intent in PermissionHelper.fixIntents(context, kind)) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.d(TAG, "settings page unavailable for $kind: ${intent.component ?: intent.action}")
            } catch (e: SecurityException) {
                Log.d(TAG, "settings page blocked for $kind: ${intent.component ?: intent.action}")
            }
        }
        Log.w(TAG, "no settings page could be opened for $kind")
        return false
    }

    /** The VPN consent intent to launch, or null when already granted. */
    fun vpnConsentIntent(context: Context): Intent? =
        PermissionHelper.getVpnPermissionIntent(context)

    /** Restarts the decode pipeline with a fresh queue and an empty log. */
    @Synchronized
    fun startPipeline(sink: DataStatusSink, config: Config = Config()) {
        stopPipeline()
        this.config = config
        _completion.value = null
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

    /** Starts capturing. Call once VPN consent from [vpnConsentIntent] is granted. */
    fun start(context: Context, sink: DataStatusSink, config: Config = Config()) {
        appContext = context.applicationContext
        CaptureStatus.resetParsingProgress()
        startPipeline(sink, config)
        ContextCompat.startForegroundService(
            context,
            Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_START)
        )
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
        )
        stopPipeline()
    }

    /** Gives up a start that never got consent; the only host-side state write. */
    fun abortStart() {
        CaptureStatus.updateCapturingStatus(false)
    }

    /** GOOD v3 export of the collected data. `settingsJson` is the module's JSON schema. */
    fun exportGood(settingsJson: String): String? = NativeLib.exportGood(settingsJson)

    /** Achievement export as defined by the native export format codes. */
    fun exportAchievements(format: Int): String? = NativeLib.exportAchievements(format)

    /** Full proto body JSON for one decoded command, or null if it is gone. */
    fun commandBody(packetId: Long, commandIndex: Int): String? =
        NativeLib.commandBody(packetId, commandIndex)

    /** Posts the completion notification with sample counts, for testing heads-up. */
    fun showCompletionPreview(context: Context) {
        CaptureService.showCompletionNotification(context, 1, 2, 3, 4)
        scope.launch {
            delay(5_000)
            CaptureService.cancelCompletionNotification(context)
        }
    }
}
