package com.irminsul.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.irminsul.data.repository.GameDataRepository
import com.irminsul.presentation.state.MainUiState

/**
 * 主界面 ViewModel
 * 管理UI状态和导航
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: GameDataRepository
) : ViewModel() {

    companion object {
        const val CHARACTER_COUNT = "characterCount"
        const val ARTIFACT_COUNT = "artifactCount"
        const val WEAPON_COUNT = "weaponCount"
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _navigationState = MutableStateFlow(NavigationState())
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    init {
        loadDataCounts()
    }

    private fun loadDataCounts() {
        viewModelScope.launch {
            try {
                val characterCount = repository.getCharacterCount()
                val artifactCount = repository.getArtifactCount()
                val weaponCount = repository.getWeaponCount()
                
                _uiState.value = _uiState.value.copy(
                    characterCount = characterCount,
                    artifactCount = artifactCount,
                    weaponCount = weaponCount
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = e.message
                )
            }
        }
    }

    fun startCapture() {
        _uiState.value = _uiState.value.copy(
            isCapturing = true,
            captureStatus = "正在捕获..."
        )
    }

    fun stopCapture() {
        _uiState.value = _uiState.value.copy(
            isCapturing = false,
            captureStatus = "捕获已停止"
        )
    }

    fun navigateTo(screen: Screen) {
        _navigationState.value = _navigationState.value.copy(currentScreen = screen)
    }

    fun clearAllData() {
        viewModelScope.launch {
            try {
                repository.clearAllData()
                loadDataCounts()
                _uiState.value = _uiState.value.copy(
                    captureStatus = "数据已清除"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = e.message
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}

/**
 * 导航状态
 */
data class NavigationState(
    val currentScreen: Screen = Screen.Main
)

/**
 * 屏幕定义
 */
sealed class Screen {
    object Main : Screen()
    object Characters : Screen()
    object Artifacts : Screen()
    object Weapons : Screen()
    object Export : Screen()
}
