package com.ustc.scoreminder.data.local.dao

import androidx.room.*
import com.ustc.scoreminder.data.local.entity.GradeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GradeDao {
    
    @Query("SELECT * FROM grades ORDER BY semester DESC, courseName ASC")
    fun getAllGrades(): Flow<List<GradeEntity>>
    
    @Query("SELECT * FROM grades ORDER BY semester DESC, courseName ASC")
    suspend fun getAllGradesSnapshot(): List<GradeEntity>
    
    @Query("SELECT * FROM grades WHERE courseId = :courseId")
    suspend fun getGradeById(courseId: String): GradeEntity?
    
    @Query("SELECT * FROM grades WHERE semester = :semester ORDER BY courseName ASC")
    fun getGradesBySemester(semester: String): Flow<List<GradeEntity>>
    
    @Query("SELECT DISTINCT semester FROM grades ORDER BY semester DESC")
    fun getAllSemesters(): Flow<List<String>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGrade(grade: GradeEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGrades(grades: List<GradeEntity>)
    
    @Delete
    suspend fun deleteGrade(grade: GradeEntity)
    
    @Query("DELETE FROM grades WHERE courseId = :courseId")
    suspend fun deleteGradeById(courseId: String)
    
    @Query("DELETE FROM grades")
    suspend fun deleteAllGrades()
    
    @Query("SELECT COUNT(*) FROM grades")
    suspend fun getGradeCount(): Int
}
