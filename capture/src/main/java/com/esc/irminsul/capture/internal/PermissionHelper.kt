package com.esc.irminsul.capture.internal

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.esc.irminsul.capture.PermissionKind
import com.esc.irminsul.capture.PermissionSnapshot

internal object PermissionHelper {

    /**
     * Everything a capture session can be blocked on, judged on the device and
     * including ROM-specific guidance. This is the module's own reading of the
     * state, so it returns the public type rather than a private mirror of it.
     */
    fun checkPermissions(context: Context): PermissionSnapshot {
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return PermissionSnapshot(
            notificationGranted = notificationGranted,
            headsUpEnabled = isHeadsUpEnabled(context),
            vpnPermissionGranted = isVpnPermissionGranted(context),
            batteryOptimizationExempt = isBatteryOptimizationExempt(context),
            needsAutoStart = RomUtils.needsAutoStartGuide() &&
                RomUtils.getAutoStartSettingsIntent(context) != null,
            romHint = RomUtils.getRomPermissionTips()
        )
    }

    /**
     * 检查悬浮通知（Heads-up）是否开启
     * Android 8+: 检查通知渠道 importance 是否 >= HIGH
     * Android 8-: 检查全局 heads_up_notifications_enabled 设置
     *
     * A missing channel is not a pass. The module creates it during
     * [com.esc.irminsul.capture.IrminsulCapture.initNative], so by the time a
     * host reads this it exists — and on ROMs that clamp the importance (EMUI
     * drops HIGH to DEFAULT and locks it) the host sees the truth instead of a
     * first-launch "已开启" that contradicts itself a moment later.
     */
    fun isHeadsUpEnabled(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            val channel = manager.getNotificationChannel(CaptureNotifier.CHANNEL_COMPLETE_ID)
            channel != null && channel.importance >= NotificationManager.IMPORTANCE_HIGH
        } else {
            try {
                Settings.Secure.getInt(context.contentResolver, "heads_up_notifications_enabled", 1) == 1
            } catch (e: Exception) {
                true
            }
        }
    }

    fun isVpnPermissionGranted(context: Context): Boolean {
        return VpnService.prepare(context) == null
    }

    fun isBatteryOptimizationExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun getVpnPermissionIntent(context: Context): Intent? {
        return VpnService.prepare(context)
    }

    fun getBatteryOptimizationIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
    }

    fun getNotificationSettingsIntent(context: Context): Intent {
        return RomUtils.getNotificationSettingsIntent(context)
        ?: Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

    fun getChannelSettingsIntent(context: Context, channelId: String): Intent {
        return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
    }

    fun getAutoStartSettingsIntent(context: Context): Intent? {
        return RomUtils.getAutoStartSettingsIntent(context)
    }

    /**
     * 应用详情设置页（兜底方案，所有权限都能从这里找到）
     */
    fun getAppDetailsSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
    }

    /**
     * 通知权限设置页（标准 Android，可能被 ROM 重定向）
     */
    fun getAppNotificationSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    /**
     * Ordered settings pages that can grant [kind], best guess first. The
     * ROM-specific variants come before the stock Android ones so a host never
     * has to know which vendor it is running on.
     */
    fun fixIntents(context: Context, kind: PermissionKind): List<Intent> {
        val details = listOf(getAppDetailsSettingsIntent(context))
        return when (kind) {
            PermissionKind.Notifications ->
                listOfNotNull(RomUtils.getNotificationSettingsIntent(context)) +
                    listOf(getAppNotificationSettingsIntent(context)) + details
            PermissionKind.HeadsUp ->
                listOf(
                    getChannelSettingsIntent(context, CaptureNotifier.CHANNEL_COMPLETE_ID),
                    getAppNotificationSettingsIntent(context)
                ) + details
            PermissionKind.Vpn -> listOfNotNull(getVpnPermissionIntent(context))
            PermissionKind.BatteryOptimization -> listOf(getBatteryOptimizationIntent(context))
            PermissionKind.AutoStart -> listOfNotNull(getAutoStartSettingsIntent(context)) + details
            PermissionKind.AppDetails -> details
        }
    }
}
