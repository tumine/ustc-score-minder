package com.ustc.scoreminder.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 使用 EncryptedSharedPreferences 加密存储用户凭证
 */
@Singleton
class CredentialsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    fun saveCredentials(username: String, password: String) {
        sharedPreferences.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }
    
    fun getUsername(): String? = sharedPreferences.getString(KEY_USERNAME, null)
    
    fun getPassword(): String? = sharedPreferences.getString(KEY_PASSWORD, null)
    
    fun hasCredentials(): Boolean = getUsername() != null && getPassword() != null
    
    fun clearCredentials() {
        sharedPreferences.edit()
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .apply()
    }
    
    fun getSyncIntervalMs(): Long {
        // 先检查是否有毫秒设置
        if (sharedPreferences.contains(KEY_SYNC_INTERVAL_MS)) {
            return sharedPreferences.getLong(KEY_SYNC_INTERVAL_MS, DEFAULT_SYNC_INTERVAL_MS)
        }
        
        // 迁移旧的分钟设置
        val minutes = sharedPreferences.getInt(KEY_SYNC_INTERVAL_MINUTES, DEFAULT_SYNC_INTERVAL_MINUTES)
        val ms = minutes * 60 * 1000L
        
        // 保存新格式并移除旧格式（可选，为了兼容性也可以保留）
        setSyncIntervalMs(ms)
        
        return ms
    }

    fun setSyncIntervalMs(ms: Long) {
        sharedPreferences.edit()
            .putLong(KEY_SYNC_INTERVAL_MS, ms)
            // 同时更新旧的分钟key以保持兼容性（尽管可能不再准确，取近似值）
            .putInt(KEY_SYNC_INTERVAL_MINUTES, (ms / 60000).toInt())
            .apply()
    }

    // 保持兼容性，但内部使用 MS
    fun getSyncIntervalMinutes(): Int = (getSyncIntervalMs() / 60000).toInt()
    
    fun setSyncIntervalMinutes(minutes: Int) {
        setSyncIntervalMs(minutes * 60 * 1000L)
    }
    
    fun isNotificationEnabled(): Boolean = 
        sharedPreferences.getBoolean(KEY_NOTIFICATION_ENABLED, true)
    
    fun setNotificationEnabled(enabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean(KEY_NOTIFICATION_ENABLED, enabled)
            .apply()
    }
    
    companion object {
        private const val PREFS_NAME = "ustc_score_minder_encrypted_prefs"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SYNC_INTERVAL_MINUTES = "sync_interval_minutes"
        private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
        private const val KEY_SYNC_INTERVAL_MS = "sync_interval_ms"
        private const val DEFAULT_SYNC_INTERVAL_MINUTES = 30
        private const val DEFAULT_SYNC_INTERVAL_MS = DEFAULT_SYNC_INTERVAL_MINUTES * 60 * 1000L
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_LAST_SYNC_RESULT = "last_sync_result"
        private const val KEY_NEEDS_RE_LOGIN = "needs_re_login"
    }

    fun setLastSyncTime(time: Long) {
        sharedPreferences.edit()
            .putLong(KEY_LAST_SYNC_TIME, time)
            .apply()
    }

    fun getLastSyncTime(): Long = sharedPreferences.getLong(KEY_LAST_SYNC_TIME, 0)

    fun setLastSyncResult(result: String) {
        sharedPreferences.edit()
            .putString(KEY_LAST_SYNC_RESULT, result)
            .apply()
    }

    fun getLastSyncResult(): String? = sharedPreferences.getString(KEY_LAST_SYNC_RESULT, null)

    fun setNeedsReLogin(needsReLogin: Boolean) {
        sharedPreferences.edit()
            .putBoolean(KEY_NEEDS_RE_LOGIN, needsReLogin)
            .apply()
    }

    fun getNeedsReLogin(): Boolean = sharedPreferences.getBoolean(KEY_NEEDS_RE_LOGIN, false)

    fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
