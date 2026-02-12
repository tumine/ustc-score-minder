package com.ustc.scoreminder.ui.settings

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

    // 监听 SharedPreferences 变化以实时更新调试信息
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "last_sync_time" || key == "last_sync_result" || key == "sync_interval_minutes") {
            loadDebugInfo()
            loadSettings()
        }
    }
    
    init {
        loadSettings()
        loadDebugInfo()
        credentialsManager.registerOnSharedPreferenceChangeListener(prefsListener)
    }
    
    override fun onCleared() {
        super.onCleared()
        credentialsManager.unregisterOnSharedPreferenceChangeListener(prefsListener)
        workInfoLiveData?.removeObserver(workInfoObserver!!)
    }
    
    private fun loadSettings() {
        uiState = uiState.copy(
            syncIntervalMinutes = credentialsManager.getSyncIntervalMinutes(),
            notificationEnabled = credentialsManager.isNotificationEnabled(),
            isLoggedIn = credentialsManager.hasCredentials()
        )
    }
    
    fun loadDebugInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val lastSyncTime = credentialsManager.getLastSyncTime()
            val lastSyncResult = credentialsManager.getLastSyncResult()
            
            // 获取下次同步时间
            var nextSyncTime = 0L
            try {
                // 使用 get() 阻塞获取，因为我们在 IO 线程
                val workInfos = workManager.getWorkInfosForUniqueWork(GradeSyncWorker.WORK_NAME).get()
                if (workInfos != null && workInfos.isNotEmpty()) {
                    val workInfo = workInfos[0]
                    nextSyncTime = workInfo.nextScheduleTimeMillis
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // 切换回主线程更新 UI
            viewModelScope.launch(Dispatchers.Main) {
                uiState = uiState.copy(
                    lastSyncTime = lastSyncTime,
                    lastSyncResult = lastSyncResult,
                    nextSyncTime = nextSyncTime
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
                    
                    // 如果手动同步成功，重置定期任务的计时器
                    if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                        val interval = credentialsManager.getSyncIntervalMinutes().toLong()
                        val request = GradeSyncWorker.buildRequest(interval)
                        workManager.enqueueUniquePeriodicWork(
                            GradeSyncWorker.WORK_NAME,
                            androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                            request
                        )
                        // 重新加载以更新“下次同步时间”
                        // 稍微延迟一下以确保 WorkManager 更新了数据库
                        viewModelScope.launch(Dispatchers.IO) {
                            kotlinx.coroutines.delay(500)
                            loadDebugInfo()
                        }
                    }
                }
            }
        }
        workInfoLiveData?.observeForever(workInfoObserver!!)
        
        workManager.enqueue(workRequest)
    }
    
    fun updateSyncInterval(minutes: Int) {
        credentialsManager.setSyncIntervalMinutes(minutes)
        uiState = uiState.copy(syncIntervalMinutes = minutes)
        // 更新定时任务
        // 注意：这里需要重新调度 PeriodicWork，App 类中有 helper 方法，但 ViewModel 最好通过 WorkManager 直接操作
        // 或者通知 UI 层去处理。为了简单起见，这里假设用户下次重启应用或手动触发时生效，
        // 但更好的做法是立即重新调度。
        // 由于 GradeSyncWorker.buildRequest 在 companion object 中，我们可以直接调用。
        
        val workRequest = GradeSyncWorker.buildRequest(minutes.toLong())
        workManager.enqueueUniquePeriodicWork(
            GradeSyncWorker.WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
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
    val syncIntervalMinutes: Int = 30,
    val notificationEnabled: Boolean = true,
    val isLoggedIn: Boolean = true,
    val lastSyncTime: Long = 0,
    val lastSyncResult: String? = null,
    val nextSyncTime: Long = 0,
    val isSyncing: Boolean = false
)
