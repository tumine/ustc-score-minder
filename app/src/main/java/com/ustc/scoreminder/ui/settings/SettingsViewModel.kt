package com.ustc.scoreminder.ui.settings

import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.ustc.scoreminder.data.local.CredentialsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentialsManager: CredentialsManager
) : ViewModel() {
    
    var uiState by mutableStateOf(SettingsUiState())
        private set
    
    init {
        loadSettings()
    }
    
    private fun loadSettings() {
        uiState = uiState.copy(
            syncIntervalMinutes = credentialsManager.getSyncIntervalMinutes(),
            notificationEnabled = credentialsManager.isNotificationEnabled(),
            isLoggedIn = credentialsManager.hasCredentials()
        )
    }
    
    fun updateSyncInterval(minutes: Int) {
        credentialsManager.setSyncIntervalMinutes(minutes)
        uiState = uiState.copy(syncIntervalMinutes = minutes)
    }
    
    fun toggleNotification(enabled: Boolean) {
        credentialsManager.setNotificationEnabled(enabled)
        uiState = uiState.copy(notificationEnabled = enabled)
    }
    
    fun logout() {
        credentialsManager.clearCredentials()
        // 同时清除 WebView 的 cookies
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        uiState = uiState.copy(isLoggedIn = false)
    }
}

data class SettingsUiState(
    val syncIntervalMinutes: Int = 30,
    val notificationEnabled: Boolean = true,
    val isLoggedIn: Boolean = true
)
