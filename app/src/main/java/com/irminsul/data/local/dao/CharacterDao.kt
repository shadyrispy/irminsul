package com.irminsul.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.irminsul.data.local.entity.CharacterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters")
    suspend fun getAll(): List<CharacterEntity>
    
    @Query("SELECT * FROM characters")
    fun getAllFlow(): Flow<List<CharacterEntity>>
    
    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun getById(id: String): CharacterEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(characters: List<CharacterEntity>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(character: CharacterEntity)
    
    @Query("DELETE FROM characters")
    suspend fun deleteAll()
    
    @Query("SELECT COUNT(*) FROM characters")
    suspend fun getCount(): Int
}
