package com.ustc.scoreminder.data.repository

import android.util.Log
import com.ustc.scoreminder.data.local.CredentialsManager
import com.ustc.scoreminder.data.local.dao.GradeDao
import com.ustc.scoreminder.data.local.entity.GradeEntity
import com.ustc.scoreminder.data.remote.BackgroundWebViewAuthenticator
import com.ustc.scoreminder.data.remote.GradeParser
import com.ustc.scoreminder.data.remote.JwAuthenticator
import com.ustc.scoreminder.data.remote.WebViewGradeFetcher
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
    private val credentialsManager: CredentialsManager,
    private val webViewGradeFetcher: WebViewGradeFetcher,
    private val backgroundWebViewAuthenticator: BackgroundWebViewAuthenticator
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
        val errorMessage: String? = null,
        val isAuthError: Boolean = false
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
        // Step 1: 首先尝试通过 WebView 提取成绩（利用已有的会话 cookies）
        Log.d(TAG, "Attempting to fetch grades via WebView...")
        val webViewGrades = try {
            webViewGradeFetcher.fetchGradeDataViaJs()
        } catch (e: Exception) {
            Log.w(TAG, "WebView grade fetch failed", e)
            null
        }
        
        if (!webViewGrades.isNullOrEmpty()) {
            Log.d(TAG, "Successfully fetched ${webViewGrades.size} grades via WebView")
            return processRemoteGrades(webViewGrades.map { it.toGrade() })
        }
        
        // Step 2: WebView 提取失败，检查是否已经通过 cookies 登录
        Log.d(TAG, "WebView fetch returned empty, trying direct HTTP...")
        // 注意：authenticator.checkLoginStatus() 是基于 HTTP 请求的，
        // 如果 cookies 已经失效，这里会返回 false
        var isAlreadyLoggedIn = authenticator.checkLoginStatus()
        
        if (!isAlreadyLoggedIn) {
            // 如果没有有效的 cookies，尝试使用保存的凭证登录
            val loginResult = performAutoLogin()
            if (!loginResult.success) {
                credentialsManager.setNeedsReLogin(true)
                return SyncResult(false, errorMessage = loginResult.errorMessage, isAuthError = loginResult.isAuthError)
            }
            isAlreadyLoggedIn = true
        } else {
            Log.d(TAG, "Already logged in via cookies, fetching grades...")
        }
        
        // Step 3: 获取成绩页面
        // 此时应该已经登录成功（无论是之前的 Session 还是刚才的重新登录）
        var html = authenticator.fetchGradePage()
        
        // 检查 HTML 是否为登录页面（False Positive: checkLoginStatus 说已登录，但实际 Session 已过期）
        if (html != null && (html.contains("id.ustc.edu.cn") || html.contains("login"))) {
            Log.w(TAG, "Session expired despite checkLoginStatus=true, attempting auto re-login...")
            val loginResult = performAutoLogin()
            if (loginResult.success) {
                // 重试获取页面
                html = authenticator.fetchGradePage()
            } else {
                credentialsManager.setNeedsReLogin(true)
                return SyncResult(false, errorMessage = loginResult.errorMessage, isAuthError = loginResult.isAuthError)
            }
        }
        
        if (html == null) {
             // 即使登录显示成功，获取页面仍可能失败（例如重定向问题）
             // 最后尝试一次 WebView 获取
             Log.w(TAG, "HTTP fetch grade page failed, trying WebView again as last resort...")
             val retryGrades = try {
                webViewGradeFetcher.fetchGradeDataViaJs()
            } catch (e: Exception) {
                null
            }
            
            if (!retryGrades.isNullOrEmpty()) {
                return processRemoteGrades(retryGrades.map { it.toGrade() })
            }
             
             return SyncResult(false, errorMessage = "获取成绩页面失败，请稍后重试")
        }
        
        // Step 4: 解析成绩
        val remoteGrades = gradeParser.parseGrades(html)
        if (remoteGrades.isEmpty()) {
            Log.w(TAG, "No grades parsed from HTML, trying WebView fetch (hybrid approach)...")
            
            // 再次尝试 WebView 方式 (Jsoup 解析失败可能是因为页面结构变成了 SPA)
            val retryGrades = try {
                webViewGradeFetcher.fetchGradeDataViaJs()
            } catch (e: Exception) {
                null
            }
            
            if (!retryGrades.isNullOrEmpty()) {
                return processRemoteGrades(retryGrades.map { it.toGrade() })
            }
            
            // 如果 HTML 内容看起来像是登录页，说明 Session 可能还是无效
            if (html.contains("id.ustc.edu.cn") || html.contains("login")) {
                 return SyncResult(false, errorMessage = "会话依然失效，请手动重新登录", isAuthError = true)
            }
            
            return SyncResult(false, errorMessage = "成绩解析失败，请稍后重试")
        }
        
        return processRemoteGrades(remoteGrades)
    }

    /**
     * 尝试自动登录
     */
    private suspend fun performAutoLogin(): LoginResult {
        val username = credentialsManager.getUsername()
        val password = credentialsManager.getPassword()
        
        if (username == null || password == null) {
            return LoginResult(false, errorMessage = "未保存登录凭证，请重新登录", isAuthError = true)
        }
        
        Log.d(TAG, "Attempting auto re-login with saved credentials...")
        
        // 尝试 HTTP 登录 (JwAuthenticator)
        // 如果 JwAuthenticator 无法处理新版 CAS，我们将回退到 BackgroundWebViewAuthenticator
        var loginSuccess = false
        var loginErrorMsg = ""
        var isAuthError = false
        
        when (val result = authenticator.login(username, password)) {
            is JwAuthenticator.LoginResult.Success -> {
                Log.d(TAG, "HTTP auto re-login successful")
                loginSuccess = true
                credentialsManager.setNeedsReLogin(false)
            }
            is JwAuthenticator.LoginResult.Error -> {
                Log.w(TAG, "HTTP login failed: ${result.message}")
                loginErrorMsg = result.message
                if (isAuthenticationError(result.message)) {
                    isAuthError = true
                }
            }
            is JwAuthenticator.LoginResult.NeedLogin -> {
                Log.w(TAG, "HTTP login failed: NeedLogin")
                isAuthError = true
                loginErrorMsg = "需要重新登录"
            }
        }
        
        // 如果 HTTP 登录失败，尝试 BackgroundWebViewAuthenticator
        if (!loginSuccess) {
            Log.d(TAG, "HTTP login failed, attempting Background WebView login...")
            
            val bgResult = backgroundWebViewAuthenticator.performLogin(username, password)
            
            if (bgResult.isSuccess) {
                Log.d(TAG, "Background WebView login successful")
                loginSuccess = true
                credentialsManager.setNeedsReLogin(false)
                // 登录成功后，Cookies 已经同步到 CookieManager 和 OkHttpClient
            } else {
                val error = bgResult.exceptionOrNull()
                Log.e(TAG, "Background WebView login failed", error)
                
                // 如果之前的 HTTP 登录已经是认证错误，则保持该错误
                // 否则使用 WebView 登录的错误
                if (!isAuthError) {
                     val msg = error?.message ?: "后台登录失败"
                     loginErrorMsg = msg
                     isAuthError = true // 既然两次尝试都失败了，大概率是凭证问题或系统变更，提示用户重新登录比较稳妥
                }
            }
        }
        
        return LoginResult(loginSuccess, errorMessage = loginErrorMsg, isAuthError = isAuthError)
    }

    private data class LoginResult(
        val success: Boolean,
        val errorMessage: String? = null,
        val isAuthError: Boolean = false
    )
    
    /**
     * 处理远程成绩并更新本地数据库
     */
    private suspend fun processRemoteGrades(remoteGrades: List<Grade>): SyncResult {
        // 与本地成绩对比
        val localGrades = gradeDao.getAllGradesSnapshot()
        val localGradeMap = localGrades.associateBy { it.courseId }
        val remoteGradeMap = remoteGrades.associateBy { it.courseId }
        
        // 找出新增的成绩
        val newGrades = remoteGrades.filter { it.courseId !in localGradeMap.keys }
        
        // 找出删除的成绩
        val removedGrades = localGrades.filter { it.courseId !in remoteGradeMap.keys }
        
        Log.d(TAG, "Sync result: ${newGrades.size} new, ${removedGrades.size} removed")
        
        // 更新本地数据库
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
    
    /**
     * 判断错误消息是否为认证错误（密码错误等）
     * 区分认证错误和网络错误，以决定是否提示用户重新输入凭证
     */
    private fun isAuthenticationError(message: String): Boolean {
        val authErrorKeywords = listOf(
            "用户名或密码错误，请确认后重新输入",
            "用户名或密码错误",
            "认证失败",
            "登录失败",
            "密码错误",
            "credential",
            "authentication",
            "unauthorized",
            "登录验证失败"
        )
        return authErrorKeywords.any { message.contains(it, ignoreCase = true) }
    }
    
    // 扩展函数：WebView GradeData -> Domain
    private fun WebViewGradeFetcher.GradeData.toGrade(): Grade = Grade(
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
