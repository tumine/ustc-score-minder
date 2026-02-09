package com.ustc.scoreminder.di

import android.content.Context
import androidx.room.Room
import com.ustc.scoreminder.data.local.AppDatabase
import com.ustc.scoreminder.data.local.dao.GradeDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).build()
    }
    
    @Provides
    @Singleton
    fun provideGradeDao(database: AppDatabase): GradeDao {
        return database.gradeDao()
    }
}
