package com.esc.irminsul.ui

import com.esc.irminsul.MainViewModel
import com.esc.irminsul.R
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
//! MainScreen.kt — split out of the former 2166-line MainScreen.kt; same package, no visibility changes.

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    vpnPermissionLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>,
    notificationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    onToggleCapture: () -> Unit,
    onOpenPcapFile: () -> Unit,
    onResetData: () -> Unit,
    onCopyGood: () -> Unit,
    onDownloadGood: () -> Unit,
    onCopyAchievements: () -> Unit,
    onDownloadAchievements: () -> Unit,
    onSetAchievementFormat: (String) -> Unit,
    onOpenAchievements: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAchievementSettingsDialog by remember { mutableStateOf(false) }
    var showExportHistoryDialog by remember { mutableStateOf(false) }
    
    val backgroundBitmap = remember { loadBackgroundImage(context) }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            HeaderCard(backgroundBitmap)

            Spacer(modifier = Modifier.height(24.dp))

            CaptureCard(
                isCapturing = uiState.isCapturing,
                artifactsCount = uiState.artifactsCount,
                charactersCount = uiState.charactersCount,
                materialsCount = uiState.materialsCount,
                weaponsCount = uiState.weaponsCount,
                achievementsCount = uiState.achievementsCount,
                artifactsLoaded = uiState.itemsLoaded,
                charactersLoaded = uiState.charactersLoaded,
                materialsLoaded = uiState.itemsLoaded,
                weaponsLoaded = uiState.weaponsLoaded,
                achievementsLoaded = uiState.achievementsLoaded,
                onToggleCapture = onToggleCapture,
                onOpenPcapFile = onOpenPcapFile,
                onResetData = onResetData
            )

            AutoStopSettingRow(
                enabled = uiState.autoStopEnabled,
                onToggle = { viewModel.setAutoStopEnabled(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            ExportSection(
                title = stringResource(R.string.data_export),
                subtitle = stringResource(R.string.good_format),
                canExport = uiState.canExportGood,
                fakeInitializeEnabled = uiState.fakeInitialize4thLine,
                onCopy = onCopyGood,
                onDownload = onDownloadGood,
                onSettings = { showSettingsDialog = true }
            )

            Spacer(modifier = Modifier.height(16.dp))

            AchievementExportSection(
                canExport = uiState.canExportAchievements,
                currentFormat = uiState.achievementExportFormat,
                onCopy = onCopyAchievements,
                onOpen = onOpenAchievements,
                onDownload = onDownloadAchievements,
                onSettings = { showAchievementSettingsDialog = true }
            )

            Spacer(modifier = Modifier.height(48.dp))
        }

        AnimatedVisibility(
            visible = uiState.toastMessage.isNotEmpty(),
            enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.9f),
            exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.9f)
        ) {
            ToastDialog(message = uiState.toastMessage) {
                viewModel.clearToast()
            }
        }

        if (uiState.showLaunchGameDialog) {
            LaunchGameDialog(
                onLaunchGame = { viewModel.launchGame() },
                onDismiss = { viewModel.dismissLaunchGameDialog() }
            )
        }

        if (uiState.showReloginDialog) {
            AwaitingLoginDialog(
                onDismiss = { viewModel.dismissReloginDialog() }
            )
        }

        if (uiState.showPermissionDialog) {
            PermissionSetupDialog(
                permissionState = uiState.permissionState,
                onOpenNotificationSettings = {
                    viewModel.requestNotificationPermission(notificationPermissionLauncher)
                },
                onOpenChannelSettings = { viewModel.openChannelSettings() },
                onOpenAutoStartSettings = { viewModel.openAutoStartSettings() },
                onRequestVpnPermission = { viewModel.requestVpnPermission(vpnPermissionLauncher) },
                onOpenBatterySettings = { viewModel.openBatteryOptimizationSettings() },
                onTestNotification = { viewModel.testHeadsUpNotification() },
                onRecheck = { viewModel.recheckPermissions() },
                onDismiss = { viewModel.dismissPermissionDialog() }
            )
        }

        if (showExportHistoryDialog) {
            ExportHistoryDialog(
                history = uiState.exportHistory,
                onClear = { viewModel.clearExportHistory() },
                onDelete = { id -> viewModel.deleteExportRecord(id) },
                onDismiss = { showExportHistoryDialog = false }
            )
        }

        if (showSettingsDialog) {
            SettingsDialog(
                uiState = uiState,
                onFakeInitialize4thLineChange = { viewModel.toggleFakeInitialize4thLine() },
                onIncludeCharactersChange = { viewModel.toggleIncludeCharacters() },
                onIncludeArtifactsChange = { viewModel.toggleIncludeArtifacts() },
                onIncludeWeaponsChange = { viewModel.toggleIncludeWeapons() },
                onIncludeMaterialsChange = { viewModel.toggleIncludeMaterials() },
                onMinCharacterLevelChange = { viewModel.updateMinCharacterLevel(it) },
                onMinCharacterAscensionChange = { viewModel.updateMinCharacterAscension(it) },
                onMinCharacterConstellationChange = { viewModel.updateMinCharacterConstellation(it) },
                onMinArtifactLevelChange = { viewModel.updateMinArtifactLevel(it) },
                onMinArtifactRarityChange = { viewModel.updateMinArtifactRarity(it) },
                onMinWeaponLevelChange = { viewModel.updateMinWeaponLevel(it) },
                onMinWeaponRefinementChange = { viewModel.updateMinWeaponRefinement(it) },
                onMinWeaponAscensionChange = { viewModel.updateMinWeaponAscension(it) },
                onMinWeaponRarityChange = { viewModel.updateMinWeaponRarity(it) },
                onDismiss = { showSettingsDialog = false }
            )
        }

        if (showAchievementSettingsDialog) {
            AchievementSettingsDialog(
                currentFormat = uiState.achievementExportFormat,
                onFormatChange = onSetAchievementFormat,
                onDismiss = { showAchievementSettingsDialog = false }
            )
        }
    }
}

