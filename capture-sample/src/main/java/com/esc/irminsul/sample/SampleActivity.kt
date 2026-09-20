package com.esc.irminsul.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.esc.irminsul.capture.CaptureResult
import com.esc.irminsul.capture.CaptureSource
import com.esc.irminsul.capture.DataStatus
import com.esc.irminsul.capture.DataStatusSink
import com.esc.irminsul.capture.IrminsulCapture
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Minimal host app for the published `com.esc.irminsul:capture` AAR: obtains
 * VPN consent, starts the service, and shows live command/progress counts.
 */
class SampleActivity : ComponentActivity() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var lastStatus: DataStatus? = null

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var toggleButton: Button
    private lateinit var reloginButton: Button

    /** Receives collection progress from the library, on the library's decode thread. */
    private val statusSink = object : DataStatusSink {
        override fun publish(status: DataStatus) {
            lastStatus = status
            runOnUiThread { render() }
        }
    }

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK ||
                IrminsulCapture.refreshPermissions(this).vpnPermissionGranted
            ) {
                startCapture()
            } else {
                append("VPN consent declined")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        observeCapture()

        append(
            when (val init = IrminsulCapture.initNative(this)) {
                is CaptureResult.Ok -> "native sniffer ready"
                is CaptureResult.Err -> "capture unavailable: ${init.error}"
            }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    /** The library's flows are the only source of truth for what the UI shows. */
    private fun observeCapture() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    IrminsulCapture.isCapturing.collect {
                        toggleButton.text = if (it) "Stop capture" else "Start capture"
                        render()
                    }
                }
                launch {
                    IrminsulCapture.packets.collect { render() }
                }
                launch {
                    IrminsulCapture.droppedPackets.collect { render() }
                }
                launch {
                    IrminsulCapture.sessionPhase.collect { render() }
                }
                launch {
                    IrminsulCapture.traffic.collect { render() }
                }
                launch {
                    IrminsulCapture.logs.collect { append(it) }
                }
            }
        }
    }

    private fun buildLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 48, 48, 48)
        statusView = TextView(this@SampleActivity)
        toggleButton = Button(this@SampleActivity).apply {
            text = "Start capture"
            setOnClickListener {
                if (IrminsulCapture.isCapturing.value) stopCapture() else requestThenStart()
            }
        }
        reloginButton = Button(this@SampleActivity).apply {
            text = "Force re-login"
            setOnClickListener { forceRelogin() }
        }
        logView = TextView(this@SampleActivity)
        addView(toggleButton)
        addView(reloginButton)
        addView(statusView)
        addView(ScrollView(this@SampleActivity).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })
    }

    private fun requestThenStart() {
        IrminsulCapture.vpnConsentIntent(this)?.let { vpnConsent.launch(it) } ?: startCapture()
    }

    private fun startCapture() {
        // A host that shows its own UI does not want the library's notification.
        // autoForceRelogin stays at its default: when the tunnel carries game
        // traffic that nothing decrypts, the library restarts the game so its
        // login — and with it the session key — runs in front of the capture.
        lastStatus = null   // a new session has collected nothing, whatever the last one did
        IrminsulCapture.start(
            applicationContext,
            CaptureSource.Vpn,
            statusSink,
            IrminsulCapture.Config(completionNotification = false)
        )
        append("capture started")
    }

    private fun stopCapture() {
        IrminsulCapture.stop(applicationContext)
        append("capture stopped")
    }

    /** Manual version of what [IrminsulCapture.Config.autoForceRelogin] does. */
    private fun forceRelogin() {
        when (IrminsulCapture.forceRelogin(applicationContext)) {
            is CaptureResult.Ok -> append("restarting the game to catch its login")
            is CaptureResult.Err -> append("force re-login refused")
        }
    }

    private fun render() {
        val records = IrminsulCapture.packets.value
        val decoded = records.size
        val latest = records.lastOrNull()
        val s = lastStatus
        val traffic = IrminsulCapture.traffic.value
        statusView.text = buildString {
            append(IrminsulCapture.sessionPhase.value)
            append("  decoded=").append(decoded)
            val dropped = IrminsulCapture.droppedPackets.value
            if (dropped > 0) append("  dropped=").append(dropped)
            append("\ntotal=").append(traffic.totalBytes)
            append("B  conns=").append(traffic.connections)
            if (latest != null) {
                append("\nlast: ").append(latest.name)
                append(" (").append(latest.cmdId).append(")")
            }
            if (s != null) {
                append("\nchars=").append(s.charactersCount)
                append(" artifacts=").append(s.artifactsCount)
                append(" weapons=").append(s.weaponsCount)
                append(" achievements=").append(s.achievementsCount)
            }
        }
    }

    private fun append(line: String) {
        logView.append(timeFormat.format(Date()))
        logView.append("  ")
        logView.append(line)
        logView.append("\n")
        render()
    }
}
