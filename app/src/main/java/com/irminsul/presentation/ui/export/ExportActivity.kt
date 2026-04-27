package com.irminsul.presentation.ui.export

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.irminsul.presentation.theme.IrminsulTheme
import com.irminsul.presentation.viewmodel.ExportViewModel
import com.irminsul.presentation.viewmodel.ExportUiState
import dagger.hilt.android.AndroidEntryPoint

/**
 * 数据导出 Activity
 * 用于导出解析的游戏数据为 GOOD 格式
 */
@AndroidEntryPoint
class ExportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel: ExportViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()

            IrminsulTheme {
                ExportScreen(
                    uiState = uiState,
                    onExportClick = { format ->
                        if (format == "GOOD") {
                            viewModel.exportGoodFormat()
                        }
                    },
                    onBackClick = { finish() }
                )
            }
        }
    }
}

@Composable
fun ExportScreen(
    uiState: ExportUiState,
    onExportClick: (String) -> Unit,
    onBackClick: () -> Unit
) {
    var selectedFormat by remember { mutableStateOf("GOOD") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "导出数据",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "选择导出格式:",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        listOf("GOOD", "JSON", "CSV").forEach { format ->
            Row(
                modifier = Modifier.fillMaxWidth(0.6f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedFormat == format,
                    onClick = { selectedFormat = format }
                )
                Text(
                    text = format,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 导出状态显示
        if (uiState.isExporting) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(8.dp))
            Text("正在导出...")
        }

        if (uiState.exportSuccess) {
            Text(
                text = "导出成功！文件保存在:\n${uiState.exportFilePath}",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        uiState.errorMessage?.let { error ->
            Text(
                text = "导出失败: $error",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onBackClick,
                modifier = Modifier.weight(1f)
            ) {
                Text("取消")
            }

            Button(
                onClick = { onExportClick(selectedFormat) },
                modifier = Modifier.weight(1f),
                enabled = !uiState.isExporting
            ) {
                Text("导出")
            }
        }
    }
}
