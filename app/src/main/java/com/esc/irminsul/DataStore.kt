package com.esc.irminsul

import com.esc.irminsul.capture.CaptureResult
import com.esc.irminsul.capture.IrminsulCapture
import com.esc.irminsul.capture.DataStatusSink
import com.esc.irminsul.capture.DataStatus
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class ExportStats(
    val charactersCount: Int,
    val artifactsCount: Int,
    val weaponsCount: Int,
    val materialsCount: Int
)

data class ExportSettings(
    var includeCharacters: Boolean = true,
    var includeArtifacts: Boolean = true,
    var includeWeapons: Boolean = true,
    var includeMaterials: Boolean = true,
    var minCharacterLevel: Int = 1,
    var minCharacterAscension: Int = 0,
    var minCharacterConstellation: Int = 0,
    var minArtifactLevel: Int = 0,
    var minArtifactRarity: Int = 1,
    var minWeaponLevel: Int = 1,
    var minWeaponRefinement: Int = 1,
    var minWeaponAscension: Int = 0,
    var minWeaponRarity: Int = 1,
    var fakeInitialize4thLine: Boolean = false
)

class DataStore : DataStatusSink {
    companion object {
        private const val TAG = "DataStore"
        const val FORMAT_UIAF = 0
        const val FORMAT_SEELIE = 1
        const val FORMAT_CSV = 2
    }

    private val _dataStatus = MutableStateFlow(DataStatus())
    val dataStatus: StateFlow<DataStatus> = _dataStatus.asStateFlow()

    override fun publish(status: DataStatus) {
        _dataStatus.value = status
        Log.d(TAG, "Status updated: items=${status.itemsLoaded} chars=${status.charactersLoaded} " +
                "ach=${status.achievementsLoaded} artifacts=${status.artifactsCount} " +
                "weapons=${status.weaponsCount} materials=${status.materialsCount}")
    }

    fun clear() {
        Log.i(TAG, "DataStore cleared")
        _dataStatus.value = DataStatus()
    }

    fun exportGood(settings: ExportSettings = ExportSettings()): Pair<String, ExportStats> {
        val settingsJson = settingsToJson(settings)
        val json = when (val result = IrminsulCapture.exportGood(settingsJson)) {
            is CaptureResult.Ok -> result.value
            is CaptureResult.Err -> throw RuntimeException("GOOD export failed: ${result.error}")
        }
        val stats = parseExportStats(json, settings)
        return Pair(json, stats)
    }

    fun exportAchievements(formatCode: Int): String {
        return when (val result = IrminsulCapture.exportAchievements(formatCode)) {
            is CaptureResult.Ok -> result.value
            is CaptureResult.Err -> throw RuntimeException("Achievement export failed: ${result.error}")
        }
    }

    private fun settingsToJson(settings: ExportSettings): String {
        val obj = JSONObject()
        obj.put("include_characters", settings.includeCharacters)
        obj.put("include_artifacts", settings.includeArtifacts)
        obj.put("include_weapons", settings.includeWeapons)
        obj.put("include_materials", settings.includeMaterials)
        obj.put("min_character_level", settings.minCharacterLevel)
        obj.put("min_character_ascension", settings.minCharacterAscension)
        obj.put("min_character_constellation", settings.minCharacterConstellation)
        obj.put("min_artifact_level", settings.minArtifactLevel)
        obj.put("min_artifact_rarity", settings.minArtifactRarity)
        obj.put("min_weapon_level", settings.minWeaponLevel)
        obj.put("min_weapon_refinement", settings.minWeaponRefinement)
        obj.put("min_weapon_ascension", settings.minWeaponAscension)
        obj.put("min_weapon_rarity", settings.minWeaponRarity)
        obj.put("fake_initialize_4th_line", settings.fakeInitialize4thLine)
        return obj.toString()
    }

    private fun parseExportStats(json: String, settings: ExportSettings): ExportStats {
        return try {
            val root = JSONObject(json)
            var charactersCount = 0
            var artifactsCount = 0
            var weaponsCount = 0
            var materialsCount = 0

            if (settings.includeCharacters) {
                charactersCount = root.optJSONArray("characters")?.length() ?: 0
            }
            if (settings.includeArtifacts) {
                artifactsCount = root.optJSONArray("artifacts")?.length() ?: 0
            }
            if (settings.includeWeapons) {
                weaponsCount = root.optJSONArray("weapons")?.length() ?: 0
            }
            if (settings.includeMaterials) {
                val materials = root.optJSONObject("materials")
                materialsCount = materials?.length() ?: 0
            }

            ExportStats(charactersCount, artifactsCount, weaponsCount, materialsCount)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse export stats", e)
            ExportStats(0, 0, 0, 0)
        }
    }
}
