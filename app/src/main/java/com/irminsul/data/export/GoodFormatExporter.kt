package com.irminsul.data.export

import com.irminsul.data.local.entity.ArtifactEntity
import com.irminsul.data.local.entity.CharacterEntity
import com.irminsul.data.local.entity.WeaponEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * GOOD格式导出器
 * GOOD (Genshin Open Object Definition) 是原神数据交换格式
 */
object GoodFormatExporter {
    
    /**
     * 导出完整数据为GOOD格式
     */
    fun exportAll(
        characters: List<CharacterEntity>,
        weapons: List<WeaponEntity>,
        artifacts: List<ArtifactEntity>
    ): String {
        val root = JSONObject()
        root.put("format", "GOOD")
        root.put("version", 2)
        root.put("source", "Irminsul")
        
        // 导出角色
        val charactersArray = JSONArray()
        for (character in characters) {
            val charObj = JSONObject()
            charObj.put("key", character.id)
            charObj.put("level", character.level)
            charObj.put("constellation", character.constellation)
            
            // 天赋（从JSON解析）
            val talentArray = JSONArray()
            try {
                val talentsJson = JSONObject(character.talents)
                talentArray.put(talentsJson.optInt("auto", 1))
                talentArray.put(talentsJson.optInt("skill", 1))
                talentArray.put(talentsJson.optInt("burst", 1))
            } catch (e: Exception) {
                talentArray.put(1)
                talentArray.put(1)
                talentArray.put(1)
            }
            charObj.put("talent", talentArray)
            
            // 装备武器
            if (character.weaponId != null) {
                charObj.put("weapon", character.weaponId)
            }
            
            charactersArray.put(charObj)
        }
        root.put("characters", charactersArray)
        
        // 导出武器
        val weaponsArray = JSONArray()
        for (weapon in weapons) {
            val weaponObj = JSONObject()
            weaponObj.put("key", weapon.id)
            weaponObj.put("level", weapon.level)
            weaponObj.put("refinement", weapon.refinement)
            weaponObj.put("location", JSONObject.NULL)
            weaponObj.put("lock", false)
            weaponsArray.put(weaponObj)
        }
        root.put("weapons", weaponsArray)
        
        // 导出圣遗物
        val artifactsArray = JSONArray()
        for (artifact in artifacts) {
            val artifactObj = JSONObject()
            artifactObj.put("setKey", artifact.setId)
            artifactObj.put("slotKey", getSlotKey(artifact.slot))
            artifactObj.put("rarity", artifact.rarity)
            artifactObj.put("level", artifact.level)
            artifactObj.put("primary", artifact.mainStatKey)
            
            // 副词条
            val subStats = JSONArray()
            val subStatsMap = parseJsonMap(artifact.subStats)
            for ((key, value) in subStatsMap) {
                val subObj = JSONObject()
                subObj.put("key", key)
                subObj.put("value", value)
                subStats.put(subObj)
            }
            artifactObj.put("substats", subStats)
            
            artifactObj.put("location", JSONObject.NULL)
            artifactObj.put("lock", false)
            
            artifactsArray.put(artifactObj)
        }
        root.put("artifacts", artifactsArray)
        
        return root.toString(2) // 缩进2空格
    }
    
    private fun getSlotKey(slot: Int): String {
        return when (slot) {
            1 -> "flower"
            2 -> "plume"
            3 -> "sands"
            4 -> "goblet"
            5 -> "circlet"
            else -> "flower"
        }
    }
    
    private fun parseJsonMap(json: String): Map<String, Float> {
        val map = mutableMapOf<String, Float>()
        try {
            val jsonObj = JSONObject(json)
            val keys = jsonObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = jsonObj.getDouble(key).toFloat()
                map[key] = value
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }
}
