package com.esc.irminsul.ui

import com.esc.irminsul.LocalStorage
import com.esc.irminsul.R
import com.esc.irminsul.UiState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
//! HistoryAndSettingsDialogs.kt — split out of the former 2166-line MainScreen.kt; same package, no visibility changes.

@Composable
fun ExportHistoryDialog(
    history: List<LocalStorage.ExportRecord>,
    onClear: () -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .border(1.dp, Border.copy(alpha = 0.3f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(
                containerColor = Surface.copy(alpha = 0.98f)
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_log),
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.export_history),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (history.isNotEmpty()) {
                            TextButton(
                                onClick = onClear,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.clear_all),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Error
                                )
                            }
                        }
                        
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_error),
                                contentDescription = "Close",
                                tint = TextHint,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Border.copy(alpha = 0.3f))
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (history.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(SurfaceLight),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_download),
                                    contentDescription = null,
                                    tint = TextHint,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.no_export_history),
                                fontSize = 15.sp,
                                color = TextHint
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.exported_data_will_appear),
                                fontSize = 12.sp,
                                color = TextHint.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            history.forEachIndexed { index, record ->
                                HistoryItemDialog(
                                    record = record,
                                    onDelete = { onDelete(record.id) }
                                )
                                if (index < history.size - 1) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(Border.copy(alpha = 0.2f))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryItemDialog(
    record: LocalStorage.ExportRecord,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (record.success) Success.copy(alpha = 0.18f) else Error.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(
                    id = if (record.success) R.drawable.ic_check else R.drawable.ic_error
                ),
                contentDescription = null,
                tint = if (record.success) Success else Error,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.type,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(SurfaceLight)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = record.format,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatTimestamp(record.timestamp),
                fontSize = 13.sp,
                color = TextHint
            )
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_trash),
                contentDescription = "Delete",
                tint = Error.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun SettingsDialog(
    uiState: UiState,
    onFakeInitialize4thLineChange: () -> Unit,
    onIncludeCharactersChange: () -> Unit,
    onIncludeArtifactsChange: () -> Unit,
    onIncludeWeaponsChange: () -> Unit,
    onIncludeMaterialsChange: () -> Unit,
    onMinCharacterLevelChange: (Int) -> Unit,
    onMinCharacterAscensionChange: (Int) -> Unit,
    onMinCharacterConstellationChange: (Int) -> Unit,
    onMinArtifactLevelChange: (Int) -> Unit,
    onMinArtifactRarityChange: (Int) -> Unit,
    onMinWeaponLevelChange: (Int) -> Unit,
    onMinWeaponRefinementChange: (Int) -> Unit,
    onMinWeaponAscensionChange: (Int) -> Unit,
    onMinWeaponRarityChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .border(1.dp, Border.copy(alpha = 0.3f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(
                containerColor = Surface.copy(alpha = 0.98f)
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_error),
                            contentDescription = "Close",
                            tint = TextHint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Border.copy(alpha = 0.3f))
                )

                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    Column {
                        SettingsSection(title = stringResource(R.string.include_data)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SettingsSwitchItem(
                                    title = stringResource(R.string.characters),
                                    subtitle = "",
                                    checked = uiState.includeCharacters,
                                    onCheckedChange = onIncludeCharactersChange,
                                    modifier = Modifier.weight(1f)
                                )
                                SettingsSwitchItem(
                                    title = stringResource(R.string.artifacts),
                                    subtitle = "",
                                    checked = uiState.includeArtifacts,
                                    onCheckedChange = onIncludeArtifactsChange,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SettingsSwitchItem(
                                    title = stringResource(R.string.weapons),
                                    subtitle = "",
                                    checked = uiState.includeWeapons,
                                    onCheckedChange = onIncludeWeaponsChange,
                                    modifier = Modifier.weight(1f)
                                )
                                SettingsSwitchItem(
                                    title = stringResource(R.string.materials),
                                    subtitle = "",
                                    checked = uiState.includeMaterials,
                                    onCheckedChange = onIncludeMaterialsChange,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        SettingsSection(title = stringResource(R.string.character_filters)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SliderItem(
                                    label = stringResource(R.string.min_level),
                                    value = uiState.minCharacterLevel,
                                    valueRange = 1..90,
                                    onValueChange = onMinCharacterLevelChange,
                                    modifier = Modifier.weight(1f)
                                )
                                SliderItem(
                                    label = stringResource(R.string.min_ascension),
                                    value = uiState.minCharacterAscension,
                                    valueRange = 0..6,
                                    onValueChange = onMinCharacterAscensionChange,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            SliderItem(
                                label = stringResource(R.string.min_constellation),
                                value = uiState.minCharacterConstellation,
                                valueRange = 0..6,
                                onValueChange = onMinCharacterConstellationChange
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        SettingsSection(title = stringResource(R.string.artifact_filters)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SliderItem(
                                    label = stringResource(R.string.min_level),
                                    value = uiState.minArtifactLevel,
                                    valueRange = 0..20,
                                    onValueChange = onMinArtifactLevelChange,
                                    modifier = Modifier.weight(1f)
                                )
                                SliderItem(
                                    label = stringResource(R.string.min_rarity),
                                    value = uiState.minArtifactRarity,
                                    valueRange = 1..5,
                                    onValueChange = onMinArtifactRarityChange,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        SettingsSection(title = stringResource(R.string.weapon_filters)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SliderItem(
                                    label = stringResource(R.string.min_level),
                                    value = uiState.minWeaponLevel,
                                    valueRange = 1..90,
                                    onValueChange = onMinWeaponLevelChange,
                                    modifier = Modifier.weight(1f)
                                )
                                SliderItem(
                                    label = stringResource(R.string.min_refinement),
                                    value = uiState.minWeaponRefinement,
                                    valueRange = 1..5,
                                    onValueChange = onMinWeaponRefinementChange,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SliderItem(
                                    label = stringResource(R.string.min_ascension),
                                    value = uiState.minWeaponAscension,
                                    valueRange = 0..6,
                                    onValueChange = onMinWeaponAscensionChange,
                                    modifier = Modifier.weight(1f)
                                )
                                SliderItem(
                                    label = stringResource(R.string.min_rarity),
                                    value = uiState.minWeaponRarity,
                                    valueRange = 1..5,
                                    onValueChange = onMinWeaponRarityChange,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        SettingsSection(title = stringResource(R.string.special_options)) {
                            SettingsSwitchItem(
                                title = stringResource(R.string.simulate_4th_substat),
                                subtitle = "",
                                checked = uiState.fakeInitialize4thLine,
                                onCheckedChange = onFakeInitialize4thLineChange,
                                accentColor = Accent
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Accent,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = SurfaceLight.copy(alpha = 0.8f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(10.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: () -> Unit,
    accentColor: Color = Success,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clickable { onCheckedChange() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextHint
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = { onCheckedChange() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = accentColor,
                checkedTrackColor = accentColor.copy(alpha = 0.35f),
                uncheckedTrackColor = Border.copy(alpha = 0.6f),
                uncheckedThumbColor = TextHint
            ),
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
fun SliderItem(
    label: String,
    value: Int,
    valueRange: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceHighlight)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "$value",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Accent
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = valueRange.last - valueRange.first - 1,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Border.copy(alpha = 0.5f)
            ),
            modifier = Modifier.height(36.dp)
        )
    }
}

@Composable
fun AchievementSettingsDialog(
    currentFormat: String,
    onFormatChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Border.copy(alpha = 0.3f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(
                containerColor = Surface.copy(alpha = 0.98f)
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.achievement_export_settings),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_error),
                            contentDescription = stringResource(R.string.close),
                            tint = TextHint,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                AchievementFormatOption(
                    format = stringResource(R.string.format_uiaf),
                    description = stringResource(R.string.format_uiaf_desc),
                    isSelected = currentFormat == "UIAF",
                    onClick = { onFormatChange("UIAF") }
                )

                Spacer(modifier = Modifier.height(8.dp))

                AchievementFormatOption(
                    format = stringResource(R.string.format_seelie),
                    description = stringResource(R.string.format_seelie_desc),
                    isSelected = currentFormat == "Seelie",
                    onClick = { onFormatChange("Seelie") }
                )

                Spacer(modifier = Modifier.height(8.dp))

                AchievementFormatOption(
                    format = stringResource(R.string.format_csv),
                    description = stringResource(R.string.format_csv_desc),
                    isSelected = currentFormat == "CSV",
                    onClick = { onFormatChange("CSV") }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss
                    ) {
                        Text(
                            text = stringResource(R.string.close),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Accent
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AchievementFormatOption(
    format: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Accent.copy(alpha = 0.15f) else SurfaceLight.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .border(
                        width = 2.dp,
                        color = if (isSelected) Accent else TextHint,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Accent)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = format,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) TextPrimary else TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = TextHint
                )
            }
        }
    }
}


private fun formatTimestamp(timestamp: Long): String {
    val date = Date(timestamp)
    val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return format.format(date)
}
