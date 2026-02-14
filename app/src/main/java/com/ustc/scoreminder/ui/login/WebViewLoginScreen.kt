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
    credentials: Pair<String, String>? = null
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
                                    // 如果有凭证，尝试自动填充
                                    credentials?.let { (u, p) ->
                                        injectAutoFillScript(view, u, p)
                                    }
                                }
                                // Case 2: On JW Portal Login Selection Page (Need to click Unified Auth)
                                else if (it.contains("jw.ustc.edu.cn") && !it.contains("sheet")) {
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
 *
 * USTC CAS 是 Angular SPA，表单由 JS 动态渲染，使用 ngModel 绑定。
 * 需要等待 Angular 渲染完成后再查找 input 元素。
 * 用户名: input[name="username"]
 * 密码: .passwordInput input 或 input[type="password"] 或 input[autocomplete="new-password"]
 */
private fun injectCredentialCaptureScript(webView: WebView?) {
    val js = """
        (function() {
            if (window._credentialCaptureInjected) return;
            window._credentialCaptureInjected = true;
            
            function findInputs() {
                // Angular SPA: 用户名通过 name="username" 定位
                var usernameInput = document.querySelector('input[name="username"]')
                    || document.querySelector('#username')
                    || document.querySelector('input[type="text"]');
                    
                // Angular SPA: 密码在 .passwordInput 容器内，或通过 type/autocomplete 定位
                var passwordInput = document.querySelector('.passwordInput input')
                    || document.querySelector('input[type="password"]')
                    || document.querySelector('input[autocomplete="new-password"]')
                    || document.querySelector('#password')
                    || document.querySelector('input[name="password"]');
                    
                return { username: usernameInput, password: passwordInput };
            }
            
            function captureAndSend() {
                var inputs = findInputs();
                if (inputs.username && inputs.password) {
                    var u = inputs.username.value;
                    var p = inputs.password.value;
                    if (u && p) {
                        AndroidBridge.captureCredentials(u, p);
                    }
                }
            }
            
            function checkError() {
                var errorToast = document.querySelector('.error-toast .error-msg');
                var bodyText = document.body ? document.body.innerText : '';
                if ((errorToast && errorToast.innerText) || 
                    bodyText.includes("用户名或密码错误") ||
                    bodyText.includes("认证失败")) {
                    console.log("Login error detected");
                    AndroidBridge.onLoginErrorDetected();
                }
            }
            
            // 定时检查（应对 Angular SPA 动态渲染和自动填充）
            setInterval(captureAndSend, 500);
            setInterval(checkError, 1000);
            
            // 监听输入事件
            ['input', 'change', 'blur', 'keyup'].forEach(function(evt) {
                document.addEventListener(evt, captureAndSend, true);
            });

            // 使用 MutationObserver 监听 Angular 动态渲染后绑定表单事件
            var formBound = false;
            var observer = new MutationObserver(function() {
                if (formBound) return;
                var forms = document.querySelectorAll('form');
                var buttons = document.querySelectorAll('button[type="submit"], button.login-button');
                if (forms.length > 0 || buttons.length > 0) {
                    formBound = true;
                    forms.forEach(function(form) {
                        form.addEventListener('submit', captureAndSend, true);
                    });
                    buttons.forEach(function(btn) {
                        btn.addEventListener('click', captureAndSend, true);
                    });
                }
            });
            observer.observe(document.body || document.documentElement, {
                childList: true, subtree: true
            });
        })();
    """.trimIndent()
    
    webView?.evaluateJavascript(js, null)
}

/**
 * 注入 JavaScript 脚本，自动填充用户名和密码
 *
 * USTC CAS Angular SPA 使用 ngModel 双向绑定，直接设置 element.value 不会
 * 更新 Angular 的内部模型。必须使用 HTMLInputElement.prototype.value 的原生 setter，
 * 然后派发 'input' 事件让 Angular 感知到值的变化。
 */
private fun injectAutoFillScript(webView: WebView?, u: String, p: String) {
    if (u.isBlank() || p.isBlank()) return
    
    // 转义用于 JS 字符串的特殊字符
    val safeU = u.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
    val safeP = p.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
    
    val js = """
        (function() {
            if (window._autoFillInjected) return;
            window._autoFillInjected = true;
            
            var attempts = 0;
            var maxAttempts = 40; // 20 seconds total

            // 使用原生 setter 设置值，绕过 Angular 的属性拦截
            // 这是让 Angular ngModel 正确感知值变化的关键
            var nativeSetter = Object.getOwnPropertyDescriptor(
                window.HTMLInputElement.prototype, 'value'
            ).set;
            
            function setNativeValue(element, value) {
                nativeSetter.call(element, value);
                // 派发 input 事件 - Angular 监听此事件来更新 ngModel
                element.dispatchEvent(new Event('input', { bubbles: true }));
                // 额外派发 change 事件确保校验触发
                element.dispatchEvent(new Event('change', { bubbles: true }));
            }
            
            function tryAutoFill() {
                // 等待 Angular 渲染完成: <app-root> 内部必须有内容
                var appRoot = document.querySelector('app-root');
                if (!appRoot || !appRoot.innerHTML || appRoot.innerHTML.trim().length < 100) {
                    return false;
                }

                // Angular SPA 选择器:
                // 用户名: input[name="username"]
                var usernameInput = document.querySelector('input[name="username"]')
                    || document.querySelector('#username')
                    || document.querySelector('input[type="text"]');
                    
                // 密码: .passwordInput 容器内的 input, 或 type="password"
                var passwordInput = document.querySelector('.passwordInput input')
                    || document.querySelector('input[type="password"]')
                    || document.querySelector('input[autocomplete="new-password"]')
                    || document.querySelector('#password')
                    || document.querySelector('input[name="password"]');
                
                if (!usernameInput || !passwordInput) {
                    return false;
                }

                console.log("Auto-fill: Found username and password inputs");
                
                // 使用原生 setter 设置值（让 Angular ngModel 正确更新）
                setNativeValue(usernameInput, "$safeU");
                setNativeValue(passwordInput, "$safeP");

                // 派发 focus/blur 事件确保表单验证
                ['focus', 'blur'].forEach(function(evt) {
                    usernameInput.dispatchEvent(new Event(evt, { bubbles: true }));
                    passwordInput.dispatchEvent(new Event(evt, { bubbles: true }));
                });

                // 同时通知 AndroidBridge 记录凭证
                try { AndroidBridge.captureCredentials("$safeU", "$safeP"); } catch(e) {}

                console.log("Auto-fill: Values set, looking for login button...");
                
                // 延迟点击登录按钮，等待 Angular 处理绑定更新
                setTimeout(function() {
                    var loginBtn = document.querySelector('button.login-button')
                        || document.querySelector('button[type="submit"]')
                        || document.querySelector('input[type="submit"]');
                    if (loginBtn) {
                        console.log("Auto-fill: Clicking login button");
                        loginBtn.click();
                    } else {
                        console.log("Auto-fill: Login button not found, trying form submit");
                        var form = document.querySelector('form');
                        if (form) {
                            form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
                        }
                    }
                }, 800);
                
                return true;
            }

            // 1. 使用 MutationObserver 监听 Angular 渲染
            var observer = new MutationObserver(function() {
                if (tryAutoFill()) {
                    observer.disconnect();
                    clearInterval(intervalId);
                }
            });
            observer.observe(document.body || document.documentElement, {
                childList: true, subtree: true
            });

            // 2. 同时用定时器作为后备
            var intervalId = setInterval(function() {
                attempts++;
                if (tryAutoFill() || attempts >= maxAttempts) {
                    clearInterval(intervalId);
                    observer.disconnect();
                }
            }, 500);
            
            // 3. 立即尝试一次
            tryAutoFill();
        })();
    """.trimIndent()
    
    webView?.evaluateJavascript(js, null)
}

/**
 * 注入脚本点击"统一身份认证登录"按钮
 * 用于 jw.ustc.edu.cn 首页跳转
 */
private fun injectAutoLoginClickScript(webView: WebView?) {
    val js = """
        (function() {
            function findAndClickButton() {
                // Find button by text content or specific class
                var buttons = Array.from(document.querySelectorAll('a, button, div.btn'));
                var targetBtn = buttons.find(el => 
                    el.innerText && (el.innerText.includes("统一身份认证") || el.innerText.includes("Unified Identity"))
                );
                
                if (targetBtn) {
                    console.log("Found Unified Auth button, clicking...");
                    targetBtn.click();
                    return true;
                }
                return false;
            }
            
            if (!findAndClickButton()) {
                // Retry a few times if not found immediately
                var attempts = 0;
                var interval = setInterval(function() {
                    attempts++;
                    if (findAndClickButton() || attempts > 10) {
                        clearInterval(interval);
                    }
                }, 500);
            }
        })();
    """.trimIndent()
    
    webView?.evaluateJavascript(js, null)
}