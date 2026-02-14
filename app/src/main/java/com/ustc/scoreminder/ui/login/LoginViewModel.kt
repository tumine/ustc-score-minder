package com.ustc.scoreminder.ui.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.ustc.scoreminder.data.local.CredentialsManager
import com.ustc.scoreminder.domain.usecase.LoginUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.ustc.scoreminder.worker.GradeSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val loginUseCase: LoginUseCase,
    private val credentialsManager: CredentialsManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    private val workManager by lazy { WorkManager.getInstance(context) }
    
    var uiState by mutableStateOf(LoginUiState())
        private set
    
    init {
        // 检查是否有有效的登录会话（凭证或 cookies）
        if (loginUseCase.hasValidSession()) {
            uiState = uiState.copy(isLoggedIn = true)
        }
        checkHasCredentials()
    }
    
    fun logout() {
        loginUseCase.logout()
        uiState = LoginUiState()
        // 取消定时任务
        workManager.cancelUniqueWork(GradeSyncWorker.WORK_NAME)
    }
    
    /**
     * WebView 登录成功回调
     * @param username 用户名（学号）
     * @param password 密码
     */
    fun onWebViewLoginSuccess(username: String, password: String) {
        Log.d("LoginViewModel", "onWebViewLoginSuccess: username=$username, passwordLength=${password.length}")
        
        // 加密保存用户名和密码
        if (username.isNotBlank() && password.isNotBlank()) {
            saveCredentials(username, password)
        } else {
            Log.e("LoginViewModel", "Credentials empty, not saving!")
        }
        
        // 登录成功后，立即调度后台同步任务
        val intervalMinutes = credentialsManager.getSyncIntervalMinutes().toLong()
        val workRequest = GradeSyncWorker.buildRequest(intervalMinutes)
        workManager.enqueueUniquePeriodicWork(
            GradeSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
        
        uiState = uiState.copy(isLoggedIn = true)
    }

    fun getCredentials(): Pair<String, String>? {
        val u = credentialsManager.getUsername()
        val p = credentialsManager.getPassword()
        return if (u != null && p != null) u to p else null
    }

    fun checkHasCredentials() {
        uiState = uiState.copy(hasCredentials = credentialsManager.hasCredentials())
        if (uiState.hasCredentials) {
            Log.d("LoginViewModel", "User has credentials stored")
        }
    }

    fun saveCredentials(u: String, p: String) {
        Log.d("LoginViewModel", "Saving credentials explicitly")
        credentialsManager.saveCredentials(u, p)
        checkHasCredentials()
    }
    
    fun clearCredentials() {
        Log.d("LoginViewModel", "Clearing credentials")
        credentialsManager.clearCredentials()
        checkHasCredentials()
    }
}

data class LoginUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val hasCredentials: Boolean = false,
    val errorMessage: String? = null
)
