package com.ustc.scoreminder.ui.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.ustc.scoreminder.data.local.CredentialsManager
import com.ustc.scoreminder.domain.usecase.LoginUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val loginUseCase: LoginUseCase,
    private val credentialsManager: CredentialsManager
) : ViewModel() {
    
    var uiState by mutableStateOf(LoginUiState())
        private set
    
    init {
        // 检查是否有有效的登录会话（凭证或 cookies）
        if (loginUseCase.hasValidSession()) {
            uiState = uiState.copy(isLoggedIn = true)
        }
    }
    
    fun logout() {
        loginUseCase.logout()
        uiState = LoginUiState()
    }
    
    /**
     * WebView 登录成功回调
     * @param cookies 登录成功后的 cookies
     */
    fun onWebViewLoginSuccess(cookies: String) {
        // 保存 cookies 以便下次跳过登录
        credentialsManager.saveCookies(cookies)
        uiState = uiState.copy(isLoggedIn = true)
    }
}

data class LoginUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val errorMessage: String? = null
)
