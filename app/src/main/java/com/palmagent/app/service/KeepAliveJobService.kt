package com.palmagent.app.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

class KeepAliveJobService : JobService() {

    companion object {
        private const val TAG = "KeepAliveJob"
        private const val JOB_ID = 10086
        private const val INTERVAL_MS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.getPendingJob(JOB_ID) != null) return

            val jobInfo = JobInfo.Builder(JOB_ID, ComponentName(context, KeepAliveJobService::class.java))
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .build()

            val result = scheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.i(TAG, "KeepAlive job scheduled")
            } else {
                Log.e(TAG, "KeepAlive job schedule failed")
            }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "KeepAlive job triggered, ForegroundService running: ${ForegroundService.isRunning()}, A11yService running: ${GUIAccessibilityService.isRunning}")
        if (!ForegroundService.isRunning()) {
            ForegroundService.start(applicationContext)
        }
        // 检查无障碍服务状态，尝试编程恢复
        if (!GUIAccessibilityService.isRunning) {
            val restored = AccessibilityServiceHelper.ensureServiceEnabled(applicationContext)
            Log.i(TAG, "无障碍服务未运行，编程恢复结果: $restored")
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}