package com.ustc.scoreminder.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ustc.scoreminder.MainActivity
import com.ustc.scoreminder.R
import com.ustc.scoreminder.domain.model.Grade
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    init {
        createNotificationChannels()
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val gradeChannel = NotificationChannel(
                CHANNEL_ID,
                "成绩通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "新成绩发布通知"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(gradeChannel)
            
            val authChannel = NotificationChannel(
                AUTH_CHANNEL_ID,
                "登录状态",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "登录过期或凭证失效通知"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(authChannel)
        }
    }
    
    fun showNewGradeNotification(grades: List<Grade>) {
        if (grades.isEmpty()) return
        
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 
            0, 
            intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val title = if (grades.size == 1) {
            "新成绩: ${grades.first().courseName}"
        } else {
            "发现 ${grades.size} 门新成绩"
        }
        
        val content = if (grades.size == 1) {
            val grade = grades.first()
            "${grade.courseName}: ${grade.score}"
        } else {
            grades.take(3).joinToString(", ") { "${it.courseName}: ${it.score}" } +
                if (grades.size > 3) " ..." else ""
        }
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * 显示需要重新登录的通知
     * 当后台同步检测到凭证失效时调用
     */
    fun showReLoginNotification(errorMessage: String? = null) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("needs_re_login", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val content = errorMessage ?: "登录状态已过期，请重新输入用户名和密码"
        
        val notification = NotificationCompat.Builder(context, AUTH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("需要重新登录")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(AUTH_NOTIFICATION_ID, notification)
    }
    
    companion object {
        const val CHANNEL_ID = "grade_notification_channel"
        const val AUTH_CHANNEL_ID = "auth_notification_channel"
        const val NOTIFICATION_ID = 1001
        const val AUTH_NOTIFICATION_ID = 1002
    }
}
