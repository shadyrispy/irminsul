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
import com.irminsul.presentation.state.WeaponUiState

/**
 * 武器数据 ViewModel
 */
@HiltViewModel
class WeaponViewModel @Inject constructor(
    private val repository: GameDataRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WeaponUiState())
    val uiState: StateFlow<WeaponUiState> = _uiState.asStateFlow()

    init {
        loadWeapons()
    }

    private fun loadWeapons() {
        viewModelScope.launch {
            try {
                val weapons = repository.getAllWeapons()
                _uiState.value = _uiState.value.copy(
                    weapons = weapons,
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

