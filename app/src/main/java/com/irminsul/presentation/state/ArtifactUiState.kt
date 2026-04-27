package com.irminsul.presentation.state

import com.irminsul.data.local.entity.ArtifactEntity

/**
 * 圣遗物界面UI状态
 */
data class ArtifactUiState(
    val artifacts: List<ArtifactEntity> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
