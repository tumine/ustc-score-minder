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
                    // 对于手动同步，我们可能希望它 Fail 而不是 Retry，以便用户知道出错了
                    // 这里如果是 OneTimeRequest (runAttemptCount == 0)，我们可以 Fail
                    // 但为了简单，我们如果是网络错误可能还是想 Retry
                    // 暂时改为 Failure 以确保 UI 刷新显示错误信息
                    ListenableWorker.Result.failure() 
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Work failed with exception", e)
            credentialsManager.setLastSyncTime(System.currentTimeMillis())
            credentialsManager.setLastSyncResult("异常: ${e.message}")
            ListenableWorker.Result.failure()
        }
    }
}
