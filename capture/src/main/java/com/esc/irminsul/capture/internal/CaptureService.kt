package com.esc.irminsul.capture.internal

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Module-owned capture state: whether a session is live, and how many packets
 * the queue had to drop. Written only here and by the facade's
 * [com.esc.irminsul.capture.IrminsulCapture.abortStart]; read through the
 * facade.
 *
 * Collection progress deliberately does not live here: that state arrives on
 * every [com.esc.irminsul.capture.DataStatus] publish, so mirroring it would
 * give the module a second, unsynchronised source of truth.
 */
internal object CaptureStatus {
    private const val TAG = "CaptureStatus"

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    fun setCapturing(running: Boolean) {
        _isCapturing.value = running
        Log.d(TAG, "Capture status updated: $running")
    }

    private val dropped = AtomicLong(0)
    private val _droppedPackets = MutableStateFlow(0L)
    val droppedPackets: StateFlow<Long> = _droppedPackets.asStateFlow()

    fun resetDroppedPackets() {
        dropped.set(0)
        _droppedPackets.value = 0L
    }

    /**
     * Publishes in bursts: a saturated queue drops one packet per game packet,
     * and emitting each one would redraw every collector watching.
     */
    fun recordDroppedPacket() {
        val total = dropped.incrementAndGet()
        if (total == 1L || total % 100L == 0L) {
            _droppedPackets.value = total
            Log.w(TAG, "Packet queue saturated, $total packets dropped so far")
        }
    }
}

/**
 * Instantiated by the framework from the manifest, and its
 * `onPacketCaptured` / `onCaptureStats` / `protectSocket` members are looked up
 * by name from libcapture, so this class and those members cannot be `internal`
 * (Kotlin mangles internal member names, which would break the lookup).
 *
 * Its job is the tunnel and the packet hand-off. Notifications live in
 * [CaptureNotifier] and network discovery in [NetworkProbe].
 */
class CaptureService : VpnService() {

    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_START = "com.esc.irminsul.START_CAPTURE"
        const val ACTION_STOP = "com.esc.irminsul.STOP_CAPTURE"

        private const val VPN_MTU = 1500
        private const val VPN_IP4_ADDRESS = "10.215.173.1"
        private const val VPN_IP4_PREFIX = 30
        private const val VPN_DNS_SERVER = "10.215.173.2"
        private const val VPN_IP6_ADDRESS = "fd00:2:fd00:1:fd00:1:fd00:1"
        private const val VPN_IP6_PREFIX = 128
        private const val VPN_IP6_DNS_SERVER = "fd00:2:fd00:1:fd00:1:fd00:2"

        private const val NOTIFICATION_ID_CAPTURE = 1

        private val TARGET_PACKAGES = listOf(
            "com.miHoYo.GenshinImpact",
            "com.miHoYo.Yuanshen",
            "com.miHoYo.ys.bilibili"
        )

        @Volatile
        private var _packetQueue: LinkedBlockingQueue<RawPacket>? = null

        @Synchronized
        internal fun setPacketQueue(queue: LinkedBlockingQueue<RawPacket>?) {
            _packetQueue = queue
        }

        /**
         * Hands one captured packet to the decode pipeline. Non-blocking by
         * necessity — this runs on the native capture thread — so a saturated
         * queue drops, which [CaptureStatus] counts.
         */
        @Synchronized
        internal fun offerPacket(packetData: ByteArray) {
            val queued = _packetQueue?.offer(RawPacket(packetData, System.currentTimeMillis()))
            if (queued == false) CaptureStatus.recordDroppedPacket()
        }

