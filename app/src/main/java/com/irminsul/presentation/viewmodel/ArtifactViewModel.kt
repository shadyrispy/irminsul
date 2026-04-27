package com.irminsul.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.irminsul.data.repository.GameDataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.irminsul.presentation.state.ArtifactUiState

/**
 * 圣遗物数据 ViewModel
 */
@HiltViewModel
class ArtifactViewModel @Inject constructor(
    private val repository: GameDataRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArtifactUiState())
    val uiState: StateFlow<ArtifactUiState> = _uiState.asStateFlow()

    init {
        loadArtifacts()
    }

    private fun loadArtifacts() {
        viewModelScope.launch {
            try {
                val artifacts = repository.getAllArtifacts()
                _uiState.value = _uiState.value.copy(
                    artifacts = artifacts,
                    isLoading = false,
                    errorMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message
                )
            }
        }

        _uiState.value = _uiState.value.copy(isLoading = true)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}

