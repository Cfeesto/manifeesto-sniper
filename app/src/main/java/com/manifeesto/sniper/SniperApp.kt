package com.manifeesto.sniper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.Security

class SniperApp : Application() {

    companion object {
        const val CHANNEL_ID = "sniper_bot_channel"
        const val CHANNEL_NAME = "Sniper Bot"
        const val TAG = "ManifeestoSniper"
    }

    override fun onCreate() {
        super.onCreate()
        // 注册完整 BouncyCastle — Android 自带的是阉割版，缺少 CustomNamedCurves
        // web3j secp256k1 签名需要完整版本，必须在任何 web3j 调用之前注册
        installBouncyCastle()
        setupCrashHandler()
        createNotificationChannel()
    }

    private fun installBouncyCastle() {
        try {
            // 移除 Android 自带的残缺版本
            Security.removeProvider("BC")
            // 插入完整版到第一位置
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (e: Exception) {
            Log.w(TAG, "BouncyCastle install warning: ${e.message}")
        }
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

