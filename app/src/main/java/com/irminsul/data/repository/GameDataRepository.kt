package com.irminsul.data.repository

import com.irminsul.data.local.entity.ArtifactEntity
import com.irminsul.data.local.entity.CharacterEntity
import com.irminsul.data.local.entity.WeaponEntity

interface GameDataRepository {
    // 角色数据
    suspend fun getAllCharacters(): List<CharacterEntity>
    suspend fun insertCharacters(characters: List<CharacterEntity>)
    suspend fun getCharacterCount(): Int
    
    // 圣遗物数据
    suspend fun getAllArtifacts(): List<ArtifactEntity>
    suspend fun insertArtifacts(artifacts: List<ArtifactEntity>)
    suspend fun getArtifactCount(): Int
    
    // 武器数据
    suspend fun getAllWeapons(): List<WeaponEntity>
    suspend fun insertWeapons(weapons: List<WeaponEntity>)
    suspend fun getWeaponCount(): Int
    
    // 清空数据
    suspend fun clearAllData()
}
