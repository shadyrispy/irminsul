package com.irminsul.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.irminsul.data.local.entity.ArtifactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtifactDao {
    @Query("SELECT * FROM artifacts")
    suspend fun getAll(): List<ArtifactEntity>
    
    @Query("SELECT * FROM artifacts")
    fun getAllFlow(): Flow<List<ArtifactEntity>>
    
    @Query("SELECT * FROM artifacts WHERE id = :id")
    suspend fun getById(id: String): ArtifactEntity?
    
    @Query("SELECT * FROM artifacts WHERE setId = :setId")
    suspend fun getBySetId(setId: String): List<ArtifactEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(artifacts: List<ArtifactEntity>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(artifact: ArtifactEntity)
    
    @Query("DELETE FROM artifacts")
    suspend fun deleteAll()
    
    @Query("SELECT COUNT(*) FROM artifacts")
    suspend fun getCount(): Int
}
