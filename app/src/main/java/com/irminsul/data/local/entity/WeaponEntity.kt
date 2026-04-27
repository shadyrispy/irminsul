package com.irminsul.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "weapons")
data class WeaponEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val level: Int,
    val refinement: Int,
    val rarity: Int,
    val stats: String, // JSON格式存储
    val lastUpdated: Long = System.currentTimeMillis()
)
