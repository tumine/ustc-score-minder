package com.ustc.scoreminder.ui.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ustc.scoreminder.domain.usecase.LoginUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val loginUseCase: LoginUseCase
) : ViewModel() {
    
    var uiState by mutableStateOf(LoginUiState())
        private set
    
    init {
        // 检查是否已有保存的凭证
        if (loginUseCase.hasCredentials()) {
            uiState = uiState.copy(isLoggedIn = true)
        }
    }
    
    fun updateUsername(username: String) {
        uiState = uiState.copy(username = username, errorMessage = null)
    }
    
    fun updatePassword(password: String) {
        uiState = uiState.copy(password = password, errorMessage = null)
    }
    
    fun login() {
        if (uiState.username.isBlank() || uiState.password.isBlank()) {
            uiState = uiState.copy(errorMessage = "用户名和密码不能为空")
            return
        }
        
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, errorMessage = null)
            
            loginUseCase(uiState.username, uiState.password)
                .onSuccess {
                    uiState = uiState.copy(
                        isLoading = false,
                        isLoggedIn = true
                    )
                }
                .onFailure { e ->
                    uiState = uiState.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "登录失败"
                    )
                }
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
        // 标记登录成功
        // WebView 登录后，cookies 已经由 WebView 的 CookieManager 管理
        // 后续的网络请求会自动使用这些 cookies
        uiState = uiState.copy(isLoggedIn = true)
    }
}

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val errorMessage: String? = null
)
