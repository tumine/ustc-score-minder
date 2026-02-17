package com.ustc.scoreminder.data.remote

/**
 * 登录脚本工具类
 * 存放用于 WebView 登录的 JavaScript 脚本
 */
object LoginScriptUtils {

    /**
     * 注入 JavaScript 脚本，在用户提交登录表单时捕获用户名和密码
     */
    fun getCredentialCaptureScript(): String {
        return """
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
                            try { AndroidBridge.captureCredentials(u, p); } catch(e) {}
                        }
                    }
                }
                
                function checkError() {
                    var errorToast = document.querySelector('.error-toast .error-msg');
                    var bodyText = document.body ? document.body.innerText : '';
                    if ((errorToast && errorToast.innerText) || 
                        bodyText.includes("用户名或密码错误，请确认后重新输入") ||
                        bodyText.includes("用户名或密码错误") ||
                        bodyText.includes("认证失败")) {
                        console.log("Login error detected");
                        try { AndroidBridge.onLoginErrorDetected(); } catch(e) {}
                    }
                }
                
                // 定时检查
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
    }

    /**
     * 注入 JavaScript 脚本，自动填充用户名和密码
     */
    fun getAutoFillScript(u: String, p: String): String {
        if (u.isBlank() || p.isBlank()) return ""
        
        // 转义用于 JS 字符串的特殊字符
        val safeU = u.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
        val safeP = p.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
        
        return """
            (function() {
                if (window._autoFillInjected) return;
                window._autoFillInjected = true;
                
                var attempts = 0;
                var maxAttempts = 40; // 20 seconds total

                // 使用原生 setter 设置值，绕过 Angular 的属性拦截
                var nativeSetter = Object.getOwnPropertyDescriptor(
                    window.HTMLInputElement.prototype, 'value'
                ).set;
                
                function setNativeValue(element, value) {
                    nativeSetter.call(element, value);
                    element.dispatchEvent(new Event('input', { bubbles: true }));
                    element.dispatchEvent(new Event('change', { bubbles: true }));
                }
                
                function tryAutoFill() {
                    // 等待 Angular 渲染完成
                    var appRoot = document.querySelector('app-root');
                    if (!appRoot || !appRoot.innerHTML || appRoot.innerHTML.trim().length < 100) {
                        return false;
                    }

                    // Angular SPA 选择器
                    var usernameInput = document.querySelector('input[name="username"]')
                        || document.querySelector('#username')
                        || document.querySelector('input[type="text"]');
                        
                    var passwordInput = document.querySelector('.passwordInput input')
                        || document.querySelector('input[type="password"]')
                        || document.querySelector('input[autocomplete="new-password"]')
                        || document.querySelector('#password')
                        || document.querySelector('input[name="password"]');
                    
                    if (!usernameInput || !passwordInput) {
                        return false;
                    }

                    console.log("Auto-fill: Found username and password inputs");
                    
                    setNativeValue(usernameInput, "$safeU");
                    setNativeValue(passwordInput, "$safeP");

                    ['focus', 'blur'].forEach(function(evt) {
                        usernameInput.dispatchEvent(new Event(evt, { bubbles: true }));
                        passwordInput.dispatchEvent(new Event(evt, { bubbles: true }));
                    });

                    // 同时通知 AndroidBridge 记录凭证
                    try { AndroidBridge.captureCredentials("$safeU", "$safeP"); } catch(e) {}

                    console.log("Auto-fill: Values set, looking for login button...");
                    
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

                var observer = new MutationObserver(function() {
                    if (tryAutoFill()) {
                        observer.disconnect();
                        clearInterval(intervalId);
                    }
                });
                observer.observe(document.body || document.documentElement, {
                    childList: true, subtree: true
                });

                var intervalId = setInterval(function() {
                    attempts++;
                    if (tryAutoFill() || attempts >= maxAttempts) {
                        clearInterval(intervalId);
                        observer.disconnect();
                    }
                }, 500);
                
                tryAutoFill();
            })();
        """.trimIndent()
    }

    /**
     * 注入脚本点击"统一身份认证登录"按钮
     */
    fun getAutoLoginClickScript(): String {
        return """
            (function() {
                function findAndClickButton() {
                    // Strategy 1: Find by text content "统一身份认证"
                    var elements = Array.from(document.querySelectorAll('a, button, div.btn, span, input[type="button"]'));
                    var targetBtn = elements.find(function(el) {
                        var text = (el.innerText || el.textContent || '').trim();
                        return text.indexOf('统一身份认证') !== -1 || text.indexOf('Unified Identity') !== -1;
                    });
                    
                    if (targetBtn) {
                        console.log("Found Unified Auth button by text, clicking...");
                        targetBtn.click();
                        return true;
                    }
                    
                    // Strategy 2: Find link pointing to CAS / passport / id.ustc.edu.cn
                    var casLink = document.querySelector('a[href*="passport.ustc.edu.cn"]')
                        || document.querySelector('a[href*="id.ustc.edu.cn"]')
                        || document.querySelector('a[href*="cas/login"]')
                        || document.querySelector('a[href*="ucas-sso"]');
                    if (casLink) {
                        console.log("Found CAS link by href, clicking...");
                        casLink.click();
                        return true;
                    }
                    
                    // Strategy 3: Try window.location redirect as last resort
                    var allLinks = document.querySelectorAll('a[href]');
                    for (var i = 0; i < allLinks.length; i++) {
                        var href = allLinks[i].getAttribute('href') || '';
                        if (href.indexOf('passport') !== -1 || href.indexOf('id.ustc') !== -1 || href.indexOf('cas') !== -1) {
                            console.log("Found CAS-related link, clicking: " + href);
                            allLinks[i].click();
                            return true;
                        }
                    }
                    
                    return false;
                }
                
                // Try immediately
                if (!findAndClickButton()) {
                    var attempts = 0;
                    var interval = setInterval(function() {
                        attempts++;
                        if (findAndClickButton() || attempts > 20) {
                            clearInterval(interval);
                        }
                    }, 500);
                }
            })();
        """.trimIndent()
    }
}
