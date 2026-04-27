package com.irminsul.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.irminsul.data.local.entity.WeaponEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WeaponDao {
    @Query("SELECT * FROM weapons")
    suspend fun getAll(): List<WeaponEntity>
    
    @Query("SELECT * FROM weapons")
    fun getAllFlow(): Flow<List<WeaponEntity>>
    
    @Query("SELECT * FROM weapons WHERE id = :id")
    suspend fun getById(id: String): WeaponEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(weapons: List<WeaponEntity>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(weapon: WeaponEntity)
    
    @Query("DELETE FROM weapons")
    suspend fun deleteAll()
    
    @Query("SELECT COUNT(*) FROM weapons")
    suspend fun getCount(): Int
}
