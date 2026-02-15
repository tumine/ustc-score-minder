package com.ustc.scoreminder.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 后台 WebView 认证器
 * 用于在后台（如 WorkManager）中执行静默登录
 */
@Singleton
class BackgroundWebViewAuthenticator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BgWebViewAuth"
        private const val LOGIN_URL = "https://jw.ustc.edu.cn/for-std/grade/sheet" // 直接访问成绩页触发登录
        private const val SUCCESS_URL_PATTERN = "for-std/grade/sheet"
        private const val TIMEOUT_MS = 60000L // 60秒超时
    }

    /**
     * 执行静默登录
     * @return Result<Unit> 成功返回 Success，失败返回 Failure
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun performLogin(username: String, password: String): Result<Unit> {
        if (username.isBlank() || password.isBlank()) {
            return Result.failure(IllegalArgumentException("用户名或密码为空"))
        }

        return try {
            withTimeout(TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val mainHandler = Handler(Looper.getMainLooper())
                    var webView: WebView? = null
                    
                    mainHandler.post {
                        var isResumed = false
                        
                        fun resumeExample(result: Result<Unit>) {
                            if (!isResumed && continuation.isActive) {
                                isResumed = true
                                try {
                                    webView?.stopLoading()
                                    webView?.destroy()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error destroying WebView", e)
                                }
                                webView = null
                                continuation.resume(result)
                            }
                        }

                        try {
                            Log.d(TAG, "Starting background WebView login...")
                            
                            // 强制清除 Cookie，确保重新登录
                            try {
                                CookieManager.getInstance().removeAllCookies(null)
                                CookieManager.getInstance().flush()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to clear cookies", e)
                            }

                            webView = WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(1, 1) // 最小尺寸
                                
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                }

                                // 添加 JS 接口接收错误回调
                                addJavascriptInterface(object {
                                    @JavascriptInterface
                                    fun captureCredentials(u: String, p: String) {
                                        // 这里不需要捕获，因为我们是用来登录的，但是为了兼容脚本，保留接口
                                        Log.v(TAG, "JS captured credentials (expected)")
                                    }

                                    @JavascriptInterface
                                    fun onLoginErrorDetected() {
                                        Log.e(TAG, "Login error detected by JS")
                                        mainHandler.post {
                                            resumeExample(Result.failure(Exception("用户名或密码错误")))
                                        }
                                    }
                                }, "AndroidBridge")

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        Log.d(TAG, "Page finished: $url")
                                        
                                        if (url != null) {
                                            // 1. USTC CAS 登录页
                                            if (url.contains("id.ustc.edu.cn") || url.contains("passport.ustc.edu.cn")) {
                                                Log.d(TAG, "On login page, injecting auto-fill script")
                                                val script = LoginScriptUtils.getAutoFillScript(username, password)
                                                // 同时也注入捕获脚本以启用错误检测
                                                val captureScript = LoginScriptUtils.getCredentialCaptureScript()
                                                view?.evaluateJavascript(captureScript, null)
                                                view?.evaluateJavascript(script, null)
                                            }
                                            // 2. 教务系统首页（可能需要点击登录）
                                            else if (url.contains("jw.ustc.edu.cn") && url.contains("login")) {
                                                Log.d(TAG, "On JW portal, injecting auto-click script")
                                                view?.evaluateJavascript(LoginScriptUtils.getAutoLoginClickScript(), null)
                                            }
                                            // 3. 登录成功
                                            else if (url.contains(SUCCESS_URL_PATTERN) && !url.contains("login")) {
                                                Log.i(TAG, "Login successful! URL: $url")
                                                resumeExample(Result.success(Unit))
                                            }
                                        }
                                    }

                                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                        super.onReceivedError(view, request, error)
                                        if (request?.isForMainFrame == true) {
                                            Log.w(TAG, "WebView error: ${error?.description}")
                                            // 不立即失败，有些错误可能是暂时的或非致命的
                                        }
                                    }
                                }
                                
                                loadUrl(LOGIN_URL)
                            }
                            
                            // 设置超时处理（在协程的 withTimeout 之外的双重保障，或者用于处理页面卡死）
                            mainHandler.postDelayed({
                                if (!isResumed) {
                                    Log.w(TAG, "Login timed out (internal)")
                                    resumeExample(Result.failure(Exception("登录超时")))
                                }
                            }, TIMEOUT_MS)

                        } catch (e: Exception) {
                            Log.e(TAG, "Error initializing background WebView", e)
                            resumeExample(Result.failure(e))
                        }
                    }
                    
                    continuation.invokeOnCancellation {
                        mainHandler.post {
                            try {
                                webView?.stopLoading()
                                webView?.destroy()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error cleaning up WebView", e)
                            }
                            webView = null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
