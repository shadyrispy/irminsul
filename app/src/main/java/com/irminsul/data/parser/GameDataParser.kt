package com.irminsul.data.parser

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.irminsul.data.local.entity.ArtifactEntity
import com.irminsul.data.local.entity.CharacterEntity
import com.irminsul.data.local.entity.WeaponEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 游戏数据解析器
 * 解析Rust库返回的原神数据
 */
object GameDataParser {
    private const val TAG = "GameDataParser"
    
    /**
     * 解析角色数据
     */
    fun parseCharacter(json: String): CharacterEntity? {
        return try {
            val obj = JSONObject(json)
            CharacterEntity(
                id = obj.getString("id"),
                name = obj.optString("name", "Unknown"),
                level = obj.optInt("level", 1),
                constellation = obj.optInt("constellation", 0),
                weaponId = obj.optString("weaponId", null),
                talents = obj.optJSONObject("talents")?.toString() ?: "{}",
                stats = obj.optJSONObject("stats")?.toString() ?: "{}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse character: ${e.message}")
            null
        }
    }
    
    /**
     * 解析武器数据
     */
    fun parseWeapon(json: String): WeaponEntity? {
        return try {
            val obj = JSONObject(json)
            WeaponEntity(
                id = obj.getString("id"),
                name = obj.optString("name", "Unknown"),
                level = obj.optInt("level", 1),
                refinement = obj.optInt("refinement", 1),
                rarity = obj.optInt("rarity", 3),
                stats = obj.optJSONObject("stats")?.toString() ?: "{}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse weapon: ${e.message}")
            null
        }
    }
    
    /**
     * 解析圣遗物数据
     */
    fun parseArtifact(json: String): ArtifactEntity? {
        return try {
            val obj = JSONObject(json)
            ArtifactEntity(
                id = obj.getString("id"),
                setId = obj.getString("setId"),
                setName = obj.optString("setName", "Unknown"),
                slot = obj.optInt("slot", 1),
                rarity = obj.optInt("rarity", 3),
                level = obj.optInt("level", 0),
                mainStatKey = obj.getString("mainStatKey"),
                mainStatValue = obj.optDouble("mainStatValue", 0.0).toFloat(),
                subStats = obj.optJSONObject("subStats")?.toString() ?: "{}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse artifact: ${e.message}")
            null
        }
    }
    
    /**
     * 批量解析数据
     */
    fun parseBatch(jsonArray: String, type: String): List<Any> {
        val result = mutableListOf<Any>()
        return try {
            val array = JSONArray(jsonArray)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val parsed = when (type) {
                    "character" -> parseCharacter(item.toString())
                    "weapon" -> parseWeapon(item.toString())
                    "artifact" -> parseArtifact(item.toString())
                    else -> null
                }
                parsed?.let { result.add(it) }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse batch: ${e.message}")
            emptyList()
        }
    }
}
