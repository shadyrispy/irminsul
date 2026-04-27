package com.irminsul.presentation.ui.main

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.irminsul.presentation.theme.IrminsulTheme
import com.irminsul.presentation.ui.character.CharacterScreen
import com.irminsul.presentation.ui.main.MainScreen
import com.irminsul.presentation.ui.export.ExportActivity
import com.irminsul.presentation.ui.artifact.ArtifactScreen
import com.irminsul.presentation.ui.weapon.WeaponScreen
import com.irminsul.presentation.state.MainUiState
import com.irminsul.presentation.viewmodel.MainViewModel
import com.irminsul.service.GenshinCaptureService
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    // VPN权限请求
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startCaptureService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IrminsulTheme {
                val viewModel: MainViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsState()
                var currentScreen by remember { mutableStateOf("main") }
                
                when (currentScreen) {
                    "main" -> {
                        MainScreen(
                            uiState = uiState,
                            onStartCapture = { checkVpnPermissionAndStart() },
                            onStopCapture = { stopCaptureService() },
                            onClearData = { viewModel.clearAllData() },
                            onExportClick = {
                                startActivity(
                                    Intent(this@MainActivity, ExportActivity::class.java)
                                )
                            },
                            onNavigateToCharacters = { currentScreen = "characters" },
                            onNavigateToArtifacts = { currentScreen = "artifacts" },
                            onNavigateToWeapons = { currentScreen = "weapons" }
                        )
                    }
                    "characters" -> {
                        CharacterScreen(
                            onBackClick = { currentScreen = "main" }
                        )
                    }
                    "artifacts" -> {
                        ArtifactScreen(
                            onBackClick = { currentScreen = "main" }
                        )
                    }
                    "weapons" -> {
                        WeaponScreen(
                            onBackClick = { currentScreen = "main" }
                        )
                    }
                }
            }
        }
    }
    
    /**
     * 检查VPN权限并启动
     */
    private fun checkVpnPermissionAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // 需要请求VPN权限
            vpnPermissionLauncher.launch(intent)
        } else {
            // 已有权限，直接启动
            startCaptureService()
        }
    }

    /**
     * 启动捕获服务
     */
    private fun startCaptureService() {
        val intent = Intent(this, GenshinCaptureService::class.java).apply {
            action = GenshinCaptureService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * 停止捕获服务
     */
    private fun stopCaptureService() {
        val intent = Intent(this, GenshinCaptureService::class.java).apply {
            action = GenshinCaptureService.ACTION_STOP
        }
        startService(intent)
    }
}
