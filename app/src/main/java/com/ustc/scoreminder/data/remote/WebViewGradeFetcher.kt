package com.ustc.scoreminder.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 使用 WebView 获取成绩页面内容
 * 
 * USTC 教务系统的成绩页面是一个 Vue.js SPA，需要通过 WebView
 * 执行 JavaScript 才能获取渲染后的成绩数据。
 */
@Singleton
class WebViewGradeFetcher @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WebViewGradeFetcher"
        private const val GRADE_URL = "https://jw.ustc.edu.cn/for-std/grade/sheet"
        private const val PAGE_LOAD_TIMEOUT_MS = 30000L
        private const val RENDER_WAIT_MS = 3000L // 等待 Vue.js 渲染完成
    }

    /**
     * 获取成绩页面的完整 HTML（包含 JavaScript 渲染后的内容）
     * 
     * @return 渲染后的 HTML，如果失败则返回 null
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchRenderedGradePage(): String? = withTimeout(PAGE_LOAD_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val mainHandler = Handler(Looper.getMainLooper())
            
            mainHandler.post {
                var webView: WebView? = null
                var isResumed = false
                
                fun resumeOnce(result: String?) {
                    if (!isResumed && continuation.isActive) {
                        isResumed = true
                        webView?.destroy()
                        webView = null
                        continuation.resume(result)
                    }
                }
                
                try {
                    webView = WebView(context).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }
                        
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                
                                url?.let {
                                    Log.d(TAG, "Page finished: $it")
                                    
                                    // 检查是否被重定向到登录页面
                                    if (it.contains("login") || it.contains("passport")) {
                                        Log.w(TAG, "Redirected to login page, session may have expired")
                                        resumeOnce(null)
                                        return
                                    }
                                    
                                    // 检查是否到达成绩页面
                                    if (it.contains("for-std/grade/sheet")) {
                                        // 等待 Vue.js 渲染完成后提取 HTML
                                        mainHandler.postDelayed({
                                            extractHtmlContent(view)
                                        }, RENDER_WAIT_MS)
                                    }
                                }
                            }
                            
                            private fun extractHtmlContent(view: WebView?) {
                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                        // 尝试获取表格内容
                                        var tableHtml = document.querySelector('.el-table__body-wrapper')?.innerHTML 
                                            || document.querySelector('.my-table')?.innerHTML 
                                            || document.querySelector('table')?.outerHTML;
                                        
                                        if (tableHtml && tableHtml.trim()) {
                                            return tableHtml;
                                        }
                                        
                                        // 如果没有找到表格，返回整个页面
                                        return document.documentElement.outerHTML;
                                    })()
                                    """.trimIndent()
                                ) { result ->
                                    val html = result?.removeSurrounding("\"")
                                        ?.replace("\\n", "\n")
                                        ?.replace("\\\"", "\"")
                                        ?.replace("\\u003C", "<")
                                        ?.replace("\\u003E", ">")
                                    
                                    Log.d(TAG, "Extracted HTML length: ${html?.length ?: 0}")
                                    resumeOnce(html)
                                }
                            }
                            
                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                // 只处理主框架的错误
                                if (request?.isForMainFrame == true) {
                                    Log.e(TAG, "Error loading page: ${error?.description}")
                                    resumeOnce(null)
                                }
                            }
                        }
                        
                        // 使用现有的 cookies（从 WebView 登录中获取的）
                        Log.d(TAG, "Loading grade page with existing cookies...")
                        loadUrl(GRADE_URL)
                    }
                    
                    continuation.invokeOnCancellation {
                        mainHandler.post {
                            webView?.destroy()
                            webView = null
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating WebView", e)
                    resumeOnce(null)
                }
            }
        }
    }

    /**
     * 使用 JavaScript 直接从 Vue.js 组件中提取成绩数据
     * 这是更可靠的方法，可以直接获取 JSON 数据
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchGradeDataViaJs(): List<GradeData>? = withTimeout(PAGE_LOAD_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val mainHandler = Handler(Looper.getMainLooper())
            
            mainHandler.post {
                var webView: WebView? = null
                var isResumed = false
                
                fun resumeOnce(result: List<GradeData>?) {
                    if (!isResumed && continuation.isActive) {
                        isResumed = true
                        webView?.destroy()
                        webView = null
                        continuation.resume(result)
                    }
                }
                
                try {
                    webView = WebView(context).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }
                        
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                
                                url?.let {
                                    Log.d(TAG, "Page finished (JS extraction): $it")
                                    
                                    if (it.contains("login") || it.contains("passport")) {
                                        Log.w(TAG, "Redirected to login")
                                        resumeOnce(null)
                                        return
                                    }
                                    
                                    if (it.contains("for-std/grade/sheet")) {
                                        mainHandler.postDelayed({
                                            extractGradeDataViaJs(view)
                                        }, RENDER_WAIT_MS)
                                    }
                                }
                            }
                            
                            private fun extractGradeDataViaJs(view: WebView?) {
                                // 尝试多种方式提取成绩数据
                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                        var grades = [];
                                        
                                        // 方法1: 从表格行提取
                                        var rows = document.querySelectorAll('.el-table__body tr, table.my-table tbody tr, table tbody tr');
                                        
                                        rows.forEach(function(row) {
                                            var cells = row.querySelectorAll('td');
                                            if (cells.length >= 5) {
                                                var grade = {
                                                    semester: cells[0]?.innerText?.trim() || '',
                                                    courseId: cells[1]?.innerText?.trim() || '',
                                                    courseName: cells[2]?.innerText?.trim() || '',
                                                    credit: cells[3]?.innerText?.trim() || '0',
                                                    score: cells[4]?.innerText?.trim() || '',
                                                    gradePoint: cells[5]?.innerText?.trim() || '',
                                                    courseType: cells[6]?.innerText?.trim() || '',
                                                    examType: cells[7]?.innerText?.trim() || ''
                                                };
                                                if (grade.courseId && grade.courseName) {
                                                    grades.push(grade);
                                                }
                                            }
                                        });
                                        
                                        return JSON.stringify(grades);
                                    })()
                                    """.trimIndent()
                                ) { result ->
                                    try {
                                        val jsonStr = result?.removeSurrounding("\"")
                                            ?.replace("\\\"", "\"")
                                            ?.replace("\\n", "")
                                        
                                        Log.d(TAG, "Extracted grades JSON: ${jsonStr?.take(200)}...")
                                        
                                        if (jsonStr.isNullOrBlank() || jsonStr == "[]") {
                                            resumeOnce(emptyList())
                                            return@evaluateJavascript
                                        }
                                        
                                        // 简单的 JSON 解析
                                        val grades = parseGradesJson(jsonStr)
                                        Log.d(TAG, "Parsed ${grades.size} grades")
                                        resumeOnce(grades)
                                        
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error parsing grades JSON", e)
                                        resumeOnce(null)
                                    }
                                }
                            }
                        }
                        
                        loadUrl(GRADE_URL)
                    }
                    
                    continuation.invokeOnCancellation {
                        mainHandler.post {
                            webView?.destroy()
                            webView = null
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating WebView for JS extraction", e)
                    resumeOnce(null)
                }
            }
        }
    }
    
    /**
     * 简单的 JSON 解析器，解析成绩数组
     */
    private fun parseGradesJson(json: String): List<GradeData> {
        val grades = mutableListOf<GradeData>()
        
        // 使用正则表达式提取 JSON 对象
        val objectPattern = Regex("""\{[^{}]*\}""")
        val matches = objectPattern.findAll(json)
        
        for (match in matches) {
            try {
                val obj = match.value
                val semester = extractJsonValue(obj, "semester")
                val courseId = extractJsonValue(obj, "courseId")
                val courseName = extractJsonValue(obj, "courseName")
                val credit = extractJsonValue(obj, "credit")
                val score = extractJsonValue(obj, "score")
                val gradePoint = extractJsonValue(obj, "gradePoint")
                val courseType = extractJsonValue(obj, "courseType")
                val examType = extractJsonValue(obj, "examType")
                
                if (courseId.isNotEmpty() && courseName.isNotEmpty()) {
                    grades.add(GradeData(
                        semester = semester,
                        courseId = courseId,
                        courseName = courseName,
                        credit = credit.toFloatOrNull() ?: 0f,
                        score = score,
                        gradePoint = gradePoint.toFloatOrNull(),
                        courseType = courseType.ifEmpty { null },
                        examType = examType.ifEmpty { null }
                    ))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing grade object: ${match.value}", e)
            }
        }
        
        return grades
    }
    
    private fun extractJsonValue(json: String, key: String): String {
        val pattern = Regex(""""$key"\s*:\s*"([^"]*?)"""")
        return pattern.find(json)?.groupValues?.getOrNull(1) ?: ""
    }
    
    /**
     * 成绩数据类
     */
    data class GradeData(
        val semester: String,
        val courseId: String,
        val courseName: String,
        val credit: Float,
        val score: String,
        val gradePoint: Float?,
        val courseType: String?,
        val examType: String?
    )
}