        internal val packetQueue: LinkedBlockingQueue<RawPacket>?
            @Synchronized
            get() = _packetQueue
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureWorker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "irminsul-capture-worker").apply { isDaemon = true }
    }

    @Volatile
    private var bytesSent: Long = 0

    @Volatile
    private var bytesReceived: Long = 0

    @Volatile
    private var numConnections: Int = 0

    private external fun nativeRunPacketLoop(tunfd: Int)
    private external fun nativeStopCapture()
    private external fun nativeSetDnsServer(dnsIp: String, dnsPort: Int, ipver: Int)

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("capture")
        Log.d(TAG, "CaptureService created, native library loaded")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> stopCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (CaptureStatus.isCapturing.value) {
            Log.w(TAG, "Capture already running")
            return
        }

        CaptureNotifier.ensureCaptureChannel(this)
        startForeground(
            NOTIFICATION_ID_CAPTURE,
            CaptureNotifier.buildCaptureNotification(this, 0, 0, 0)
        )

        val realDnsV4 = NetworkProbe.dnsServerV4(this)
        val realDnsV6 = NetworkProbe.dnsServerV6(this)
        val hasIPv6 = NetworkProbe.hasIPv6(this)
        Log.d(
            TAG,
            "=== VPN startup: ${NetworkProbe.networkType(this)}, " +
                "dns v4=$realDnsV4 v6=${realDnsV6 ?: "none"}, " +
                "ipv6=$hasIPv6, privateDns=${NetworkProbe.privateDnsMode(this)}, mtu=$VPN_MTU"
        )

        val builder = Builder()
        builder.setMtu(VPN_MTU)
        builder.addAddress(VPN_IP4_ADDRESS, VPN_IP4_PREFIX)
        builder.addRoute("0.0.0.0", 1)
        builder.addRoute("128.0.0.0", 1)
        builder.addDnsServer(VPN_DNS_SERVER)

        if (hasIPv6) {
            builder.addAddress(VPN_IP6_ADDRESS, VPN_IP6_PREFIX)
            builder.addRoute("2000::", 3)
            builder.addRoute("fc00::", 7)
            builder.addDnsServer(VPN_IP6_DNS_SERVER)
        } else {
            Log.d(TAG, "No IPv6 connectivity, skipping IPv6 VPN configuration")
        }

        for (pkg in TARGET_PACKAGES) {
            try {
                builder.addAllowedApplication(pkg)
                Log.d(TAG, "Added allowed application: $pkg")
            } catch (e: Exception) {
                Log.w(TAG, "Package not found, skipping: $pkg")
            }
        }

        builder.setSession("Irminsul")
        builder.setConfigureIntent(CaptureNotifier.hostLaunchIntent(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf()
            return
        }

        CaptureStatus.setCapturing(true)
        Log.d(TAG, "VPN interface established, starting capture")

        val tunfd = vpnInterface!!.fd
        captureWorker.execute {
            nativeSetDnsServer(realDnsV4, 53, 4)
            if (hasIPv6 && realDnsV6 != null) {
                nativeSetDnsServer(realDnsV6, 53, 6)
            }
            nativeRunPacketLoop(tunfd)
        }
    }

    fun stopCapture() {
        if (!CaptureStatus.isCapturing.value) return

        Log.d(TAG, "Stopping capture")
        CaptureStatus.setCapturing(false)

        try {
            nativeStopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping native capture", e)
        }

        captureWorker.execute {
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
            }

            try {
                vpnInterface?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing VPN interface", e)
            }
            vpnInterface = null

            setPacketQueue(null)
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            Log.d(TAG, "Capture stopped")
        }
    }

    fun onPacketCaptured(packetData: ByteArray) {
        offerPacket(packetData)
    }

    fun onCaptureStats(sent: Long, received: Long, connections: Int) {
        bytesSent = sent
        bytesReceived = received
        numConnections = connections
        mainHandler.post { updateNotification() }
    }

    fun protectSocket(fd: Int): Boolean {
        return try {
            protect(fd)
        } catch (e: Exception) {
            Log.e(TAG, "protect() failed for fd=$fd", e)
            false
        }
    }

    private fun updateNotification() {
        if (!CaptureStatus.isCapturing.value) return
        CaptureNotifier.notifyCapture(
            this,
            CaptureNotifier.buildCaptureNotification(this, bytesSent, bytesReceived, numConnections)
        )
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
        captureWorker.shutdown()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked")
        stopCapture()
        super.onRevoke()
    }
}
