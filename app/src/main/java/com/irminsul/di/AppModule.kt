package com.irminsul.di

import android.content.Context
import androidx.room.Room
import com.irminsul.data.local.AppDatabase
import com.irminsul.data.repository.GameDataRepository
import com.irminsul.data.repository.GameDataRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "irminsul_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideGameDataRepository(
        database: AppDatabase
    ): GameDataRepository {
        return GameDataRepositoryImpl(database)
    }
}
