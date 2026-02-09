package com.ustc.scoreminder

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.*
import com.ustc.scoreminder.data.local.CredentialsManager
import com.ustc.scoreminder.worker.GradeSyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ScoreMinderApp : Application(), Configuration.Provider {
    
    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    
    @Inject
    lateinit var credentialsManager: CredentialsManager
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    
    override fun onCreate() {
        super.onCreate()
        
        // 如果已登录，启动定时同步
        if (credentialsManager.hasCredentials()) {
            scheduleGradeSync()
        }
    }
    
    fun scheduleGradeSync() {
        val intervalMinutes = credentialsManager.getSyncIntervalMinutes().toLong()
        
        val workRequest = GradeSyncWorker.buildRequest(intervalMinutes)
        
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            GradeSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }
    
    fun cancelGradeSync() {
        WorkManager.getInstance(this).cancelUniqueWork(GradeSyncWorker.WORK_NAME)
    }
}
