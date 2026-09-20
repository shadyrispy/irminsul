package com.esc.irminsul

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.esc.irminsul.capture.IrminsulCapture
import java.io.File

/**
 * Test hook, driven from adb: black-holes the capture tunnel so the game's
 * connection dies in front of it, hunting the stall length that makes the
 * client re-handshake.
 *
 * ```
 * adb shell am broadcast -a com.esc.irminsul.STALL_TUNNEL --ei ms 5000
 * ```
 *
 * Deliberately exported: it only acts while a capture session is running, and
 * its whole effect is a network stall for the game.
 */
class StallTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION -> IrminsulCapture.stallTunnel(intent.getIntExtra("ms", 0))
            ACTION_DUMP -> {
                val name = intent.getStringExtra("name") ?: "dump.pcap"
                // External files dir: readable by `adb pull` on images where
                // run-as is blocked.
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                val file = File(dir, name)
                IrminsulCapture.dumpRawPackets(file.absolutePath)
                Log.i("StallTestReceiver", "raw dump -> ${file.absolutePath}")
            }
            ACTION_DUMP_STOP -> IrminsulCapture.dumpRawPackets(null)
        }
    }

    companion object {
        const val ACTION = "com.esc.irminsul.STALL_TUNNEL"
        const val ACTION_DUMP = "com.esc.irminsul.DUMP"
        const val ACTION_DUMP_STOP = "com.esc.irminsul.DUMP_STOP"
    }
}
