package com.ustc.scoreminder.ui.settings

import android.util.Log
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.ustc.scoreminder.data.local.CredentialsManager
import com.ustc.scoreminder.worker.GradeSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.util.UUID
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext



@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentialsManager: CredentialsManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    private val workManager by lazy { WorkManager.getInstance(context) }
    
    var uiState by mutableStateOf(SettingsUiState())
        private set

    // 监听手动同步任务的状态
    private var currentSyncId: UUID? = null
    private var workInfoObserver: Observer<androidx.work.WorkInfo>? = null
    private var workInfoLiveData: LiveData<androidx.work.WorkInfo>? = null

    // 监听定期同步任务的状态变化
    private val periodicWorkObserver = Observer<List<androidx.work.WorkInfo>> { workInfos ->
        if (workInfos.isNullOrEmpty()) return@Observer
        
        // 找到未结束的定期任务（ENQUEUED 或 RUNNING）
        val activeWorkInfo = workInfos.find { !it.state.isFinished }
        
        if (activeWorkInfo != null) {
            // 当任务处于 ENQUEUED 状态时，nextScheduleTimeMillis 才是准确的下次执行时间
            // 当任务 RUNNING 时，nextScheduleTimeMillis 可能是 Long.MAX_VALUE
            if (activeWorkInfo.state == androidx.work.WorkInfo.State.ENQUEUED) {
                val nextTime = activeWorkInfo.nextScheduleTimeMillis
                if (nextTime != Long.MAX_VALUE && nextTime > System.currentTimeMillis()) {
                    uiState = uiState.copy(nextSyncTime = nextTime)
                }
            }
            // 如果是 RUNNING 状态，保持现有的 nextSyncTime 不变，避免显示异常或 0
        }
    }

    // 监听 SharedPreferences 变化以实时更新调试信息
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "sync_interval_ms" || key == "sync_interval_minutes") {
            loadSettings()
            // Don't loadDebugInfo here, wait for manual update in updateSyncInterval
        } else if (key == "last_sync_time" || key == "last_sync_result") {
            loadDebugInfo()
            loadSettings()
        }
    }
    
    init {
        loadSettings()
        loadDebugInfo()
        credentialsManager.registerOnSharedPreferenceChangeListener(prefsListener)
        
        // 开始观察定期任务状态
        workManager.getWorkInfosForUniqueWorkLiveData(GradeSyncWorker.WORK_NAME)
            .observeForever(periodicWorkObserver)
    }
    
    override fun onCleared() {
        super.onCleared()
        credentialsManager.unregisterOnSharedPreferenceChangeListener(prefsListener)
        workInfoLiveData?.removeObserver(workInfoObserver!!)
        
        // 停止观察定期任务
        try {
            workManager.getWorkInfosForUniqueWorkLiveData(GradeSyncWorker.WORK_NAME)
                .removeObserver(periodicWorkObserver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun loadSettings() {
        uiState = uiState.copy(
            syncIntervalMs = credentialsManager.getSyncIntervalMs(),
            notificationEnabled = credentialsManager.isNotificationEnabled(),
            isLoggedIn = credentialsManager.hasCredentials()
        )
    }
    
    fun loadDebugInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val lastSyncTime = credentialsManager.getLastSyncTime()
            val lastSyncResult = credentialsManager.getLastSyncResult()
            
            // 切换回主线程更新 UI
            viewModelScope.launch(Dispatchers.Main) {
                uiState = uiState.copy(
                    lastSyncTime = lastSyncTime,
                    lastSyncResult = lastSyncResult
                    // nextSyncTime 由 periodicWorkObserver 维护，此处不再更新
                )
            }
        }
    }
    
    fun triggerSyncNow() {
        val workRequest = OneTimeWorkRequest.Builder(GradeSyncWorker::class.java).build()
        currentSyncId = workRequest.id
        
        // 观察此次同步任务的状态
        workInfoLiveData?.removeObserver(workInfoObserver!!)
        
        workInfoLiveData = workManager.getWorkInfoByIdLiveData(workRequest.id)
        workInfoObserver = Observer { workInfo ->
            if (workInfo != null) {
                val isSyncing = workInfo.state == androidx.work.WorkInfo.State.RUNNING || 
                                workInfo.state == androidx.work.WorkInfo.State.ENQUEUED
                
                // update UI state on main thread (Observer is called on main thread)
                uiState = uiState.copy(isSyncing = isSyncing)
                
                if (workInfo.state.isFinished) {
                    loadDebugInfo() // 任务完成，刷新一下
                    
                    // 如果手动同步成功，重置定时任务
                    if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                        Log.d("SettingsViewModel", "Manual sync succeeded, rescheduling work")
                        val intervalMs = credentialsManager.getSyncIntervalMs()
                        
                        if (intervalMs < 15 * 60 * 1000L) {
                            // 短间隔：重置递归任务
                            val request = GradeSyncWorker.buildOneTimeRequest(intervalMs, true)
                            workManager.enqueueUniqueWork(
                                GradeSyncWorker.WORK_NAME,
                                ExistingWorkPolicy.REPLACE,
                                request
                            )
                        } else {
                            // 长间隔：重置定期任务
                            val intervalMinutes = intervalMs / (60 * 1000)
                            val request = GradeSyncWorker.buildRequest(intervalMinutes, intervalMinutes)
                            workManager.enqueueUniquePeriodicWork(
                                GradeSyncWorker.WORK_NAME,
                                androidx.work.ExistingPeriodicWorkPolicy.REPLACE,
                                request
                            )
                        }
                        
                        // 重新加载以更新“下次同步时间”
                        viewModelScope.launch(Dispatchers.IO) {
                            kotlinx.coroutines.delay(1000)
                            loadDebugInfo()
                            kotlinx.coroutines.delay(2000)
                            loadDebugInfo()
                        }
                    }
                }
            }
        }
        workInfoLiveData?.observeForever(workInfoObserver!!)
        
        workManager.enqueue(workRequest)
    }
    
    fun updateSyncInterval(intervalMs: Long) {
        credentialsManager.setSyncIntervalMs(intervalMs)
        uiState = uiState.copy(syncIntervalMs = intervalMs)
        
        if (intervalMs < 15 * 60 * 1000L) {
            // 短间隔：使用递归 OneTimeWork
            // 取消之前的（可能是定期任务）并立即启动新的计时（initialDelay）
            val request = GradeSyncWorker.buildOneTimeRequest(intervalMs, true)
            workManager.enqueueUniqueWork(
                GradeSyncWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            // 更新 UI 状态
            loadDebugInfo()
        } else {
            // 长间隔：使用 PeriodicWork
            // 如果之前是递归任务，enqueueUniquePeriodicWork(REPLACE) 会覆盖它
            val intervalMinutes = intervalMs / (60 * 1000)
            val workRequest = GradeSyncWorker.buildRequest(intervalMinutes)
            workManager.enqueueUniquePeriodicWork(
                GradeSyncWorker.WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.UPDATE, // 使用 UPDATE 尝试保留原有计划，或者 REPLACE 也可以
                workRequest
            )
             // 对于 PeriodicWork，由于最小间隔限制，UPDATE可能不会立即生效如预期那样灵敏，但对于标准用途足够
        }
        
        // 稍微延迟一下更新 UI
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(500)
            loadDebugInfo()
        }
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
        // 取消定时任务
        workManager.cancelUniqueWork(GradeSyncWorker.WORK_NAME)
    }
}

data class SettingsUiState(
    val syncIntervalMs: Long = 30 * 60 * 1000L,
    val notificationEnabled: Boolean = true,
    val isLoggedIn: Boolean = true,
    val lastSyncTime: Long = 0,
    val lastSyncResult: String? = null,
    val nextSyncTime: Long = 0,
    val isSyncing: Boolean = false
) {
    // 兼容旧 UI 代码的辅助属性
    val syncIntervalMinutes: Int
        get() = (syncIntervalMs / 60000).toInt()
}
