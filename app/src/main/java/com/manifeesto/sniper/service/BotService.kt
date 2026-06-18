package com.manifeesto.sniper.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.manifeesto.sniper.R
import com.manifeesto.sniper.SniperApp
import com.manifeesto.sniper.scanner.AirdropScanner
import com.manifeesto.sniper.scanner.TestnetFarmer
import com.manifeesto.sniper.executor.WithdrawExecutor
import com.manifeesto.sniper.ui.MainActivity
import kotlinx.coroutines.*

/**
 * 24/7 前台服务 — 核心 bot 运行环境
 */
class BotService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private val scanner = AirdropScanner()
    private val farmer = TestnetFarmer()
    private val withdrawExecutor = WithdrawExecutor()

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_STATUS_UPDATE = "com.manifeesto.sniper.STATUS_UPDATE"
        const val EXTRA_STATUS_MSG = "status_msg"
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Bot active — scanning..."))
        isRunning = true
        broadcast("Bot service started. Scanning every 5 minutes.")
        startBotLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        wakeLock?.release()
        broadcast("Bot service stopped.")
    }

    private fun startBotLoop() {
        scope.launch {
            while (isActive) {
                try {
                    runCycle()
                } catch (e: Exception) {
                    val msg = "Error: ${e.message?.take(80)}"
                    updateNotification(msg)
                    broadcast(msg)
                }
                delay(5 * 60 * 1000L)
            }
        }
    }

    private suspend fun runCycle() {
        broadcast("Scanning airdrop campaigns...")
        updateNotification("Scanning airdrop campaigns...")

        val campaigns = scanner.fetchActiveCampaigns()
        broadcast("Found ${campaigns.size} active campaigns: ${campaigns.joinToString { it.name }}")
        updateNotification("${campaigns.size} campaigns active")

        campaigns.forEach { campaign ->
            try {
                broadcast("Farming: ${campaign.name}...")
                farmer.farm(campaign)
                broadcast("Farming cycle done: ${campaign.name}")
            } catch (e: Exception) {
                broadcast("Farm error (${campaign.name}): ${e.message?.take(60)}")
            }
        }

        val claimable = scanner.fetchClaimableAirdrops()
        if (claimable.isNotEmpty()) {
            broadcast("Claiming ${claimable.size} airdrops...")
            updateNotification("Claiming ${claimable.size} airdrops...")
            claimable.forEach { airdrop ->
                withdrawExecutor.claimAndWithdraw(airdrop)
                broadcast("Claimed: ${airdrop.tokenSymbol} on ${airdrop.network.name}")
            }
        } else {
            broadcast("No claimable airdrops yet.")
        }

        broadcast("Cycle complete. Next scan in 5 min.")
        updateNotification("Idle — next scan in 5 min")
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ManifeestoSniper::BotWakeLock"
        ).apply { acquire(10 * 60 * 1000L) }
    }

    private fun broadcast(msg: String) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS_MSG, msg)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, SniperApp.CHANNEL_ID)
            .setContentTitle("Manifeesto Sniper")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
