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
import com.esc.irminsul.DataStatus
import com.esc.irminsul.DataStatusSink
import com.esc.irminsul.capture.IrminsulCapture
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal host app for the published `com.esc.irminsul:capture` AAR: obtains
 * VPN consent, starts the service, and shows live command/progress counts.
 */
class SampleActivity : ComponentActivity() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var lastStatus: DataStatus? = null

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var toggleButton: Button

    /** Receives collection progress from the library. */
    private val statusSink = object : DataStatusSink {
        override fun publish(status: DataStatus) {
            lastStatus = status
            render()
        }
    }

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK || IrminsulCapture.hasVpnPermission(this)) {
                startCapture()
            } else {
                append("VPN consent declined")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        append(
            when (IrminsulCapture.initNative()) {
                0 -> "native sniffer ready"
                else -> "native library unavailable — parsing disabled"
            }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
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
        logView = TextView(this@SampleActivity)
        addView(toggleButton)
        addView(statusView)
        addView(ScrollView(this@SampleActivity).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })
    }

    private fun requestThenStart() {
        IrminsulCapture.vpnPermissionIntent(this)?.let { vpnConsent.launch(it) } ?: startCapture()
    }

    private fun startCapture() {
        IrminsulCapture.start(applicationContext, statusSink)
        toggleButton.text = "Stop capture"
        append("capture started")
    }

    private fun stopCapture() {
        IrminsulCapture.stop(applicationContext)
        toggleButton.text = "Start capture"
        append("capture stopped")
    }

    private fun render() {
        val decoded = IrminsulCapture.packets.records.value.size
        val latest = IrminsulCapture.packets.records.value.lastOrNull()
        val s = lastStatus
        statusView.text = buildString {
            append("capturing=").append(IrminsulCapture.isCapturing.value)
            append("  decoded=").append(decoded)
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
