package com.irminsul.presentation.state

import com.irminsul.data.local.entity.WeaponEntity

/**
 * 武器界面UI状态
 */
data class WeaponUiState(
    val weapons: List<WeaponEntity> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
