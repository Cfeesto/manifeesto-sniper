package com.manifeesto.sniper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class SniperApp : Application() {

    companion object {
        const val CHANNEL_ID = "sniper_bot_channel"
        const val CHANNEL_NAME = "Sniper Bot"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
