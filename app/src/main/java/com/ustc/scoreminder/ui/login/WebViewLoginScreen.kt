package com.ustc.scoreminder.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ustc.scoreminder.data.remote.LoginScriptUtils
import com.ustc.scoreminder.domain.model.VerificationCodeMethod

/**
 * WebView 登录屏幕
 * 使用系统 WebView 处理复杂的 CAS 认证流程（Angular SPA）
 * 通过 JavaScript 注入捕获用户输入的用户名和密码
 *
 * USTC CAS 登录页面 (id.ustc.edu.cn) 是一个 Angular 单页应用，
 * 使用 Ionic + NG-ZORRO 组件库，表单通过 ngModel 双向绑定。
 * 自动填充需要使用 HTMLInputElement 原生 setter + input 事件。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewLoginScreen(
    onLoginSuccess: (username: String, password: String) -> Unit,
    onLoginCancel: () -> Unit,
    onLoginError: () -> Unit = {},
    credentials: Pair<String, String>? = null,
    verificationCodeMethod: VerificationCodeMethod = VerificationCodeMethod.SMS
) {
    val loginUrl = "https://jw.ustc.edu.cn/for-std/grade/sheet"
    val successUrlPattern = "jw.ustc.edu.cn"
    
    var isLoading by remember { mutableStateOf(true) }
    var loadingProgress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf(loginUrl) }
    
    // 用于存储 JS 捕获的凭证
    val credentialsHolder = remember {
        object {
            @Volatile var username: String = ""
            @Volatile var password: String = ""
        }
    }
    
    // 如果有传入凭证，初始化 holder
    LaunchedEffect(credentials) {
        credentials?.let {
            credentialsHolder.username = it.first
            credentialsHolder.password = it.second
        }
    }
    
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部进度条
        if (isLoading) {
            LinearProgressIndicator(
                progress = { loadingProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        
        // 标题栏
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "统一身份认证登录",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                TextButton(onClick = onLoginCancel) {
                    Text("取消")
                }
            }
        }
        
        // WebView
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            factory = { context ->
                // 强制清除 Cookie，确保用户必须手动输入账号密码，以便我们捕获凭证
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = true
                        allowContentAccess = true
                        allowFileAccess = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    
                    // 添加 JavaScript 接口用于接收捕获的凭证
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun captureCredentials(username: String, password: String) {
                            Log.d("WebViewLogin", "Credentials captured for user: $username")
                            credentialsHolder.username = username
                            credentialsHolder.password = password
                        }

                        @JavascriptInterface
                        fun onLoginErrorDetected() {
                            Log.d("WebViewLogin", "Login error detected by JS")
                            // 必须在主线程执行回调
                            post {
                                onLoginError()
                            }
                        }
                    }, "AndroidBridge")
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            isLoading = true
                            url?.let { currentUrl = it }
                            Log.d("WebViewLogin", "Page started: $url")
                        }
                        
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                            
                            url?.let {
                                currentUrl = it
                                Log.d("WebViewLogin", "Page finished: $it")
                                
                                // Case 1: On USTC CAS Login Page (Angular SPA)
                                if (it.contains("id.ustc.edu.cn") || it.contains("passport.ustc.edu.cn")) {
                                    injectCredentialCaptureScript(view)
                                    injectSecondFactorAutoRequestScript(view, verificationCodeMethod)
                                    // 如果有凭证，尝试自动填充
                                    credentials?.let { (u, p) ->
                                        injectAutoFillScript(view, u, p)
                                    }
                                }
                                // Case 2: On JW Portal Login Page - auto-click "统一身份认证登录" button
                                else if (it.contains("jw.ustc.edu.cn") && it.contains("login")) {
                                    Log.d("WebViewLogin", "Detected jw login page, auto-clicking CAS login button")
                                    injectAutoLoginClickScript(view)
                                }
                                
                                // 检查是否登录成功（到达教务系统页面）
                                if (it.contains(successUrlPattern) && !it.contains("login")) {
                                    Log.d("WebViewLogin", "Login successful! URL: $it")
                                    Log.d("WebViewLogin", "Captured username: ${credentialsHolder.username}")
                                    
                                    onLoginSuccess(
                                        credentialsHolder.username,
                                        credentialsHolder.password
                                    )
                                }
                            }
                        }
                        
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            Log.d("WebViewLogin", "Override URL loading: $url")
                            
                            // 检查是否是登录成功后的重定向
                            if (url.contains(successUrlPattern) && !url.contains("login")) {
                                Log.d("WebViewLogin", "Redirecting to success URL: $url")
                            }
                            
                            // 让 WebView 处理所有 URL
                            return false
                        }
                        
                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            Log.e("WebViewLogin", "Error: ${error?.description}, URL: ${request?.url}")
                        }
                    }
                    
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            loadingProgress = newProgress
                        }
                    }
                    
                    loadUrl(loginUrl)
                }
            },
            update = { webView ->
                // WebView update logic if needed
            }
        )
    }
}

/**
 * 注入 JavaScript 脚本，在用户提交登录表单时捕获用户名和密码
 */
private fun injectCredentialCaptureScript(webView: WebView?) {
    val js = LoginScriptUtils.getCredentialCaptureScript()
    webView?.evaluateJavascript(js, null)
}

/**
 * 注入 JavaScript 脚本，自动填充用户名和密码
 */
private fun injectAutoFillScript(webView: WebView?, u: String, p: String) {
    if (u.isBlank() || p.isBlank()) return
    val js = LoginScriptUtils.getAutoFillScript(u, p)
    webView?.evaluateJavascript(js, null)
}

/** 注入二次身份验证验证码自动请求脚本。 */
private fun injectSecondFactorAutoRequestScript(
    webView: WebView?,
    verificationCodeMethod: VerificationCodeMethod
) {
    webView?.evaluateJavascript(
        LoginScriptUtils.getSecondFactorAutoRequestScript(verificationCodeMethod),
        null
    )
}

/**
 * 注入脚本点击"统一身份认证登录"按钮
 */
private fun injectAutoLoginClickScript(webView: WebView?) {
    val js = LoginScriptUtils.getAutoLoginClickScript()
    webView?.evaluateJavascript(js, null)
}
