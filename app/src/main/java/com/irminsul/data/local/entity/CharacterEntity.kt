package com.irminsul.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val level: Int,
    val constellation: Int,
    val weaponId: String? = null,
    val talents: String, // JSON格式存储
    val stats: String, // JSON格式存储
    val lastUpdated: Long = System.currentTimeMillis()
)