@Composable
fun AutoStopSettingRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Surface.copy(alpha = 0.55f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.auto_stop_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.auto_stop_subtitle),
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
fun HeaderCard(backgroundBitmap: ImageBitmap?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(20.dp))
    ) {
        if (backgroundBitmap != null) {
            Image(
                bitmap = backgroundBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Background.copy(alpha = 0.40f),
                                Background.copy(alpha = 0.87f)
                            )
                        )
                    )
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SurfaceHighlight)
            )
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            HeaderSection()
        }
    }
}

@Composable
fun HeaderSection() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 44.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Serif,
            color = TextPrimary,
            letterSpacing = 3.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.app_description),
            fontSize = 13.sp,
            color = TextSecondary,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun CaptureCard(
    isCapturing: Boolean,
    artifactsCount: Int,
    charactersCount: Int,
    materialsCount: Int,
    weaponsCount: Int,
    achievementsCount: Int,
    artifactsLoaded: Boolean,
    charactersLoaded: Boolean,
    materialsLoaded: Boolean,
    weaponsLoaded: Boolean,
    achievementsLoaded: Boolean,
    onToggleCapture: () -> Unit,
    onOpenPcapFile: () -> Unit,
    onResetData: () -> Unit
) {
    val borderColor = if (isCapturing) Error.copy(alpha = 0.4f) else Border.copy(alpha = 0.3f)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isCapturing) 25.dp else 16.dp,
                spotColor = if (isCapturing) Error.copy(alpha = 0.31f) else ButtonPrimary.copy(alpha = 0.19f),
                ambientColor = if (isCapturing) Error.copy(alpha = 0.13f) else ButtonPrimary.copy(alpha = 0.08f)
            )
            .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
        colors = CardDefaults.cardColors(
            containerColor = Surface.copy(alpha = 0.95f)
        ),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (isCapturing) stringResource(R.string.capturing) else stringResource(R.string.packet_capture),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (isCapturing) stringResource(R.string.listening_for_data) else stringResource(R.string.ready_to_capture),
                            fontSize = 12.sp,
                            color = TextHint
                        )
                    }
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CaptureButton(
                            isCapturing = isCapturing,
                            onClick = onToggleCapture
                        )

                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (!isCapturing) SurfaceLight else Surface.copy(alpha = 0.5f))
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { if (!isCapturing) onResetData() },
                                        onLongPress = { if (!isCapturing) onOpenPcapFile() }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_reset),
                                contentDescription = "Reset Data",
                                tint = if (!isCapturing) TextPrimary else TextDisabled,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Border.copy(alpha = 0.4f))
            )

            Spacer(modifier = Modifier.height(20.dp))

            DataStatsGrid(
                stats = listOf(
                    DataStat(stringResource(R.string.artifacts), artifactsCount, artifactsLoaded),
                    DataStat(stringResource(R.string.characters), charactersCount, charactersLoaded),
                    DataStat(stringResource(R.string.weapons), weaponsCount, weaponsLoaded),
                    DataStat(stringResource(R.string.materials), materialsCount, materialsLoaded),
                    DataStat(stringResource(R.string.achievements), achievementsCount, achievementsLoaded)
                )
            )
        }
    }
}

@Composable
fun CaptureButton(
    isCapturing: Boolean,
    onClick: () -> Unit
) {
    val buttonColor = if (isCapturing) Error else ButtonSuccess
    val iconRes = if (isCapturing) R.drawable.ic_stop else R.drawable.ic_play

    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isCapturing) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(buttonColor)
            .shadow(
                elevation = if (isCapturing) 12.dp else 16.dp,
                spotColor = if (isCapturing) Error.copy(alpha = 0.6f) else ButtonSuccess.copy(alpha = 0.6f),
                ambientColor = if (isCapturing) Error.copy(alpha = 0.3f) else ButtonSuccess.copy(alpha = 0.3f)
            )
            .clickable { onClick() }
            .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = if (isCapturing) "Stop" else "Start",
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
        
        if (isCapturing) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .border(2.dp, Error.copy(alpha = 0.4f), CircleShape)
            )
        }
    }
}

data class DataStat(val label: String, val count: Int, val isLoaded: Boolean)

@Composable
fun DataStatsGrid(stats: List<DataStat>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DataStatItem(stats[0])
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DataStatItem(stats[1])
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DataStatItem(stats[2])
        }
    }
    
    Spacer(modifier = Modifier.height(14.dp))
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DataStatItem(stats[3])
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DataStatItem(stats[4])
        }
    }
}

@Composable
fun DataStatItem(stat: DataStat) {
    AnimatedContent(
        targetState = stat.isLoaded,
        transitionSpec = {
            slideInVertically(initialOffsetY = { -20 }) + fadeIn() togetherWith
            slideOutVertically(targetOffsetY = { 20 }) + fadeOut()
        }
    ) { isLoaded ->
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isLoaded) SurfaceHighlight else SurfaceLight),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (isLoaded) stat.count.toString() else "-",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (isLoaded) Success else TextHint
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stat.label,
                    fontSize = 9.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
fun PanelCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Border.copy(alpha = 0.25f), RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(
            containerColor = Surface.copy(alpha = 0.92f)
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        content()
    }
}

private fun loadBackgroundImage(context: Context): ImageBitmap? {
    return try {
        context.assets.open("background.webp").use { inputStream ->
            val bitmap = BitmapFactory.decodeStream(inputStream)
            bitmap.asImageBitmap()
        }
    } catch (e: Exception) {
        null
    }
}
