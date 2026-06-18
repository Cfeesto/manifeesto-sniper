package com.manifeesto.sniper.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.manifeesto.sniper.databinding.ActivityMainBinding
import com.manifeesto.sniper.service.BotService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateStatus()

        binding.btnStartBot.setOnClickListener {
            startBot()
        }

        binding.btnStopBot.setOnClickListener {
            stopBot()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun startBot() {
        val intent = Intent(this, BotService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStatus()
        Toast.makeText(this, "Bot started — running 24/7", Toast.LENGTH_SHORT).show()
    }

    private fun stopBot() {
        val intent = Intent(this, BotService::class.java)
        stopService(intent)
        updateStatus()
        Toast.makeText(this, "Bot stopped", Toast.LENGTH_SHORT).show()
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
}
