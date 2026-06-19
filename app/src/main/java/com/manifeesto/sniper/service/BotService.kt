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

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_STATUS_UPDATE = "com.manifeesto.sniper.STATUS_UPDATE"
        const val EXTRA_STATUS_MSG = "status_msg"
        var isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground MUST be called immediately — before any other work
        startForeground(NOTIFICATION_ID, buildNotification("Bot starting..."))
        isRunning = true

        // All heavy work runs on background thread — never blocks main thread
        scope.launch {
            try {
                acquireWakeLock()
                broadcast("Bot started. Initializing...")
                // Small delay to let UI settle before first scan
                delay(2000)
                runBotLoop()
            } catch (e: Exception) {
                broadcast("Fatal error: ${e.message}")
                isRunning = false
                stopSelf()
            }
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

    private suspend fun runBotLoop() {
        // Lazy init on background thread — avoids blocking main thread
        val scanner = AirdropScanner()
        val farmer = try { TestnetFarmer(applicationContext) } catch (e: Exception) {
            broadcast("Farmer init error: ${e.message}"); null
        }
        val withdrawExecutor = try { WithdrawExecutor(applicationContext) } catch (e: Exception) {
            broadcast("Withdraw init error: ${e.message}"); null
        }
        val opportunityHunter = OpportunityHunter()

        while (scope.isActive) {
            try {
                runCycle(scanner, farmer, withdrawExecutor, opportunityHunter)
            } catch (e: Exception) {
                broadcast("Cycle error: ${e.message?.take(80)}")
            }
            delay(5 * 60 * 1000L)
        }
    }

    private suspend fun runCycle(
        scanner: AirdropScanner,
        farmer: TestnetFarmer?,
        withdrawExecutor: WithdrawExecutor?,
        opportunityHunter: OpportunityHunter
    ) {
        // 1. AI internet scan
        broadcast("AI scanning internet for opportunities...")
        updateNotification("AI scanning...")
        val opportunities = try { opportunityHunter.scanAllSources() } catch (_: Exception) { emptyList() }
        if (opportunities.isNotEmpty()) {
            broadcast("Found ${opportunities.size} opportunities:")
            opportunities.take(3).forEach { opp ->
                broadcast("  [${opp.type}] ${opp.name} ~\$${opp.estimatedValueUsd.toInt()}")
            }
        }

        // 2. Farm active campaigns
        broadcast("Scanning campaigns...")
        val campaigns = try { scanner.fetchActiveCampaigns() } catch (_: Exception) { emptyList() }
        broadcast("${campaigns.size} active campaigns found")

        if (farmer != null) {
            campaigns.forEach { campaign ->
                try {
                    broadcast("Farming ${campaign.name}...")
                    farmer.farm(campaign)
                    broadcast("Done: ${campaign.name}")
                } catch (e: Exception) {
                    broadcast("Farm error: ${e.message?.take(50)}")
                }
            }
        }

        // 3. Claim and withdraw
        if (withdrawExecutor != null) {
            try {
                val claimable = scanner.fetchClaimableAirdrops()
                if (claimable.isNotEmpty()) {
                    broadcast("Claiming ${claimable.size} airdrops...")
                    claimable.forEach { airdrop ->
                        try {
                            withdrawExecutor.claimAndWithdraw(airdrop)
                            broadcast("Claimed: ${airdrop.tokenSymbol} → sent to wallet")
                        } catch (e: Exception) {
                            broadcast("Claim error: ${e.message?.take(50)}")
                        }
                    }
                } else {
                    broadcast("No claimable airdrops yet.")
                }
            } catch (e: Exception) {
                broadcast("Withdraw check error: ${e.message?.take(50)}")
            }
        }

        broadcast("Cycle done. Next scan in 5 min.")
        updateNotification("Running — next scan in 5 min")
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
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
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
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }
}
