package com.esc.irminsul

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import com.esc.irminsul.capture.CaptureResult
import com.esc.irminsul.capture.CaptureSource
import com.esc.irminsul.capture.DataStatus
import com.esc.irminsul.capture.DataStatusSink
import com.esc.irminsul.capture.IrminsulCapture
import java.io.File

/**
 * The debug build's adb surface: everything here is a knob for driving the capture
 * from a shell, because none of it has a UI affordance or cannot be reached from
 * one.
 *
 * ```
 * adb shell am broadcast -n com.esc.irminsul/.DebugTestReceiver -a com.esc.irminsul.STALL_TUNNEL --ei ms 25000
 * adb shell am broadcast -n com.esc.irminsul/.DebugTestReceiver -a com.esc.irminsul.DUMP
 * adb shell am broadcast -n com.esc.irminsul/.DebugTestReceiver -a com.esc.irminsul.EXPORT_SAMPLES
 * adb shell am broadcast -n com.esc.irminsul/.DebugTestReceiver -a com.esc.irminsul.REPLAY --es path <file>
 * ```
 *
 * `STALL_TUNNEL` black-holes the tunnel so the game's connection dies in front of
 * it, which is how the stall length that forces a re-handshake was measured.
 * `DUMP` writes every raw packet out, and `EXPORT_SAMPLES` copies the known-body
 * samples next to it, both into the public Downloads dir — the only place a shell
 * can read back on images that refuse `run-as` *and* hide `Android/data`.
 * `REPLAY` drives `CaptureSource.File`, because a pcap picked through the UI needs
 * a document picker, which adb cannot operate.
 *
 * The class lives in the debug source set along with its manifest entry, so a
 * release APK carries no receiver at all: an installed release cannot be told to
 * stall its own tunnel or dump its traffic by any other app.
 */
class DebugTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The public Downloads dir rather than the app's own external files dir:
        // on API 30+ images `adb pull` gets EACCES on Android/data/<package>/files,
        // so a dump that "succeeded" is unretrievable. Downloads is where the app
        // already writes its exports, and where a shell can read them back.
        val outDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: context.getExternalFilesDir(null) ?: context.filesDir
        when (intent.action) {
            ACTION_STALL -> IrminsulCapture.stallTunnel(intent.getIntExtra("ms", 0))

            ACTION_DUMP -> {
                val name = intent.getStringExtra("name") ?: "dump.pcap"
                val file = File(outDir, name)
                IrminsulCapture.dumpRawPackets(file.absolutePath)
                Log.i(TAG, "raw dump -> ${file.absolutePath}")
            }

            ACTION_DUMP_STOP -> IrminsulCapture.dumpRawPackets(null)

            ACTION_EXPORT_SAMPLES -> when (val result = IrminsulCapture.exportKnownBodies(outDir.absolutePath)) {
                is CaptureResult.Ok -> Log.i(TAG, "known bodies -> ${result.value}")
                is CaptureResult.Err -> Log.i(TAG, "no known bodies to export: ${result.error}")
            }

            ACTION_REPLAY -> {
                val path = intent.getStringExtra("path")
                if (path == null) {
                    Log.w(TAG, "REPLAY needs --es path <pcap>")
                    return
                }
                // The startup call every host makes and a broadcast process does not:
                // without the sniffer behind it, a replay opens the file happily and
                // decodes nothing — which reads, from the outside, like the file was
                // empty. Say so if it fails.
                val init = IrminsulCapture.initNative(context)
                if (init is CaptureResult.Err) {
                    Log.w(TAG, "native init failed: ${init.error}")
                }
                IrminsulCapture.start(context, CaptureSource.File(path), SilentSink)
                Log.i(TAG, "replaying $path")
            }
        }
    }

    private object SilentSink : DataStatusSink {
        override fun publish(status: DataStatus) = Unit
    }

    companion object {
        private const val TAG = "DebugTestReceiver"
        const val ACTION_STALL = "com.esc.irminsul.STALL_TUNNEL"
        const val ACTION_DUMP = "com.esc.irminsul.DUMP"
        const val ACTION_DUMP_STOP = "com.esc.irminsul.DUMP_STOP"
        const val ACTION_EXPORT_SAMPLES = "com.esc.irminsul.EXPORT_SAMPLES"
        const val ACTION_REPLAY = "com.esc.irminsul.REPLAY"
    }
}
