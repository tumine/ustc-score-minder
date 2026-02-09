package com.ustc.scoreminder.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.ustc.scoreminder.data.local.CredentialsManager
import com.ustc.scoreminder.domain.usecase.SyncGradesUseCase
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
        
        fun buildRequest(intervalMinutes: Long): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            return PeriodicWorkRequestBuilder<GradeSyncWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10, TimeUnit.MINUTES
                )
                .build()
        }
    }
    
    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting grade sync work")
        
        // 检查是否有凭证
        if (!credentialsManager.hasCredentials()) {
            Log.d(TAG, "No credentials, skipping sync")
            return Result.success()
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
                    
                    Result.success()
                },
                onFailure = { e ->
                    Log.e(TAG, "Sync failed", e)
                    Result.retry()
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Work failed with exception", e)
            Result.retry()
        }
    }
}
