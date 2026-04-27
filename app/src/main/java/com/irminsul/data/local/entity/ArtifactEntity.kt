package com.irminsul.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "artifacts")
data class ArtifactEntity(
    @PrimaryKey
    val id: String,
    val setId: String,
    val setName: String,
    val slot: Int, // 1-5 对应花、羽、沙、杯、头
    val rarity: Int, // 1-5
    val level: Int,
    val mainStatKey: String,
    val mainStatValue: Float,
    val subStats: String, // JSON格式存储
    val unactivatedRolls: String? = null, // JSON格式存储，未激活词条
    val lastUpdated: Long = System.currentTimeMillis()
)
