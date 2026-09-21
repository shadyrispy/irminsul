package com.esc.irminsul.ui

import com.esc.irminsul.R
import com.esc.irminsul.capture.PermissionSnapshot
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
//! MainDialogs.kt — split out of the former 2166-line MainScreen.kt; same package, no visibility changes.

@Composable
fun ToastDialog(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2000)
        onDismiss()
    }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .border(1.dp, Border.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(
                containerColor = Surface.copy(alpha = 0.98f)
            ),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Success.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_check),
                        contentDescription = null,
                        tint = Success,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun LaunchGameDialog(
    onLaunchGame: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .border(1.dp, Border.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(
                containerColor = Surface.copy(alpha = 0.98f)
            ),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(ButtonSuccess.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_play),
                        contentDescription = null,
                        tint = ButtonSuccess,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.capture_started),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.launch_game_hint),
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp
                )
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Border.copy(alpha = 0.3f))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Warning.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.launch_game_tip),
                        fontSize = 11.sp,
                        color = Warning,
                        lineHeight = 16.sp
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceLight)
                            .clickable { onDismiss() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.launch_later),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ButtonPrimary)
                            .clickable { onLaunchGame() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.launch_game),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shown when the tunnel carries game traffic but nothing decrypts, i.e. the
 * capture joined a session already in progress. Nothing here closes the game:
 * only a fresh login helps, and that is the player's call to make.
 */
@Composable
fun AwaitingLoginDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.padding(horizontal = 32.dp),
            colors = CardDefaults.cardColors(containerColor = Surface.copy(alpha = 0.98f)),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(16.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.relogin_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.relogin_body),
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 19.sp
                )
                Spacer(modifier = Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ButtonPrimary)
                        .clickable { onDismiss() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.relogin_confirm),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionSetupDialog(
    permissionState: PermissionSnapshot,
    onOpenNotificationSettings: () -> Unit,
    onOpenChannelSettings: () -> Unit,
    onOpenAutoStartSettings: () -> Unit,
    onRequestVpnPermission: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onTestNotification: () -> Unit,
    onRecheck: () -> Unit,
    onDismiss: () -> Unit
) {
    // 必须权限未通过时不允许关闭
    val canClose = permissionState.allRequiredGranted
    Dialog(onDismissRequest = { if (canClose) onDismiss() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .border(1.dp, Border.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(
                containerColor = Surface.copy(alpha = 0.98f)
            ),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (canClose) Success.copy(alpha = 0.18f)
                            else Warning.copy(alpha = 0.18f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (canClose) R.drawable.ic_check else R.drawable.ic_error
                        ),
                        contentDescription = null,
                        tint = if (canClose) Success else Warning,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        if (canClose) R.string.permission_all_ready_title
                        else R.string.permission_setup_title
                    ),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.permission_setup_message),
                    fontSize = 12.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                // === 必须权限 ===
                Text(
                    text = stringResource(R.string.permission_required_section),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Accent,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))

                // 1. Notification permission
                PermissionItem(
                    label = stringResource(R.string.permission_notification),
                    status = permissionState.notificationGranted,
                    actionLabel = stringResource(R.string.permission_open_settings),
                    onAction = onOpenNotificationSettings
                )
                Spacer(modifier = Modifier.height(6.dp))

                // 2. Heads-up notification
                PermissionItem(
                    label = stringResource(R.string.permission_heads_up),
                    status = permissionState.headsUpEnabled,
                    actionLabel = stringResource(R.string.permission_open_settings),
                    onAction = onOpenChannelSettings
                )
                Spacer(modifier = Modifier.height(6.dp))

                // 3. VPN permission
                PermissionItem(
                    label = stringResource(R.string.permission_vpn),
                    status = permissionState.vpnPermissionGranted,
                    actionLabel = stringResource(R.string.permission_authorize),
                    onAction = onRequestVpnPermission
                )

                Spacer(modifier = Modifier.height(14.dp))

                // === 建议权限 ===
                Text(
                    text = stringResource(R.string.permission_recommended_section),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextHint,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))

                // 4. Battery optimization (recommended)
                PermissionItem(
                    label = stringResource(R.string.permission_battery_optimization),
                    status = permissionState.batteryOptimizationExempt,
                    actionLabel = stringResource(R.string.permission_open_settings),
                    onAction = onOpenBatterySettings
                )
                Spacer(modifier = Modifier.height(6.dp))

                // 5. Auto-start (Chinese ROM only, recommended)
                if (permissionState.needsAutoStart) {
                    PermissionItem(
                        label = stringResource(R.string.permission_auto_start),
                        status = false,
                        statusLabel = stringResource(R.string.permission_manual_required),
                        actionLabel = stringResource(R.string.permission_open_settings),
                        onAction = onOpenAutoStartSettings
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // ROM-specific tips
                val romTips = permissionState.romHint
                if (romTips.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Accent.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(text = "💡", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = romTips,
                                fontSize = 10.sp,
                                color = TextSecondary,
                                lineHeight = 14.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Test notification (when notification granted)
                if (permissionState.notificationGranted && permissionState.headsUpEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ButtonSuccess.copy(alpha = 0.15f))
                            .clickable { onTestNotification() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.test_notification),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ButtonSuccess
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Bottom buttons — fixed height to prevent squishing
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Re-check button (always visible)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceLight)
                            .clickable { onRecheck() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.permission_recheck),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }

                    // Done button - only enabled when required permissions granted
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (canClose) ButtonSuccess
                                else ButtonSuccess.copy(alpha = 0.3f)
                            )
                            .clickable(enabled = canClose) { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.permission_done),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (canClose) Color.White else TextDisabled
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionItem(
    label: String,
    status: Boolean,
    statusLabel: String? = null,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceLight.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status icon
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (status) Success.copy(alpha = 0.2f) else Error.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(
                    id = if (status) R.drawable.ic_check else R.drawable.ic_error
                ),
                contentDescription = null,
                tint = if (status) Success else Error,
                modifier = Modifier.size(12.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        // Label
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        // Status text
        Text(
            text = statusLabel ?: if (status) stringResource(R.string.permission_granted)
                                  else stringResource(R.string.permission_not_granted),
            fontSize = 11.sp,
            color = if (status) Success else Warning,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.width(6.dp))
        // Action button (only when not granted)
        if (!status) {
            Text(
                text = actionLabel,
                fontSize = 11.sp,
                color = Accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onAction() }
            )
        }
    }
}

