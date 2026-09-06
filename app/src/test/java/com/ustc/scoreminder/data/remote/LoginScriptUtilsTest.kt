package com.ustc.scoreminder.data.remote

import com.ustc.scoreminder.domain.model.VerificationCodeMethod
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginScriptUtilsTest {

    @Test
    fun autoFillScript_excludesVerificationFieldsAndRequiresDistinctLoginInputs() {
        val script = LoginScriptUtils.getAutoFillScript("Alice", "Bob")

        assertTrue(script.contains("one-time-code"))
        assertTrue(script.contains("验证码"))
        assertTrue(script.contains("usernameInput === passwordInput"))
        assertTrue(script.contains("usernameElement === passwordElement"))
        assertFalse(script.contains("document.querySelector('.passwordInput input')"))
        assertFalse(script.contains("|| document.querySelector('input[type=\"text\"]')"))
    }

    @Test
    fun autoFillScript_verifiesOnlyTheOriginallyFilledElementsAndStops() {
        val script = LoginScriptUtils.getAutoFillScript("Alice", "Bob")

        assertTrue(script.contains("document.contains(usernameElement)"))
        assertTrue(script.contains("document.contains(passwordElement)"))
        assertTrue(script.contains("if (attempts >= 10)"))
        assertFalse(script.contains("var inputs = findInputs();\n                        if (!inputs.username || !inputs.password)"))
    }

    @Test
    fun credentialCaptureScript_doesNotTreatOneVerificationFieldAsBothCredentials() {
        val script = LoginScriptUtils.getCredentialCaptureScript()

        assertTrue(script.contains("isVerificationInput"))
        assertTrue(script.contains("usernameInput === passwordInput"))
        assertFalse(script.contains("document.querySelector('.passwordInput input')"))
    }

    @Test
    fun secondFactorScript_requiresPageMarkerAndRequestsSmsCodeOnlyOnce() {
        val script = LoginScriptUtils.getSecondFactorAutoRequestScript()

        assertTrue(script.contains("二次身份验证"))
        assertTrue(script.contains("短信验证码"))
        assertTrue(script.contains("获取验证码"))
        assertTrue(script.contains("2-Factor Authentication"))
        assertTrue(script.contains("verificationTab: 'SMS'"))
        assertTrue(script.contains("Obtain Verification Code"))
        assertTrue(script.contains("if (codeRequested || !texts) return"))
        assertTrue(script.contains("pageSettleDelayMs = 1500"))
        assertTrue(script.contains("verificationPanelSettleDelayMs = 1000"))
        assertTrue(script.contains("document.readyState !== 'complete'"))
        assertTrue(script.contains("now - pageDetectedAt < pageSettleDelayMs"))
        assertTrue(script.contains("now - verificationTabClickedAt < verificationPanelSettleDelayMs"))
        assertTrue(script.contains("document.querySelectorAll(actionableSelector)"))
        assertTrue(script.contains("element.querySelectorAll(actionableSelector)"))
        assertTrue(script.contains("if (candidateText === text) return candidates[i]"))
        assertTrue(script.contains("codeRequested = true"))
        assertTrue(script.contains("requestButton.click()"))
        assertTrue(script.contains("codeRequested = false"))
        assertTrue(script.contains("verification code click dispatched to"))
        assertTrue(script.contains("new MutationObserver(tryRequestCode)"))
    }

    @Test
    fun secondFactorScript_selectsEmailTabWhenConfigured() {
        val script = LoginScriptUtils.getSecondFactorAutoRequestScript(VerificationCodeMethod.EMAIL)

        assertTrue(script.contains("verificationTab: '邮箱验证码'"))
        assertTrue(script.contains("verificationTab: 'Email'"))
        assertTrue(script.contains("selecting EMAIL verification"))
        assertTrue(script.contains("Obtain Verification Code"))
        assertFalse(script.contains("verificationTab: 'SMS'"))
    }

    @Test
    fun secondFactorScript_doesNothingWhenAutoRequestIsDisabled() {
        val script = LoginScriptUtils.getSecondFactorAutoRequestScript(VerificationCodeMethod.DISABLED)

        assertTrue(script.contains("(function() {})();"))
        assertFalse(script.contains(".click()"))
        assertFalse(script.contains("Obtain Verification Code"))
        assertFalse(script.contains("获取验证码"))
    }
}

