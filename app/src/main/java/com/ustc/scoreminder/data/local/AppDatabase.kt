package com.ustc.scoreminder.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ustc.scoreminder.data.local.dao.GradeDao
import com.ustc.scoreminder.data.local.entity.GradeEntity

@Database(
    entities = [GradeEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun gradeDao(): GradeDao
    
    companion object {
        const val DATABASE_NAME = "score_minder_db"
    }
}
