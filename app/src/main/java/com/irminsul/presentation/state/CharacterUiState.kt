package com.irminsul.presentation.state

import com.irminsul.data.local.entity.CharacterEntity

/**
 * 角色界面UI状态
 */
data class CharacterUiState(
    val characters: List<CharacterEntity> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
