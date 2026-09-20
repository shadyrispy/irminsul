package com.esc.irminsul.capture

import android.app.ActivityManager
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
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

    /** How long a session may stay blind before the game is restarted. */
    private const val BLIND_GRACE_MS = 10_000L

    /**
     * Distance between restarts. A restart costs the game a full launch — patch
     * check, login, world load — which is longer than that, so a second attempt
     * can never interrupt the first one's login.
     */
    private const val AUTO_RELOGIN_COOLDOWN_MS = 180_000L

    private const val MAX_AUTO_RELOGIN_ATTEMPTS = 2

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

    /** Bytes and connections the capture loop has accounted for. */
    val traffic: StateFlow<CaptureTraffic> = CaptureStatus.traffic

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
        /**
         * When the tunnel carries game traffic but nothing decrypts — i.e. the
         * capture joined a session already in progress — call [forceRelogin] to
         * restart the game and catch its login. Off by default: the only thing
         * that reliably produces a new handshake closes the player's game, and a
         * capture library should not do that to a host without being asked. A
         * host that wants it turns this on, or asks the player first and calls
         * [forceRelogin] itself.
         */
        val autoForceRelogin: Boolean = false,
        /**
         * Fired once per category, with only the categories that just arrived
         * set to true — a later arrival does not re-report the earlier ones.
         * Like [DataStatusSink.publish], this runs on the module's decode thread.
         */
        val onDataUpdated: (items: Boolean, characters: Boolean, achievements: Boolean) -> Unit =
            { _, _, _ -> }
    )

    private var processor: PacketProcessor? = null
    private var appContext: Context? = null
    private var config = Config()
    private var activeSource: CaptureSource? = null
    private var replayJob: Job? = null
    private var autoReloginJob: Job? = null
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
     * Whether this session can decrypt yet. Derived, never stored: no key can
     * exist until the tunnel sees the handshake's token response, so a session
     * that starts mid-game sits in [SessionPhase.AwaitingLogin] with traffic
     * flowing and nothing decoding — the state [forceRelogin] exists to end.
     */
    val sessionPhase: StateFlow<SessionPhase> = combine(
        isCapturing, packets, completion
    ) { capturing, decoded, done ->
        when {
            !capturing -> SessionPhase.Idle
            done != null -> SessionPhase.Complete
            decoded.isNotEmpty() -> SessionPhase.Collecting
            else -> SessionPhase.AwaitingLogin
        }
    }.stateIn(scope, SharingStarted.Eagerly, SessionPhase.Idle)

    /**
     * Loads the native library and creates the sniffer. Call once at startup;
     * [start] will not decode anything until this returns [CaptureResult.Ok].
     */
    fun initNative(context: Context): CaptureResult<Unit> {
        val app = context.applicationContext
        appContext = app
        // Create the heads-up channel here, once, before any permission read:
        // createNotificationChannel is asynchronous, and a channel that does not
        // exist yet cannot be judged. OEM ROMs (EMUI at minimum) also clamp the
        // requested IMPORTANCE_HIGH down to DEFAULT and lock it, so the honest
        // answer only exists after the system has settled.
        CaptureNotifier.ensureCompletionChannel(app)
        NativeLib.initLogging()
        return createSniffer()
    }

    /** Recreates the sniffer, e.g. after clearing collected data. */
    fun resetNative(): CaptureResult<Unit> = createSniffer()

    /** Releases the native sniffer. Pair with [initNative]. */
    fun close() {
        NativeLib.destroySniffer()
    }

    private fun createSniffer(): CaptureResult<Unit> {
        if (!NativeLib.isAvailable()) {
            return CaptureResult.Err(CaptureError.NativeUnavailable)
        }
        return when (val code = NativeLib.createSniffer()) {
            0 -> CaptureResult.Ok(Unit)
            else -> CaptureResult.Err(CaptureError.SnifferInitFailed(code))
        }
    }

    /** Re-reads every permission the flow can be blocked on. */
    fun refreshPermissions(context: Context): PermissionSnapshot =
        PermissionHelper.checkPermissions(context).also { _permissions.value = it }

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
     * Begins a capture session from [source], ending the previous one first:
     * the tunnel is torn down, a running pcap replay is cancelled, the decoded
     * list and drop counter are cleared, and the native per-session flags
     * restart so completion can fire again.
     *
     * Progress arrives on [packets] and through [sink]. A session that fails
     * after this call is reported on [logs] and by [isCapturing] going false.
     */
    fun start(
        context: Context,
        source: CaptureSource,
        sink: DataStatusSink,
        config: Config = Config()
    ) {
        appContext = context.applicationContext
        endActiveSession(context)
        activeSource = source
        startPipeline(sink, config)
        NativeLib.resetSession()
        when (source) {
            CaptureSource.Vpn -> {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_START)
                )
                armAutoRelogin(context)
            }
            is CaptureSource.File -> replay(source.path)
        }
    }

    /**
     * Manufactures a login, the only thing that can save a capture that joined
     * mid-session: the game's cached process is stopped and it is relaunched, so
     * its handshake runs in front of the already-running tunnel.
     *
     * Verified against a live client: neither a black-holed tunnel nor a short
     * outage does this — the client *resumes* with the key the capture never saw,
     * and no stall length is reliable (25s of silence re-logged in, 33s did not).
     * Only a new process re-logs in. See `docs/adr/0004`.
     *
     * This closes the player's game, so nothing here does it automatically:
     * [Config.autoForceRelogin] is off by default and a host calls this only
     * after asking. Closing also needs `KILL_BACKGROUND_PROCESSES`, which the
     * library deliberately does not declare for its hosts — a host that wants
     * the full restart adds it to its own manifest (normal protection level,
     * granted at install). Without it the game is only brought to the
     * foreground, which does not produce a new login. Android cannot stop a
     * foreground process at all, so a game the player is looking at stays blind.
     */
    fun forceRelogin(context: Context): CaptureResult<Unit> {
        if (!isCapturing.value) {
            return CaptureResult.Err(CaptureError.NoActiveSession)
        }
        val app = context.applicationContext
        val installed = CaptureService.targetPackages
            .mapNotNull { pkg -> app.packageManager.getLaunchIntentForPackage(pkg)?.let { pkg to it } }
        if (installed.isEmpty()) {
            return CaptureResult.Err(CaptureError.NoGameInstalled)
        }

        try {
            app.getSystemService(ActivityManager::class.java)
                ?.let { am -> installed.forEach { (pkg, _) -> am.killBackgroundProcesses(pkg) } }
        } catch (e: SecurityException) {
            Log.i(TAG, "cannot close the game: declare KILL_BACKGROUND_PROCESSES")
            _logs.tryEmit("Cannot close the game — add KILL_BACKGROUND_PROCESSES to the host manifest")
        }
        val (pkg, launch) = installed.first()
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(launch)
            _logs.tryEmit("Restarted $pkg to catch its login; expect the opening sequence")
            CaptureResult.Ok(Unit)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no launchable activity for $pkg")
            CaptureResult.Err(CaptureError.NoGameInstalled)
        } catch (e: SecurityException) {
            Log.w(TAG, "relaunch blocked for $pkg", e)
            CaptureResult.Err(CaptureError.GameRelaunchBlocked)
        }
    }

    /** Restarts a blind session's game into a login, a bounded number of times. */
    private fun armAutoRelogin(context: Context) {
        autoReloginJob?.cancel()
        if (!config.autoForceRelogin) return
        val app = context.applicationContext
        autoReloginJob = scope.launch {
            var blindSince = 0L
            var lastAttemptAt = 0L
            var attempts = 0
            combine(sessionPhase, traffic) { phase, traffic -> phase to traffic }
                .collect { (phase, traffic) ->
                    if (phase != SessionPhase.AwaitingLogin) {
                        blindSince = 0L
                        return@collect
                    }
                    // No traffic at all means the game is not talking yet; there
                    // is nothing to interrupt, so wait for it to start.
                    if (traffic.totalBytes == 0L) return@collect
                    val now = System.currentTimeMillis()
                    if (blindSince == 0L) blindSince = now
                    if (now - blindSince < BLIND_GRACE_MS) return@collect
                    if (attempts >= MAX_AUTO_RELOGIN_ATTEMPTS) return@collect
                    if (now - lastAttemptAt < AUTO_RELOGIN_COOLDOWN_MS) return@collect

                    attempts++
                    lastAttemptAt = now
                    _logs.tryEmit(
                        "No session key after ${BLIND_GRACE_MS / 1000}s of traffic — " +
                            "restarting the game to catch its login " +
                            "(attempt $attempts/$MAX_AUTO_RELOGIN_ATTEMPTS)"
                    )
                    forceRelogin(app)
                }
        }
    }

    /** Stops the current session, whether it came from VPN or a pcap replay. */
    fun stop(context: Context) {
        endActiveSession(context)
        stopPipeline()
        activeSource = null
    }

    /**
     * Tears down whatever [start] set up. Guards on the requested source rather
     * than [isCapturing], because the service only sets that once the tunnel is
     * actually up — a stop during VPN setup would otherwise be dropped.
     */
    private fun endActiveSession(context: Context) {
        replayJob?.cancel()
        replayJob = null
        autoReloginJob?.cancel()
        autoReloginJob = null
        if (activeSource is CaptureSource.Vpn || isCapturing.value) {
            context.startService(
                Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
            )
        }
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
        CaptureStatus.resetTraffic()
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

    /** Replays a pcap on a module thread; cancelled by [stop]. */
    private fun replay(path: String) {
        val worker = processor ?: return
        replayJob = scope.launch(Dispatchers.IO) {
            val packets = worker.readPcapFile(path)
            _logs.tryEmit("pcap replay finished: $packets packets from $path")
        }
    }
}
