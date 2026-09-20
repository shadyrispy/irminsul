package com.esc.irminsul

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.esc.irminsul.capture.IrminsulCapture

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
        if (intent.action != ACTION) return
        val ms = intent.getIntExtra("ms", 0)
        IrminsulCapture.stallTunnel(ms)
    }

    companion object {
        const val ACTION = "com.esc.irminsul.STALL_TUNNEL"
    }
}
