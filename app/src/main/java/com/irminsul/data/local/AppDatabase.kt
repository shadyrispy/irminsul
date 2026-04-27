package com.irminsul.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.irminsul.data.local.dao.ArtifactDao
import com.irminsul.data.local.dao.CharacterDao
import com.irminsul.data.local.dao.WeaponDao
import com.irminsul.data.local.entity.ArtifactEntity
import com.irminsul.data.local.entity.CharacterEntity
import com.irminsul.data.local.entity.WeaponEntity

@Database(
    entities = [
        CharacterEntity::class,
        ArtifactEntity::class,
        WeaponEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun weaponDao(): WeaponDao
}
