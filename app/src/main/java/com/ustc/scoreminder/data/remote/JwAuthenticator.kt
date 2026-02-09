package com.ustc.scoreminder.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.jsoup.Jsoup
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 教务系统登录认证器
 * 处理 USTC 教务系统的登录逻辑
 */
@Singleton
class JwAuthenticator @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "JwAuthenticator"
        private const val BASE_URL = "https://jw.ustc.edu.cn"
        private const val GRADE_URL = "$BASE_URL/for-std/grade/sheet"
        private const val LOGIN_URL = "$BASE_URL/login"
        private const val HOME_URL = "$BASE_URL/home"
        private const val CAS_LOGIN_URL = "https://passport.ustc.edu.cn/login"
    }

    sealed class LoginResult {
        data class Success(val cookies: List<Cookie>) : LoginResult()
        data class Error(val message: String) : LoginResult()
        object NeedLogin : LoginResult()
    }
    
    /**
     * 尝试登录并返回结果
     */
    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            // Step 1: 访问成绩页面，获取重定向和必要的 cookies
            val gradeRequest = Request.Builder()
                .url(GRADE_URL)
                .get()
                .build()
            
            val gradeResponse = httpClient.newCall(gradeRequest).execute()
            val gradeHtml = gradeResponse.body?.string() ?: ""
            val responseUrl = gradeResponse.request.url.toString()
            
            Log.d(TAG, "Grade page response URL: $responseUrl")
            
            // 如果已经登录，直接返回成功
            if (responseUrl.contains("for-std/grade/sheet") && !responseUrl.contains("login")) {
                Log.d(TAG, "Already logged in")
                return@withContext LoginResult.Success(emptyList())
            }
            
            // Step 2: 解析登录页面获取必要参数
            val loginDoc = Jsoup.parse(gradeHtml)
            
            // 获取 CAS 登录页面
            val casLoginRequest = Request.Builder()
                .url(CAS_LOGIN_URL)
                .addHeader("Referer", LOGIN_URL)
                .get()
                .build()
            
            val casResponse = httpClient.newCall(casLoginRequest).execute()
            val casHtml = casResponse.body?.string() ?: ""
            val casDoc = Jsoup.parse(casHtml)
            
            // 获取登录表单中的隐藏字段
            val casKey = casDoc.select("input[name=CAS_LT]").attr("value")
            val execution = casDoc.select("input[name=execution]").attr("value")
            val eventId = casDoc.select("input[name=_eventId]").attr("value")
            
            Log.d(TAG, "CAS_LT: $casKey, execution: $execution, eventId: $eventId")
            
            // Step 3: 提交登录表单
            val formBody = FormBody.Builder()
                .add("model", "uplogin.jsp")
                .add("CAS_LT", casKey)
                .add("execution", execution)
                .add("_eventId", eventId.ifEmpty { "submit" })
                .add("username", username)
                .add("password", password)
                .add("button", "")
                .build()
            
            val loginRequest = Request.Builder()
                .url(CAS_LOGIN_URL)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .addHeader("Referer", CAS_LOGIN_URL)
                .post(formBody)
                .build()
            
            val loginResponse = httpClient.newCall(loginRequest).execute()
            val loginResponseUrl = loginResponse.request.url.toString()
            val loginHtml = loginResponse.body?.string() ?: ""
            
            Log.d(TAG, "Login response URL: $loginResponseUrl")
            
            // 检查是否登录失败（还在登录页面）
            if (loginResponseUrl.contains("passport.ustc.edu.cn/login")) {
                val errorDoc = Jsoup.parse(loginHtml)
                val errorMsg = errorDoc.select(".error-msg, .alert-danger, #msg").text()
                return@withContext LoginResult.Error(errorMsg.ifEmpty { "登录失败，请检查用户名和密码" })
            }
            
            // Step 4: 重新访问成绩页面确认登录成功
            val verifyRequest = Request.Builder()
                .url(GRADE_URL)
                .get()
                .build()
            
            val verifyResponse = httpClient.newCall(verifyRequest).execute()
            val verifyUrl = verifyResponse.request.url.toString()
            
            if (verifyUrl.contains("for-std/grade/sheet") && !verifyUrl.contains("login")) {
                Log.d(TAG, "Login successful")
                return@withContext LoginResult.Success(emptyList())
            } else {
                Log.d(TAG, "Login verification failed, URL: $verifyUrl")
                return@withContext LoginResult.Error("登录验证失败")
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error during login", e)
            return@withContext LoginResult.Error("网络错误: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during login", e)
            return@withContext LoginResult.Error("登录异常: ${e.message}")
        }
    }
    
    /**
     * 检查当前登录状态
     */
    suspend fun checkLoginStatus(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GRADE_URL)
                .get()
                .build()
            
            val response = httpClient.newCall(request).execute()
            val responseUrl = response.request.url.toString()
            
            responseUrl.contains("for-std/grade/sheet") && !responseUrl.contains("login")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking login status", e)
            false
        }
    }
    
    /**
     * 获取成绩页面 HTML
     */
    suspend fun fetchGradePage(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GRADE_URL)
                .get()
                .build()
            
            val response = httpClient.newCall(request).execute()
            val responseUrl = response.request.url.toString()
            
            if (responseUrl.contains("for-std/grade/sheet") && !responseUrl.contains("login")) {
                response.body?.string()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching grade page", e)
            null
        }
    }
}
