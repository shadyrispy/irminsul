package com.irminsul.data.repository

import com.irminsul.data.local.AppDatabase
import com.irminsul.data.local.entity.ArtifactEntity
import com.irminsul.data.local.entity.CharacterEntity
import com.irminsul.data.local.entity.WeaponEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameDataRepositoryImpl @Inject constructor(
    private val database: AppDatabase
) : GameDataRepository {

    override suspend fun getAllCharacters(): List<CharacterEntity> {
        return database.characterDao().getAll()
    }

    override suspend fun insertCharacters(characters: List<CharacterEntity>) {
        database.characterDao().insertAll(characters)
    }

    override suspend fun getCharacterCount(): Int {
        return database.characterDao().getCount()
    }

    override suspend fun getAllArtifacts(): List<ArtifactEntity> {
        return database.artifactDao().getAll()
    }

    override suspend fun insertArtifacts(artifacts: List<ArtifactEntity>) {
        database.artifactDao().insertAll(artifacts)
    }

    override suspend fun getArtifactCount(): Int {
        return database.artifactDao().getCount()
    }

    override suspend fun getAllWeapons(): List<WeaponEntity> {
        return database.weaponDao().getAll()
    }

    override suspend fun insertWeapons(weapons: List<WeaponEntity>) {
        database.weaponDao().insertAll(weapons)
    }

    override suspend fun getWeaponCount(): Int {
        return database.weaponDao().getCount()
    }

    override suspend fun clearAllData() {
        database.characterDao().deleteAll()
        database.artifactDao().deleteAll()
        database.weaponDao().deleteAll()
    }
}
