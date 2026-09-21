package com.esc.irminsul.ui

import com.esc.irminsul.R
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
//! ExportUi.kt — split out of the former 2166-line MainScreen.kt; same package, no visibility changes.

@Composable
fun ExportSection(
    title: String,
    subtitle: String,
    canExport: Boolean,
    fakeInitializeEnabled: Boolean,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onSettings: () -> Unit
) {
    PanelCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = TextHint
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (fakeInitializeEnabled) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Accent.copy(alpha = 0.22f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.fourth_line),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Accent
                            )
                        }
                    }

                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceLight)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = "Settings",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExportActionButton(
                    icon = R.drawable.ic_copy,
                    label = stringResource(R.string.copy),
                    enabled = canExport,
                    onClick = onCopy
                )
                
                Spacer(modifier = Modifier.width(14.dp))
                
                ExportActionButton(
                    icon = R.drawable.ic_download,
                    label = stringResource(R.string.save),
                    enabled = canExport,
                    onClick = onDownload,
                    isPrimary = true
                )
            }
        }
    }
}

@Composable
fun ExportActionButton(
    icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    isPrimary: Boolean = false
) {
    val bgColor = if (enabled) {
        if (isPrimary) {
            ButtonPrimary
        } else {
            SurfaceLight
        }
    } else {
        Surface.copy(alpha = 0.4f)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clickable(enabled = enabled) { onClick() },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = label,
            tint = if (enabled) {
                if (isPrimary) Color.White else TextPrimary
            } else {
                TextDisabled
            },
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) {
                if (isPrimary) Color.White else TextPrimary
            } else {
                TextDisabled
            }
        )
    }
}

@Composable
fun AchievementExportSection(
    canExport: Boolean,
    currentFormat: String,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onSettings: () -> Unit
) {
    PanelCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.achievement_export),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(
                            when (currentFormat) {
                                "UIAF" -> R.string.format_uiaf
                                "Seelie" -> R.string.format_seelie
                                "CSV" -> R.string.format_csv
                                else -> R.string.format_uiaf
                            }
                        ),
                        fontSize = 12.sp,
                        color = TextHint
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceLight)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = "Settings",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentFormat == "UIAF") {
                    ExportActionButton(
                        icon = R.drawable.ic_upload,
                        label = stringResource(R.string.open),
                        enabled = canExport,
                        onClick = onOpen
                    )
                } else {
                    ExportActionButton(
                        icon = R.drawable.ic_copy,
                        label = stringResource(R.string.copy),
                        enabled = canExport,
                        onClick = onCopy
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                ExportActionButton(
                    icon = R.drawable.ic_download,
                    label = stringResource(R.string.save),
                    enabled = canExport,
                    onClick = onDownload,
                    isPrimary = true
                )
            }
        }
    }
}

