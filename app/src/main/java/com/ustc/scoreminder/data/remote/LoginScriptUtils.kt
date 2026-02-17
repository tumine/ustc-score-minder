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
                
                var passwordFilled = false;
                var usernameFilled = false;

                // 使用原生 setter 设置值（作为后备方案）
                var nativeSetter = Object.getOwnPropertyDescriptor(
                    window.HTMLInputElement.prototype, 'value'
                ).set;

                // 使用 execCommand 模拟真实输入，兼容 Angular ngModel
                function fillInput(element, value) {
                    element.focus();
                    element.select();
                    var success = document.execCommand('insertText', false, value);
                    if (!success || element.value !== value) {
                        console.log("Auto-fill: execCommand failed, using fallback");
                        nativeSetter.call(element, value);
                        element.dispatchEvent(new InputEvent('input', {
                            bubbles: true, inputType: 'insertText', data: value
                        }));
                        element.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                    element.dispatchEvent(new Event('blur', { bubbles: true }));
                }

                function findInputs() {
                    var usernameInput = document.querySelector('input[name="username"]')
                        || document.querySelector('#username')
                        || document.querySelector('input[type="text"]');
                    var passwordInput = document.querySelector('.passwordInput input')
                        || document.querySelector('input[type="password"]')
                        || document.querySelector('input[autocomplete="new-password"]')
                        || document.querySelector('#password')
                        || document.querySelector('input[name="password"]');
                    return { username: usernameInput, password: passwordInput };
                }

                // 检测用户名输入框旁的图标
                function findIconNear(inputEl) {
                    if (!inputEl) return null;
                    var container = inputEl.closest('nz-input-group, .ant-input-group-wrapper, .ant-input-affix-wrapper, ion-item, .input-wrapper, nz-form-control');
                    if (!container) container = inputEl.parentElement;
                    if (!container) return null;
                    return container.querySelector('svg, i.anticon, i[nz-icon], span.anticon, ion-icon, .ant-input-prefix i, .ant-input-prefix svg, .ant-input-prefix span')
                        || container.querySelector('i, svg, img');
                }

                // ======= 阶段1：密码立即填充（发现输入框即填） =======
                function tryFillPassword() {
                    if (passwordFilled) return;
                    var inputs = findInputs();
                    if (inputs.password) {
                        console.log("Auto-fill: Filling password immediately");
                        fillInput(inputs.password, "$safeP");
                        passwordFilled = true;
                        checkBothFilled();
                    }
                }

                // ======= 阶段2：用户名等待图标后填充 =======
                function tryFillUsername() {
                    if (usernameFilled) return;
                    var inputs = findInputs();
                    if (!inputs.username) return;
                    var icon = findIconNear(inputs.username);
                    if (icon) {
                        console.log("Auto-fill: Username icon detected (" + icon.tagName + "), filling username");
                        fillInput(inputs.username, "$safeU");
                        usernameFilled = true;
                        checkBothFilled();
                    }
                }

                // ======= 两个字段都填完后 → 验证 + 点击登录 =======
                function checkBothFilled() {
                    if (!passwordFilled || !usernameFilled) return;
                    console.log("Auto-fill: Both fields filled, verifying...");
                    try { AndroidBridge.captureCredentials("$safeU", "$safeP"); } catch(e) {}
                    // 清理监听器
                    if (pollId) clearInterval(pollId);
                    if (obs) obs.disconnect();
                    startVerify();
                }

                function startVerify() {
                    var passes = 0;
                    var attempts = 0;
                    var vid = setInterval(function() {
                        attempts++;
                        var inputs = findInputs();
                        if (!inputs.username || !inputs.password) {
                            passes = 0; return;
                        }
                        var uOk = inputs.username.value === "$safeU";
                        var pOk = inputs.password.value === "$safeP";
                        if (!uOk || !pOk) {
                            passes = 0;
                            if (!uOk) fillInput(inputs.username, "$safeU");
                            if (!pOk) fillInput(inputs.password, "$safeP");
                            return;
                        }
                        passes++;
                        if (passes >= 2 || attempts >= 10) {
                            clearInterval(vid);
                            console.log("Auto-fill: Verified, clicking login...");
                            var btn = document.querySelector('button.login-button')
                                || document.querySelector('button[type="submit"]')
                                || document.querySelector('input[type="submit"]');
                            if (btn) { btn.click(); }
                            else {
                                var form = document.querySelector('form');
                                if (form) form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
                            }
                        }
                    }, 200);
                }

                // ======= 统一监听：MutationObserver + 轮询 =======
                var obs = new MutationObserver(function() {
                    tryFillPassword();
                    tryFillUsername();
                });
                obs.observe(document.body || document.documentElement, {
                    childList: true, subtree: true
                });

                var attempts = 0;
                var pollId = setInterval(function() {
                    attempts++;
                    tryFillPassword();
                    tryFillUsername();
                    if ((passwordFilled && usernameFilled) || attempts >= 40) {
                        clearInterval(pollId);
                        obs.disconnect();
                        // 超时降级：强制填充
                        if (!passwordFilled || !usernameFilled) {
                            console.log("Auto-fill: Timeout, force filling...");
                            var inputs = findInputs();
                            if (inputs.username && !usernameFilled) { fillInput(inputs.username, "$safeU"); usernameFilled = true; }
                            if (inputs.password && !passwordFilled) { fillInput(inputs.password, "$safeP"); passwordFilled = true; }
                            if (usernameFilled && passwordFilled) {
                                try { AndroidBridge.captureCredentials("$safeU", "$safeP"); } catch(e) {}
                                startVerify();
                            }
                        }
                    }
                }, 500);

                // 立即尝试
                tryFillPassword();
                tryFillUsername();
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
