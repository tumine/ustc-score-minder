package com.ustc.scoreminder.data.repository

import android.util.Log
import com.ustc.scoreminder.data.local.CredentialsManager
import com.ustc.scoreminder.data.local.dao.GradeDao
import com.ustc.scoreminder.data.local.entity.GradeEntity
import com.ustc.scoreminder.data.remote.GradeParser
import com.ustc.scoreminder.data.remote.JwAuthenticator
import com.ustc.scoreminder.domain.model.Grade
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 成绩仓库
 * 协调本地和远程数据源
 */
@Singleton
class GradeRepository @Inject constructor(
    private val gradeDao: GradeDao,
    private val authenticator: JwAuthenticator,
    private val gradeParser: GradeParser,
    private val credentialsManager: CredentialsManager
) {
    companion object {
        private const val TAG = "GradeRepository"
    }
    
    /**
     * 成绩同步结果
     */
    data class SyncResult(
        val success: Boolean,
        val newGrades: List<Grade> = emptyList(),
        val removedGrades: List<Grade> = emptyList(),
        val errorMessage: String? = null
    )
    
    /**
     * 获取所有本地成绩（Flow）
     */
    fun getAllGrades(): Flow<List<Grade>> {
        return gradeDao.getAllGrades().map { entities ->
            entities.map { it.toGrade() }
        }
    }
    
    /**
     * 获取所有学期
     */
    fun getAllSemesters(): Flow<List<String>> {
        return gradeDao.getAllSemesters()
    }
    
    /**
     * 同步成绩
     * 返回新增和删除的成绩
     */
    suspend fun syncGrades(): SyncResult {
        val username = credentialsManager.getUsername()
        val password = credentialsManager.getPassword()
        
        if (username == null || password == null) {
            return SyncResult(false, errorMessage = "未保存登录凭证")
        }
        
        // Step 1: 登录
        when (val loginResult = authenticator.login(username, password)) {
            is JwAuthenticator.LoginResult.Error -> {
                return SyncResult(false, errorMessage = loginResult.message)
            }
            is JwAuthenticator.LoginResult.NeedLogin -> {
                return SyncResult(false, errorMessage = "需要重新登录")
            }
            is JwAuthenticator.LoginResult.Success -> {
                Log.d(TAG, "Login successful, fetching grades...")
            }
        }
        
        // Step 2: 获取成绩页面
        val html = authenticator.fetchGradePage()
            ?: return SyncResult(false, errorMessage = "获取成绩页面失败")
        
        // Step 3: 解析成绩
        val remoteGrades = gradeParser.parseGrades(html)
        if (remoteGrades.isEmpty()) {
            Log.w(TAG, "No grades parsed from HTML")
            // 可能是解析失败，不要清空本地数据
            return SyncResult(false, errorMessage = "成绩解析失败，请稍后重试")
        }
        
        // Step 4: 与本地成绩对比
        val localGrades = gradeDao.getAllGradesSnapshot()
        val localGradeMap = localGrades.associateBy { it.courseId }
        val remoteGradeMap = remoteGrades.associateBy { it.courseId }
        
        // 找出新增的成绩
        val newGrades = remoteGrades.filter { it.courseId !in localGradeMap.keys }
        
        // 找出删除的成绩
        val removedGrades = localGrades.filter { it.courseId !in remoteGradeMap.keys }
        
        Log.d(TAG, "Sync result: ${newGrades.size} new, ${removedGrades.size} removed")
        
        // Step 5: 更新本地数据库
        // 删除不存在的成绩
        removedGrades.forEach { grade ->
            gradeDao.deleteGradeById(grade.courseId)
        }
        
        // 插入/更新所有远程成绩
        gradeDao.insertGrades(remoteGrades.map { it.toEntity() })
        
        return SyncResult(
            success = true,
            newGrades = newGrades,
            removedGrades = removedGrades.map { it.toGrade() }
        )
    }
    
    /**
     * 清空所有成绩
     */
    suspend fun clearAllGrades() {
        gradeDao.deleteAllGrades()
    }
    
    /**
     * 获取成绩数量
     */
    suspend fun getGradeCount(): Int {
        return gradeDao.getGradeCount()
    }
    
    // 扩展函数：Entity -> Domain
    private fun GradeEntity.toGrade(): Grade = Grade(
        courseId = courseId,
        courseName = courseName,
        credit = credit,
        score = score,
        gradePoint = gradePoint,
        semester = semester,
        courseType = courseType,
        examType = examType
    )
    
    // 扩展函数：Domain -> Entity
    private fun Grade.toEntity(): GradeEntity = GradeEntity(
        courseId = courseId,
        courseName = courseName,
        credit = credit,
        score = score,
        gradePoint = gradePoint,
        semester = semester,
        courseType = courseType,
        examType = examType
    )
}
