package com.ustc.scoreminder.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.ustc.scoreminder.data.local.CredentialsManager
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
    private val credentialsManager: CredentialsManager
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
                    Log.d(TAG, "Sync successful: ${result.newGrades.size} new grades")
                    
                    if (result.hasChanges && notificationEnabled && result.newGrades.isNotEmpty()) {
                        notificationHelper.showNewGradeNotification(result.newGrades)
                    }

                    credentialsManager.setLastSyncTime(System.currentTimeMillis())
                    credentialsManager.setLastSyncResult("成功: 发现 ${result.newGrades.size} 个新成绩")
                    
                    ListenableWorker.Result.success()
                },
                onFailure = { e ->
                    Log.e(TAG, "Sync failed", e)
                    credentialsManager.setLastSyncTime(System.currentTimeMillis())
                    credentialsManager.setLastSyncResult("失败: ${e.message}")
                    
                    // 检查是否为认证错误，若是则发送通知提醒用户重新登录
                    if (e is AuthenticationException) {
                        Log.w(TAG, "Authentication error detected, notifying user to re-login")
                        notificationHelper.showReLoginNotification(e.message)
                    }
                    
                    ListenableWorker.Result.failure() 
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
}
