package com.irminsul.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.irminsul.data.export.GoodFormatExporter
import com.irminsul.data.repository.GameDataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 导出 ViewModel
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val repository: GameDataRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    /**
     * 导出数据为GOOD格式
     */
    fun exportGoodFormat(): String? {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExporting = true)
            
            try {
                val characters = repository.getAllCharacters()
                val weapons = repository.getAllWeapons()
                val artifacts = repository.getAllArtifacts()
                
                val goodJson = GoodFormatExporter.exportAll(characters, weapons, artifacts)
                
                // 保存到文件
                val file = saveToFile(goodJson, "irminsul_export_good.json")
                
                _uiState.value = _uiState.value.copy(
                    isExporting = false,
                    exportSuccess = true,
                    exportFilePath = file.absolutePath,
                    errorMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isExporting = false,
                    exportSuccess = false,
                    errorMessage = e.message
                )
            }
        }
        return null
    }

    /**
     * 保存JSON到文件
     */
    private suspend fun saveToFile(json: String, filename: String): File {
        return withContext(Dispatchers.IO) {
            val downloadsDir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(downloadsDir, filename)
            file.writeText(json)
            file
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}

/**
 * 导出UI状态
 */
data class ExportUiState(
    val isExporting: Boolean = false,
    val exportSuccess: Boolean = false,
    val exportFilePath: String? = null,
    val errorMessage: String? = null
)
