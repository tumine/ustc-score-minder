package com.ustc.scoreminder.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.ustc.scoreminder.data.local.CredentialsManager
import com.ustc.scoreminder.data.remote.BackgroundWebViewAuthenticator
import com.ustc.scoreminder.domain.usecase.SyncGradesUseCase
import com.ustc.scoreminder.domain.usecase.SyncGradesUseCase.AuthenticationException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class GradeSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncGradesUseCase: SyncGradesUseCase,
    private val notificationHelper: NotificationHelper,
    private val credentialsManager: CredentialsManager,
    private val backgroundWebViewAuthenticator: BackgroundWebViewAuthenticator
) : CoroutineWorker(appContext, workerParams) {
    
    companion object {
        private const val TAG = "GradeSyncWorker"
        const val WORK_NAME = "grade_sync_work"
        
        fun buildRequest(intervalMinutes: Long, initialDelayMinutes: Long = 0): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val builder = PeriodicWorkRequestBuilder<GradeSyncWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10, TimeUnit.MINUTES
                )

            if (initialDelayMinutes > 0) {
                builder.setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
            }
            
            return builder.build()
        }

        const val KEY_SCHEDULE_NEXT = "schedule_next"
        const val KEY_INTERVAL_MS = "interval_ms"

        fun buildOneTimeRequest(delayMs: Long, recursive: Boolean): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val data = workDataOf(
                KEY_SCHEDULE_NEXT to recursive,
                KEY_INTERVAL_MS to delayMs
            )

            return OneTimeWorkRequest.Builder(GradeSyncWorker::class.java)
                .setConstraints(constraints)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .build()
        }
    }
    
    override suspend fun doWork(): ListenableWorker.Result {
        Log.d(TAG, "Starting grade sync work")
        
        // 检查是否有凭证
        if (!credentialsManager.hasCredentials()) {
            Log.d(TAG, "No credentials, skipping sync")
            credentialsManager.setLastSyncTime(System.currentTimeMillis())
            credentialsManager.setLastSyncResult("跳过: 无凭证")
            return ListenableWorker.Result.failure()
        }
        
        // 检查通知设置
        val notificationEnabled = credentialsManager.isNotificationEnabled()
        
        return try {
            syncGradesUseCase().fold(
                onSuccess = { result ->
                    handleSyncSuccess(result, notificationEnabled)
                },
                onFailure = { e ->
                    Log.e(TAG, "Sync failed", e)
                    
                    // 认证错误：优先尝试后台重登后重试，而非直接发送通知
                    if (e is AuthenticationException) {
                        handleAuthFailureWithReLogin(notificationEnabled)
                    } else {
                        credentialsManager.setLastSyncTime(System.currentTimeMillis())
                        credentialsManager.setLastSyncResult("失败: ${e.message}")
                        ListenableWorker.Result.failure()
                    }
                }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d(TAG, "Work cancelled", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Work failed with exception", e)
            credentialsManager.setLastSyncTime(System.currentTimeMillis())
            credentialsManager.setLastSyncResult("异常: ${e.message}")
            ListenableWorker.Result.failure()
        } finally {
            // Check if we need to schedule the next one (recursive mode for short intervals)
            val scheduleNext = inputData.getBoolean(KEY_SCHEDULE_NEXT, false)
            val intervalMs = inputData.getLong(KEY_INTERVAL_MS, 0)
            
            // Only schedule if we are NOT stopped (cancelled) and explicit recursion is requested
            if (!isStopped && scheduleNext && intervalMs > 0) {
                Log.d(TAG, "Scheduling next recursive work in ${intervalMs}ms")
                val nextRequest = buildOneTimeRequest(intervalMs, true)
                WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE, // Must be REPLACE to keep the chain unique but active
                    nextRequest
                )
            }
        }
    }
    
    /**
     * 处理同步成功
     */
    private fun handleSyncSuccess(
        result: SyncGradesUseCase.SyncResult,
        notificationEnabled: Boolean
    ): ListenableWorker.Result {
        Log.d(TAG, "Sync successful: ${result.newGrades.size} new grades")
        
        if (result.hasChanges && notificationEnabled && result.newGrades.isNotEmpty()) {
            notificationHelper.showNewGradeNotification(result.newGrades)
        }

        credentialsManager.setLastSyncTime(System.currentTimeMillis())
        credentialsManager.setLastSyncResult("成功: 发现 ${result.newGrades.size} 个新成绩")
        
        return ListenableWorker.Result.success()
    }
    
    /**
     * 处理认证失败：尝试后台重登后重试同步
     * 只有在重登和重试都失败后才发送通知提醒用户
     */
    private suspend fun handleAuthFailureWithReLogin(
        notificationEnabled: Boolean
    ): ListenableWorker.Result {
        Log.w(TAG, "Authentication error detected, attempting background re-login before notifying user...")
        
        val reLoginSuccess = attemptBackgroundReLogin()
        
        if (reLoginSuccess) {
            Log.d(TAG, "Background re-login successful, retrying sync...")
            
            // 重登成功，重试同步
            return syncGradesUseCase().fold(
                onSuccess = { retryResult ->
                    handleSyncSuccess(retryResult, notificationEnabled)
                },
                onFailure = { retryError ->
                    Log.e(TAG, "Retry sync failed after re-login", retryError)
                    credentialsManager.setLastSyncTime(System.currentTimeMillis())
                    credentialsManager.setLastSyncResult("失败: 重登后重试仍失败 - ${retryError.message}")
                    
                    // 重试后仍为认证错误，通知用户
                    if (retryError is AuthenticationException) {
                        notificationHelper.showReLoginNotification(retryError.message)
                    }
                    
                    ListenableWorker.Result.failure()
                }
            )
        } else {
            // 重登失败，通知用户
            Log.w(TAG, "Background re-login failed, notifying user")
            credentialsManager.setLastSyncTime(System.currentTimeMillis())
            credentialsManager.setLastSyncResult("失败: 后台重登失败")
            notificationHelper.showReLoginNotification("登录状态已过期，自动重登失败，请手动重新登录")
            
            return ListenableWorker.Result.failure()
        }
    }
    
    /**
     * 尝试后台重登
     * @return true 如果重登成功
     */
    private suspend fun attemptBackgroundReLogin(): Boolean {
        val username = credentialsManager.getUsername()
        val password = credentialsManager.getPassword()
        
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            Log.w(TAG, "No saved credentials for background re-login")
            return false
        }
        
        return try {
            val result = backgroundWebViewAuthenticator.performLogin(username, password)
            if (result.isSuccess) {
                Log.d(TAG, "Background WebView re-login successful")
                credentialsManager.setNeedsReLogin(false)
                true
            } else {
                Log.w(TAG, "Background WebView re-login failed: ${result.exceptionOrNull()?.message}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background re-login exception", e)
            false
        }
    }
}
