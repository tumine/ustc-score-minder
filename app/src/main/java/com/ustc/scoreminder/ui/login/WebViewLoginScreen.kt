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

/**
 * WebView 登录屏幕
 * 使用系统 WebView 处理复杂的 CAS 认证流程
 * 通过 JavaScript 注入捕获用户输入的用户名和密码
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewLoginScreen(
    onLoginSuccess: (username: String, password: String) -> Unit,
    onLoginCancel: () -> Unit
) {
    val loginUrl = "https://passport.ustc.edu.cn/login?service=https://jw.ustc.edu.cn/ucas-sso/login"
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
                        userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
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
                                
                                // 在登录页面注入 JS 捕获用户名和密码
                                if (it.contains("passport.ustc.edu.cn")) {
                                    injectCredentialCaptureScript(view)
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
    val js = """
        (function() {
            if (window._credentialCaptureInjected) return;
            window._credentialCaptureInjected = true;
            
            function captureAndSend() {
                var usernameInput = document.querySelector('#username') 
                    || document.querySelector('input[name="username"]')
                    || document.querySelector('input[type="text"]');
                var passwordInput = document.querySelector('#password') 
                    || document.querySelector('input[name="password"]')
                    || document.querySelector('input[type="password"]');
                    
                if (usernameInput && passwordInput && usernameInput.value && passwordInput.value) {
                    AndroidBridge.captureCredentials(usernameInput.value, passwordInput.value);
                }
            }
            
            // 拦截表单提交
            var forms = document.querySelectorAll('form');
            forms.forEach(function(form) {
                form.addEventListener('submit', captureAndSend, true);
            });
            
            // 拦截登录按钮点击
            var buttons = document.querySelectorAll('button[type="submit"], input[type="submit"], #login, .login-btn');
            buttons.forEach(function(btn) {
                btn.addEventListener('click', captureAndSend, true);
            });
            
            // 拦截 Enter 键提交
            document.addEventListener('keydown', function(e) {
                if (e.key === 'Enter' || e.keyCode === 13) {
                    captureAndSend();
                }
            }, true);
        })();
    """.trimIndent()
    
    webView?.evaluateJavascript(js, null)
}
