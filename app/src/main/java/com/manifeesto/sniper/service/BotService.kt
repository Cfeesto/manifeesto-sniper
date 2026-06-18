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
 * 保持扫描、农耕、自动提现循环持续运行
 */
class BotService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private val scanner = AirdropScanner()
    private val farmer = TestnetFarmer()
    private val withdrawExecutor = WithdrawExecutor()

    companion object {
        const val NOTIFICATION_ID = 1001
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Bot active — scanning..."))
        isRunning = true
        startBotLoop()
        // 若服务被杀死，系统自动重启
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        wakeLock?.release()
    }

    // ─── 主循环 ───────────────────────────────────────────────
    private fun startBotLoop() {
        scope.launch {
            while (isActive) {
                try {
                    runCycle()
                } catch (e: Exception) {
                    updateNotification("Error: ${e.message?.take(60)}")
                }
                // 每 5 分钟扫描一次，避免过热
                delay(5 * 60 * 1000L)
            }
        }
    }

    private suspend fun runCycle() {
        updateNotification("Scanning airdrop campaigns...")

        // 1. 扫描新空投活动
        val campaigns = scanner.fetchActiveCampaigns()
        updateNotification("Found ${campaigns.size} active campaigns")

        // 2. 为每个活动执行链上操作
        campaigns.forEach { campaign ->
            try {
                farmer.farm(campaign)
            } catch (e: Exception) {
                // 单个活动失败不影响其他活动
            }
        }

        // 3. 检查可领取的空投并自动提现
        val claimable = scanner.fetchClaimableAirdrops()
        if (claimable.isNotEmpty()) {
            updateNotification("Claiming ${claimable.size} airdrops...")
            claimable.forEach { airdrop ->
                withdrawExecutor.claimAndWithdraw(airdrop)
            }
        }

        updateNotification("Cycle complete. Next scan in 5 min.")
    }

    // ─── 唤醒锁 — 防止 CPU 在扫描中休眠 ─────────────────────
    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ManifeestoSniper::BotWakeLock"
        ).apply { acquire(10 * 60 * 1000L) } // 最多持锁 10 分钟，循环续锁
    }

    // ─── 通知 ─────────────────────────────────────────────────
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
