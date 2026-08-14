package com.palmagent.app.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.palmagent.app.AgentApplication
import com.palmagent.app.R
import com.palmagent.app.floating.UserActionManager.UserActionRequest

/**
 * 系统通知兜底
 *
 * 当悬浮窗被遮挡时，通过系统通知提醒用户需要手动操作。
 * 使用高优先级通知 + 震动，确保用户不会错过。
 */
object UserGuideNotifier {

    private const val TAG = "UserGuideNotifier"
    private const val CHANNEL_ID = "user_action_channel"
    private const val NOTIFICATION_ID = 1001

    private var notificationManager: NotificationManager? = null
    private var initialized = false

    /**
     * 初始化通知渠道（在Application.onCreate中调用）
     */
    fun init(context: Context) {
        if (initialized) return
        try {
            notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "用户操作提示",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Agent需要用户手动操作时的通知"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(100, 200, 100)
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                notificationManager?.createNotificationChannel(channel)
            }
            initialized = true
        } catch (e: Exception) {
            Log.e(TAG, "初始化通知渠道失败: ${e.message}")
        }
    }

    /**
     * 显示用户操作通知
     */
    fun showNotification(request: UserActionRequest) {
        if (!initialized) {
            init(AgentApplication.instance)
        }
        val nm = notificationManager ?: return

        try {
            val context = AgentApplication.instance
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, Class.forName("com.palmagent.app.ui.log.LogViewerActivity")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val stepsText = if (request.steps.isNotEmpty()) {
                "${request.steps.size}个步骤待完成，点击查看详情"
            } else {
                "点击查看详情"
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("🖐 ${request.title}")
                .setContentText(stepsText)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(
                            if (request.steps.isNotEmpty()) request.steps.joinToString("\n")
                            else request.title
                        )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_VIBRATE)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "显示通知失败: ${e.message}")
        }
    }

    /**
     * 取消通知
     */
    fun cancelNotification() {
        try {
            notificationManager?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }
}
