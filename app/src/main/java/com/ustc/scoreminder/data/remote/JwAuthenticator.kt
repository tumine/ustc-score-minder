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
 * 支持新版 CAS 系统（SPA + API 方式）
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
        private const val CAS_LOGIN_URL = "https://id.ustc.edu.cn/cas/login"
        private const val CAS_LOGIN_API = "https://id.ustc.edu.cn/cas/login"
    }

    sealed class LoginResult {
        data class Success(val cookies: List<Cookie>) : LoginResult()
        data class Error(val message: String) : LoginResult()
        object NeedLogin : LoginResult()
    }
    
    /**
     * 尝试登录并返回结果
     * 支持新版 CAS 系统的登录流程
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
            
            // Step 2: 获取 CAS 登录页面并解析加密密钥
            val casLoginRequest = Request.Builder()
                .url(CAS_LOGIN_URL)
                .addHeader("Referer", LOGIN_URL)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .get()
                .build()
            
            val casResponse = httpClient.newCall(casLoginRequest).execute()
            val casHtml = casResponse.body?.string() ?: ""
            val casDoc = Jsoup.parse(casHtml)
            
            // 尝试获取新版系统的加密密钥
            val loginCrypto = casDoc.select("p#login-croypto").text().ifEmpty { 
                casDoc.select("#login-croypto").text() 
            }
            
            // 同时尝试获取旧版系统的表单字段（作为后备）
            val casKey = casDoc.select("input[name=CAS_LT]").attr("value")
            val execution = casDoc.select("input[name=execution]").attr("value")
            val eventId = casDoc.select("input[name=_eventId]").attr("value")
            val ltValue = casDoc.select("input[name=lt]").attr("value")
            
            Log.d(TAG, "login-croypto: ${loginCrypto.take(20)}..., CAS_LT: $casKey, lt: $ltValue")
            
            // Step 3: 准备登录参数
            val encryptedPassword = if (loginCrypto.isNotEmpty()) {
                // 新版系统：使用加密密钥加密密码
                CryptoUtils.encryptPassword(password, loginCrypto)
            } else {
                // 旧版系统：直接使用密码
                password
            }
            
            // 构建表单数据
            val formBodyBuilder = FormBody.Builder()
                .add("username", username)
                .add("password", encryptedPassword)
            
            // 添加可能存在的表单字段
            if (casKey.isNotEmpty()) {
                formBodyBuilder.add("CAS_LT", casKey)
            }
            if (ltValue.isNotEmpty()) {
                formBodyBuilder.add("lt", ltValue)
            }
            if (execution.isNotEmpty()) {
                formBodyBuilder.add("execution", execution)
            }
            formBodyBuilder.add("_eventId", eventId.ifEmpty { "submit" })
            formBodyBuilder.add("model", "uplogin.jsp")
            formBodyBuilder.add("button", "")
            
            val formBody = formBodyBuilder.build()
            
            Log.d(TAG, "Submitting login form...")
            
            // Step 4: 提交登录表单
            val loginRequest = Request.Builder()
                .url(CAS_LOGIN_API)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .addHeader("Referer", CAS_LOGIN_URL)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .addHeader("Origin", "https://id.ustc.edu.cn")
                .post(formBody)
                .build()
            
            val loginResponse = httpClient.newCall(loginRequest).execute()
            val loginResponseUrl = loginResponse.request.url.toString()
            val loginHtml = loginResponse.body?.string() ?: ""
            
            Log.d(TAG, "Login response URL: $loginResponseUrl")
            
            // 检查是否登录失败（还在登录页面）
            if (loginResponseUrl.contains("id.ustc.edu.cn/cas/login") || 
                loginResponseUrl.contains("id.ustc.edu.cn") ||
                loginResponseUrl.contains("passport.ustc.edu.cn")) {
                val errorDoc = Jsoup.parse(loginHtml)
                // 尝试多种错误消息选择器
                val errorMsg = errorDoc.select(".error-msg, .alert-danger, #msg, .login-error, .tip-error").text()
                    .ifEmpty { 
                        // 检查是否包含错误代码
                        val errorCode = errorDoc.select("#login-error-code").text()
                        if (errorCode.isNotEmpty()) {
                            "认证失败，错误代码: $errorCode"
                        } else {
                            "登录失败，请检查用户名和密码"
                        }
                    }
                Log.d(TAG, "Login failed: $errorMsg")
                return@withContext LoginResult.Error(errorMsg)
            }
            
            // Step 5: 重新访问成绩页面确认登录成功
            val verifyRequest = Request.Builder()
                .url(GRADE_URL)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .get()
                .build()
            
            val verifyResponse = httpClient.newCall(verifyRequest).execute()
            val verifyUrl = verifyResponse.request.url.toString()
            
            if (verifyUrl.contains("for-std/grade/sheet") && !verifyUrl.contains("login")) {
                Log.d(TAG, "Login successful")
                return@withContext LoginResult.Success(emptyList())
            } else {
                Log.d(TAG, "Login verification failed, URL: $verifyUrl")
                return@withContext LoginResult.Error("登录验证失败，请重试")
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
            Log.d(TAG, "Checking login status...")
            val request = Request.Builder()
                .url(GRADE_URL)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .get()
                .build()
            
            val response = httpClient.newCall(request).execute()
            val responseUrl = response.request.url.toString()
            
            val isLoggedIn = responseUrl.contains("for-std/grade/sheet") && !responseUrl.contains("login")
            Log.d(TAG, "Login status check - Response URL: $responseUrl, isLoggedIn: $isLoggedIn")
            
            isLoggedIn
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
            Log.d(TAG, "Fetching grade page...")
            val request = Request.Builder()
                .url(GRADE_URL)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .get()
                .build()
            
            val response = httpClient.newCall(request).execute()
            val responseUrl = response.request.url.toString()
            
            Log.d(TAG, "Grade page fetch - Response URL: $responseUrl, Status: ${response.code}")
            
            if (responseUrl.contains("for-std/grade/sheet") && !responseUrl.contains("login")) {
                val html = response.body?.string()
                Log.d(TAG, "Grade page HTML length: ${html?.length ?: 0}")
                html
            } else {
                Log.w(TAG, "Grade page fetch failed - redirected to login or wrong URL")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching grade page", e)
            null
        }
    }
}
