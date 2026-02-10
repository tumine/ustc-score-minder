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
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewLoginScreen(
    onLoginSuccess: (cookies: String) -> Unit,
    onLoginCancel: () -> Unit
) {
    val loginUrl = "https://passport.ustc.edu.cn/login?service=https://jw.ustc.edu.cn/ucas-sso/login"
    val successUrlPattern = "jw.ustc.edu.cn"
    
    var isLoading by remember { mutableStateOf(true) }
    var loadingProgress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf(loginUrl) }
    
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
                    
                    // 不清除 cookies，保留已有的登录会话
                    // 如果 cookies 仍然有效，WebView 会自动使用它们
                    
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
                                
                                // 检查是否登录成功（到达教务系统页面）
                                if (it.contains(successUrlPattern) && !it.contains("login")) {
                                    Log.d("WebViewLogin", "Login successful! URL: $it")
                                    
                                    // 获取 cookies
                                    val cookies = CookieManager.getInstance().getCookie(it) ?: ""
                                    Log.d("WebViewLogin", "Cookies: $cookies")
                                    
                                    onLoginSuccess(cookies)
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
