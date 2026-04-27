package com.irminsul.presentation.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.irminsul.presentation.state.MainUiState

@Composable
fun MainScreen(
    uiState: MainUiState,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onClearData: () -> Unit,
    onExportClick: () -> Unit,
    onNavigateToCharacters: () -> Unit,
    onNavigateToArtifacts: () -> Unit,
    onNavigateToWeapons: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Irminsul",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (uiState.isCapturing) {
            Button(
                onClick = onStopCapture,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("停止捕获")
            }
        } else {
            Button(
                onClick = onStartCapture
            ) {
                Text("开始捕获")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = uiState.captureStatus,
            style = MaterialTheme.typography.bodyMedium,
            color = if (uiState.isCapturing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 数据显示
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DataCard(title = "角色", count = uiState.characterCount, onClick = onNavigateToCharacters)
            DataCard(title = "圣遗物", count = uiState.artifactCount, onClick = onNavigateToArtifacts)
            DataCard(title = "武器", count = uiState.weaponCount, onClick = onNavigateToWeapons)
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        // 清除数据按钮
        Button(
            onClick = onClearData,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text("清除数据")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 导出数据按钮
        Button(
            onClick = onExportClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary
            )
        ) {
            Text("导出数据")
        }

        // 错误信息
        uiState.errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun DataCard(title: String, count: Int, onClick: () -> Unit = {}) {
    Card(
        modifier = Modifier.width(100.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }
}