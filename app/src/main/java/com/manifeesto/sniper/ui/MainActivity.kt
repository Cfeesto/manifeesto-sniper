package com.manifeesto.sniper.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.manifeesto.sniper.databinding.ActivityMainBinding
import com.manifeesto.sniper.databinding.DialogWalletSetupBinding
import com.manifeesto.sniper.service.BotService
import com.manifeesto.sniper.util.WalletManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var walletManager: WalletManager
    private val logLines = mutableListOf<String>()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(BotService.EXTRA_STATUS_MSG) ?: return
            appendLog(msg)
            updateStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        walletManager = WalletManager(this)

        binding.btnStartBot.setOnClickListener { onStartBot() }
        binding.btnStopBot.setOnClickListener { stopBot() }
        binding.btnWalletSetup.setOnClickListener { showWalletDialog() }

        updateStatus()
        updateWalletDisplay()

        if (!walletManager.isConfigured()) {
            appendLog("Wallet not configured. Please set up your wallet first.")
            showWalletDialog()
        } else {
            appendLog("Wallet configured: ${walletManager.getWalletAddress().take(10)}...")
            appendLog("Tap START BOT to begin farming.")
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(BotService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun onStartBot() {
        if (!walletManager.isConfigured()) {
            Toast.makeText(this, "Set up your wallet first!", Toast.LENGTH_SHORT).show()
            showWalletDialog()
            return
        }
        startBot()
    }

    private fun startBot() {
        val intent = Intent(this, BotService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        appendLog("Bot starting...")
        updateStatus()
    }

    private fun stopBot() {
        val intent = Intent(this, BotService::class.java)
        stopService(intent)
        appendLog("Bot stopped.")
        updateStatus()
    }

    private fun showWalletDialog() {
        val dialogBinding = DialogWalletSetupBinding.inflate(LayoutInflater.from(this))
        dialogBinding.etWalletAddress.setText(walletManager.getWalletAddress())
        dialogBinding.etWithdrawAddress.setText(walletManager.getWithdrawAddress())

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Wallet Setup")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val address = dialogBinding.etWalletAddress.text.toString().trim()
                val privateKey = dialogBinding.etPrivateKey.text.toString().trim()
                val withdrawAddr = dialogBinding.etWithdrawAddress.text.toString().trim()

                if (address.isEmpty() || privateKey.isEmpty()) {
                    Toast.makeText(this, "Address and private key are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    walletManager.saveWallet(address, privateKey, withdrawAddr)
                    runOnUiThread {
                        updateWalletDisplay()
                        appendLog("Wallet saved: ${address.take(10)}...")
                        Toast.makeText(this@MainActivity, "Wallet saved!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateStatus() {
        val running = BotService.isRunning
        binding.tvStatus.text = if (running) "ACTIVE" else "STOPPED"
        binding.tvStatus.setTextColor(
            if (running) getColor(android.R.color.holo_green_light)
            else getColor(android.R.color.holo_red_light)
        )
        binding.btnStartBot.isEnabled = !running
        binding.btnStopBot.isEnabled = running
    }

    private fun updateWalletDisplay() {
        val addr = walletManager.getWalletAddress()
        binding.tvWalletAddr.text = if (addr.isEmpty()) "Not configured"
        else "${addr.take(6)}...${addr.takeLast(4)}"
    }

    private fun appendLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        logLines.add("[$ts] $msg")
        if (logLines.size > 100) logLines.removeAt(0)
        binding.tvLogs.text = logLines.joinToString("\n")
        binding.scrollLogs.post { binding.scrollLogs.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}
