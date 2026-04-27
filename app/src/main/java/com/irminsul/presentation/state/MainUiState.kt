package com.irminsul.presentation.state

/**
 * 主界面UI状态
 */
data class MainUiState(
    val isCapturing: Boolean = false,
    val captureStatus: String = "准备就绪",
    val characterCount: Int = 0,
    val artifactCount: Int = 0,
    val weaponCount: Int = 0,
    val errorMessage: String? = null
)
