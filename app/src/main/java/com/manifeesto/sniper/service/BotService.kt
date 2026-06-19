package com.manifeesto.sniper.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.manifeesto.sniper.SniperApp
import com.manifeesto.sniper.scanner.AirdropScanner
import com.manifeesto.sniper.scanner.OpportunityHunter
import com.manifeesto.sniper.scanner.TestnetFarmer
import com.manifeesto.sniper.executor.WithdrawExecutor
import com.manifeesto.sniper.ui.MainActivity
import kotlinx.coroutines.*

class BotService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var scanner: AirdropScanner
    private lateinit var farmer: TestnetFarmer
    private lateinit var withdrawExecutor: WithdrawExecutor
    private lateinit var opportunityHunter: OpportunityHunter

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_STATUS_UPDATE = "com.manifeesto.sniper.STATUS_UPDATE"
        const val EXTRA_STATUS_MSG = "status_msg"
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        try {
            scanner = AirdropScanner()
            farmer = TestnetFarmer(this)
            withdrawExecutor = WithdrawExecutor(this)
            opportunityHunter = OpportunityHunter()
            acquireWakeLock()
        } catch (e: Exception) {
            broadcast("Init error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Bot active — scanning internet..."))
            isRunning = true
            broadcast("Bot started. AI scanning every 5 minutes.")
            startBotLoop()
        } catch (e: Exception) {
            broadcast("Start error: ${e.message}")
            isRunning = false
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        try { wakeLock?.release() } catch (_: Exception) {}
        broadcast("Bot stopped.")
    }

    private fun startBotLoop() {
        scope.launch {
            while (isActive) {
                try { runCycle() } catch (e: Exception) {
                    broadcast("Cycle error: ${e.message?.take(80)}")
                }
                delay(5 * 60 * 1000L)
            }
        }
    }

    private suspend fun runCycle() {
        // 1. AI 扫描互联网寻找机会
        broadcast("AI scanning internet for free crypto opportunities...")
        updateNotification("AI scanning...")
        val opportunities = try {
            opportunityHunter.scanAllSources()
        } catch (e: Exception) { emptyList() }

        if (opportunities.isNotEmpty()) {
            broadcast("Found ${opportunities.size} opportunities:")
            opportunities.take(5).forEach { opp ->
                broadcast("  [${opp.type}] ${opp.name} — est. \$${opp.estimatedValueUsd.toInt()}")
            }
        }

        // 2. 扫描活跃空投活动
        broadcast("Scanning airdrop campaigns...")
        val campaigns = try { scanner.fetchActiveCampaigns() } catch (e: Exception) { emptyList() }
        broadcast("Found ${campaigns.size} active campaigns")

        // 3. 执行链上操作
        campaigns.forEach { campaign ->
            try {
                broadcast("Farming: ${campaign.name}...")
                farmer.farm(campaign)
                broadcast("Farmed: ${campaign.name}")
            } catch (e: Exception) {
                broadcast("Farm error (${campaign.name}): ${e.message?.take(50)}")
            }
        }

        // 4. 检查并领取可用空投
        try {
            val claimable = scanner.fetchClaimableAirdrops()
            if (claimable.isNotEmpty()) {
                broadcast("Claiming ${claimable.size} airdrops → sending to your wallet...")
                claimable.forEach { airdrop ->
                    try {
                        withdrawExecutor.claimAndWithdraw(airdrop)
                        broadcast("Claimed + sent: ${airdrop.tokenSymbol} to wallet")
                    } catch (e: Exception) {
                        broadcast("Claim error: ${e.message?.take(50)}")
                    }
                }
            } else {
                broadcast("No claimable airdrops yet — keep farming.")
            }
        } catch (e: Exception) {
            broadcast("Check error: ${e.message}")
        }

        broadcast("Cycle complete. Next scan in 5 min.")
        updateNotification("Idle — next scan in 5 min")
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ManifeestoSniper::BotWakeLock")
                .apply { acquire(10 * 60 * 1000L) }
        } catch (_: Exception) {}
    }

    private fun broadcast(msg: String) {
        try {
            sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
                putExtra(EXTRA_STATUS_MSG, msg)
                setPackage(packageName)
            })
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, SniperApp.CHANNEL_ID)
            .setContentTitle("Manifeesto Sniper")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }
}
