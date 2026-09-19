package com.esc.irminsul.capture.internal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.esc.irminsul.capture.R

/**
 * Every notification the capture module owns: the ongoing "capture is running"
 * foreground notification, and the heads-up completion notification.
 *
 * The module posts these itself (a host opts out of the completion one with
 * `Config(completionNotification = false)`) because the channel, its importance
 * and the tap target are all the module's business, not the host's.
 */
internal object CaptureNotifier {

    private const val TAG = "CaptureNotifier"

    private const val CHANNEL_CAPTURE_ID = "irminsul_capture"
    private const val NOTIFICATION_ID_CAPTURE = 1

    /** Heads-up channel for completion; also what permission checks look for. */
    const val CHANNEL_COMPLETE_ID = "irminsul_complete_v2"
    private const val NOTIFICATION_ID_COMPLETE = 2

    /**
     * Intent that brings the host app's own launcher activity to the front. The
     * module cannot reference an app activity by class, so it resolves the
     * package launcher instead.
     */
    private fun launchIntentFor(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    private fun pendingIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, launchIntentFor(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Tap target for the foreground notification and the VPN configure entry. */
    internal fun hostLaunchIntent(context: Context, requestCode: Int = 0): PendingIntent =
        pendingIntent(context, requestCode)

    private fun manager(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    /** The low-importance channel behind the ongoing foreground notification. */
    fun ensureCaptureChannel(context: Context) {
        val manager = manager(context) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE_ID,
                context.getString(R.string.notification_channel_capture_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_capture_desc)
                setShowBadge(false)
            }
        )
    }

    /** The high-importance channel the completion heads-up rides on. */
    fun ensureCompletionChannel(context: Context) {
        val manager = manager(context) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_COMPLETE_ID,
                context.getString(R.string.notification_channel_complete_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_complete_desc)
                setShowBadge(true)
                enableLights(true)
                lightColor = Color.GREEN
                setBypassDnd(false)
            }
        )
    }

    fun buildCaptureNotification(
        context: Context,
        sent: Long,
        received: Long,
        connections: Int
    ): Notification {
        val text = "↑${formatBytes(sent)} ↓${formatBytes(received)} | " +
            context.getString(R.string.notification_capture_connections, connections)
        return NotificationCompat.Builder(context, CHANNEL_CAPTURE_ID)
            .setContentTitle("Irminsul - ${formatBytes(sent + received)}")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setOngoing(true)
            .setContentIntent(pendingIntent(context, 0))
            .build()
    }

    fun notifyCapture(context: Context, notification: Notification) {
        manager(context)?.notify(NOTIFICATION_ID_CAPTURE, notification)
    }

    fun showCompletion(
        context: Context,
        charactersCount: Int,
        artifactsCount: Int,
        weaponsCount: Int,
        achievementsCount: Int
    ) {
        val manager = manager(context)
        if (manager == null) {
            Log.e(TAG, "NotificationManager is null")
            return
        }
        ensureCompletionChannel(context)
        val content = context.getString(
            R.string.notification_complete_content,
            charactersCount, artifactsCount, weaponsCount, achievementsCount
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETE_ID)
            .setContentTitle(context.getString(R.string.notification_complete_title))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentIntent(pendingIntent(context, 0))
            .setFullScreenIntent(pendingIntent(context, 1), true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        try {
            manager.notify(NOTIFICATION_ID_COMPLETE, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to post completion notification - permission missing?", e)
        }
    }

    fun cancelCompletion(context: Context) {
        manager(context)?.cancel(NOTIFICATION_ID_COMPLETE)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun Log_e(message: String, error: Throwable? = null) {
        android.util.Log.e(TAG, message, error)
    }
}
