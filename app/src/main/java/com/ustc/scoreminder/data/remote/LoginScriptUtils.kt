package com.ustc.scoreminder.data.remote

import com.ustc.scoreminder.domain.model.VerificationCodeMethod

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

                function fieldIdentity(input) {
                    if (!input) return '';
                    return [input.id, input.name, input.type, input.autocomplete,
                        input.inputMode, input.placeholder, input.getAttribute('aria-label'),
                        input.className].join(' ').toLowerCase();
                }

                function isVerificationInput(input) {
                    var identity = fieldIdentity(input);
                    return /otp|one.?time|verification|verify|captcha|sms|mobile|phone|auth.?code|security.?code|验证码|动态码|短信|手机/.test(identity)
                        || input.autocomplete === 'one-time-code';
                }

                function isUsableInput(input) {
                    return !input.disabled && !input.readOnly && input.type !== 'hidden'
                        && input.getClientRects().length > 0;
                }

                function firstValid(selector, predicate) {
                    var candidates = document.querySelectorAll(selector);
                    for (var i = 0; i < candidates.length; i++) {
                        if (isUsableInput(candidates[i]) && !isVerificationInput(candidates[i])
                                && (!predicate || predicate(candidates[i]))) {
                            return candidates[i];
                        }
                    }
                    return null;
                }

                function findInputs() {
                    // 密码框必须具有明确的密码语义，不能仅凭父容器判断。
                    var passwordInput = firstValid(
                        'input[type="password"], input[autocomplete="current-password"], input[autocomplete="new-password"], #password, input[name="password"], .passwordInput input[type="password"]'
                    );

                    // 优先使用明确的用户名语义；仅当密码框存在时，才允许唯一普通文本框作为兼容回退。
                    var usernameInput = firstValid(
                        'input[name="username"], #username, input[autocomplete="username"], input[id*="username" i], input[name*="username" i], input[id*="account" i], input[name*="account" i], input[id*="login" i], input[name*="login" i]',
                        function(input) { return input !== passwordInput && input.type !== 'password'; }
                    );
                    if (!usernameInput && passwordInput) {
                        var textInputs = Array.from(document.querySelectorAll('input[type="text"], input:not([type]), input[type="email"], input[type="tel"]')).filter(function(input) {
                            return input !== passwordInput && isUsableInput(input) && !isVerificationInput(input);
                        });
                        if (textInputs.length === 1) usernameInput = textInputs[0];
                    }

                    if (!usernameInput || !passwordInput || usernameInput === passwordInput) {
                        return { username: null, password: null };
                    }
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
                    // 优先检测弹窗中的 exclamation-circle 图标
                    var exclamationIcon = document.querySelector('.ant-modal-content i.anticon-exclamation-circle');
                    if (exclamationIcon || document.querySelector('.anticon-exclamation-circle')) {
                        console.log("Login error detected: exclamation-circle icon found");
                        try { AndroidBridge.onLoginErrorDetected(); } catch(e) {}
                        return;
                    }

                    // 次选：检测错误提示文本
                    var errorToast = document.querySelector('.error-toast .error-msg');
                    var bodyText = document.body ? document.body.innerText : '';
                    if ((errorToast && errorToast.innerText) ||
                        bodyText.includes("用户名或密码错误，请确认后重新输入") ||
                        bodyText.includes("用户名或密码错误") ||
                        bodyText.includes("认证失败") ||
                        bodyText.includes("Incorrect user name or password")) {
                        console.log("Login error detected: error text found");
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
                var passwordElement = null;
                var usernameElement = null;

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

                function fieldIdentity(input) {
                    if (!input) return '';
                    return [input.id, input.name, input.type, input.autocomplete,
                        input.inputMode, input.placeholder, input.getAttribute('aria-label'),
                        input.className].join(' ').toLowerCase();
                }

                function isVerificationInput(input) {
                    var identity = fieldIdentity(input);
                    return /otp|one.?time|verification|verify|captcha|sms|mobile|phone|auth.?code|security.?code|验证码|动态码|短信|手机/.test(identity)
                        || input.autocomplete === 'one-time-code';
                }

                function isUsableInput(input) {
                    return !input.disabled && !input.readOnly && input.type !== 'hidden'
                        && input.getClientRects().length > 0;
                }

                function firstValid(selector, predicate) {
                    var candidates = document.querySelectorAll(selector);
                    for (var i = 0; i < candidates.length; i++) {
                        if (isUsableInput(candidates[i]) && !isVerificationInput(candidates[i])
                                && (!predicate || predicate(candidates[i]))) {
                            return candidates[i];
                        }
                    }
                    return null;
                }

                function findInputs() {
                    var passwordInput = firstValid(
                        'input[type="password"], input[autocomplete="current-password"], input[autocomplete="new-password"], #password, input[name="password"], .passwordInput input[type="password"]'
                    );
                    var usernameInput = firstValid(
                        'input[name="username"], #username, input[autocomplete="username"], input[id*="username" i], input[name*="username" i], input[id*="account" i], input[name*="account" i], input[id*="login" i], input[name*="login" i]',
                        function(input) { return input !== passwordInput && input.type !== 'password'; }
                    );
                    if (!usernameInput && passwordInput) {
                        var textInputs = Array.from(document.querySelectorAll('input[type="text"], input:not([type]), input[type="email"], input[type="tel"]')).filter(function(input) {
                            return input !== passwordInput && isUsableInput(input) && !isVerificationInput(input);
                        });
                        if (textInputs.length === 1) usernameInput = textInputs[0];
                    }
                    if (!usernameInput || !passwordInput || usernameInput === passwordInput) {
                        return { username: null, password: null };
                    }
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
                    if (inputs.password && inputs.username) {
                        console.log("Auto-fill: Filling password immediately");
                        fillInput(inputs.password, "$safeP");
                        passwordElement = inputs.password;
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
                        usernameElement = inputs.username;
                        usernameFilled = true;
                        checkBothFilled();
                    }
                }

                // ======= 两个字段都填完后 -> 验证 + 点击登录 =======
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
                        // 只校验最初填充的两个元素。SPA 切换到验证码页后绝不能重新查找并写入新输入框。
                        if (attempts >= 10) {
                            clearInterval(vid);
                            return;
                        }
                        if (!usernameElement || !passwordElement || usernameElement === passwordElement
                                || !document.contains(usernameElement) || !document.contains(passwordElement)) {
                            clearInterval(vid);
                            return;
                        }
                        var uOk = usernameElement.value === "$safeU";
                        var pOk = passwordElement.value === "$safeP";
                        if (!uOk || !pOk) {
                            passes = 0;
                            if (!uOk) fillInput(usernameElement, "$safeU");
                            if (!pOk) fillInput(passwordElement, "$safeP");
                            return;
                        }
                        passes++;
                        if (passes >= 2 || attempts >= 10) {
                            clearInterval(vid);
                            console.log("Auto-fill: Verified, clicking login...");
                            var form = passwordElement.form || usernameElement.form;
                            var btn = (form && form.querySelector('button.login-button, button[type="submit"], input[type="submit"]'))
                                || document.querySelector('button.login-button');
                            if (btn) { btn.click(); }
                            else if (form) form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
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
                            if (inputs.username && inputs.password && inputs.username !== inputs.password) {
                                if (!usernameFilled) {
                                    fillInput(inputs.username, "$safeU");
                                    usernameElement = inputs.username;
                                    usernameFilled = true;
                                }
                                if (!passwordFilled) {
                                    fillInput(inputs.password, "$safeP");
                                    passwordElement = inputs.password;
                                    passwordFilled = true;
                                }
                            }
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
     * 监听统一身份认证的二次身份验证页面，选择指定验证方式并请求验证码。
     *
     * 统一认证站点是 SPA，提交账号密码后通常不会触发 WebView 的
     * onPageFinished，因此监听器必须和登录脚本一起提前注入。
     */
    fun getSecondFactorAutoRequestScript(
        verificationCodeMethod: VerificationCodeMethod = VerificationCodeMethod.SMS
    ): String {
        if (verificationCodeMethod == VerificationCodeMethod.DISABLED) {
            return "(function() {})();"
        }

        val chineseTabText = when (verificationCodeMethod) {
            VerificationCodeMethod.SMS -> "短信验证码"
            VerificationCodeMethod.EMAIL -> "邮箱验证码"
            VerificationCodeMethod.DISABLED -> ""
        }
        val englishTabText = when (verificationCodeMethod) {
            VerificationCodeMethod.SMS -> "SMS"
            VerificationCodeMethod.EMAIL -> "Email"
            VerificationCodeMethod.DISABLED -> ""
        }
        val methodLogText = verificationCodeMethod.name

        return """
            (function() {
                if (window._secondFactorAutoRequestInjected) return;
                window._secondFactorAutoRequestInjected = true;

                var verificationTabClicked = false;
                var codeRequested = false;
                var observer = null;
                var pollId = null;
                var pageDetectedAt = 0;
                var verificationTabClickedAt = 0;
                var pageSettleDelayMs = 1500;
                var verificationPanelSettleDelayMs = 1000;

                function normalizedText(element) {
                    return ((element && (element.innerText || element.textContent)) || '')
                        .replace(/\s+/g, ' ').trim();
                }

                function isAvailable(element) {
                    if (!element || element.disabled || element.getAttribute('aria-disabled') === 'true') {
                        return false;
                    }
                    var style = window.getComputedStyle(element);
                    return style.display !== 'none' && style.visibility !== 'hidden'
                        && element.getClientRects().length > 0;
                }

                var actionableSelector =
                    'button, a, [role="button"], [role="tab"], label, '
                    + '.ant-tabs-tab, .ant-radio-button-wrapper, .ant-radio-wrapper';

                function actionFor(element) {
                    var actionableAncestor = element.closest(actionableSelector);
                    if (actionableAncestor && isAvailable(actionableAncestor)) {
                        return actionableAncestor;
                    }

                    // 统一认证的验证码按钮结构是 span -> span -> a。
                    // 文字容器本身没有点击处理器，必须向内找到真正绑定事件的元素。
                    var actionableDescendants = element.querySelectorAll(actionableSelector);
                    for (var i = 0; i < actionableDescendants.length; i++) {
                        if (isAvailable(actionableDescendants[i])) return actionableDescendants[i];
                    }
                    return null;
                }

                function findAction(text, allowContains) {
                    // 第一轮只搜索真正可交互的元素，避免父级 span 抢先匹配文字。
                    var candidates = document.querySelectorAll(actionableSelector);
                    var containsMatch = null;
                    for (var i = 0; i < candidates.length; i++) {
                        var candidateText = normalizedText(candidates[i]);
                        if (!isAvailable(candidates[i])) continue;
                        if (candidateText === text) return candidates[i];
                        if (!containsMatch && allowContains && candidateText.indexOf(text) !== -1) {
                            containsMatch = candidates[i];
                        }
                    }
                    if (containsMatch) return containsMatch;

                    // 兼容只有普通文字容器可识别的页面结构，再由容器向内/向外解析动作元素。
                    var textContainers = document.querySelectorAll('span, div');
                    for (var j = 0; j < textContainers.length; j++) {
                        var containerText = normalizedText(textContainers[j]);
                        if (containerText !== text
                                && (!allowContains || containerText.indexOf(text) === -1)) continue;
                        var action = actionFor(textContainers[j]);
                        if (action) return action;
                    }
                    return null;
                }

                function secondFactorTexts() {
                    var bodyText = normalizedText(document.body);
                    if (bodyText.indexOf('二次身份验证') !== -1) {
                        return { verificationTab: '$chineseTabText', requestCode: '获取验证码' };
                    }
                    if (bodyText.indexOf('2-Factor Authentication') !== -1) {
                        return { verificationTab: '$englishTabText', requestCode: 'Obtain Verification Code' };
                    }
                    return null;
                }

                function stopWatching() {
                    if (observer) observer.disconnect();
                    if (pollId) clearInterval(pollId);
                }

                function tryRequestCode() {
                    var texts = secondFactorTexts();
                    if (codeRequested || !texts) return;

                    var now = Date.now();
                    if (!pageDetectedAt) {
                        pageDetectedAt = now;
                        console.log('Second factor: page detected, waiting for it to settle');
                        return;
                    }

                    // onPageFinished 不能代表 Angular SPA 的二次验证组件已经渲染完毕。
                    if (document.readyState !== 'complete'
                            || now - pageDetectedAt < pageSettleDelayMs) return;

                    if (!verificationTabClicked) {
                        var verificationTab = findAction(texts.verificationTab, false);
                        if (!verificationTab) return;
                        verificationTabClicked = true;
                        verificationTabClickedAt = Date.now();
                        console.log('Second factor: selecting $methodLogText verification');
                        verificationTab.click();
                        return;
                    }

                    // 等待所选标签对应的验证码输入区和按钮完成动态渲染及事件绑定。
                    if (now - verificationTabClickedAt < verificationPanelSettleDelayMs) return;

                    var requestButton = findAction(texts.requestCode, true);
                    if (!requestButton) return;
                    // 先置位，避免框架处理点击造成的同步 DOM 变更触发重复请求。
                    codeRequested = true;
                    try {
                        requestButton.click();
                        console.log('Second factor: verification code click dispatched to '
                            + requestButton.tagName);
                        stopWatching();
                    } catch (error) {
                        codeRequested = false;
                        console.warn('Second factor: verification code click failed');
                    }
                }

                observer = new MutationObserver(tryRequestCode);
                observer.observe(document.body || document.documentElement, {
                    childList: true, subtree: true, characterData: true, attributes: true
                });
                pollId = setInterval(tryRequestCode, 500);
                tryRequestCode();
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
