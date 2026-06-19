package com.manifeesto.sniper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class SniperApp : Application() {

    companion object {
        const val CHANNEL_ID = "sniper_bot_channel"
        const val CHANNEL_NAME = "Sniper Bot"
        const val TAG = "ManifeestoSniper"
    }

    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
        createNotificationChannel()
    }

    // 捕获未处理异常并写入日志文件
    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val crashLog = "CRASH on thread ${thread.name}:\n$sw"
                Log.e(TAG, crashLog)
                // 写入文件供用户查看
                val logFile = File(filesDir, "crash_log.txt")
                logFile.writeText(crashLog)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Manifeesto Sniper bot status"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
