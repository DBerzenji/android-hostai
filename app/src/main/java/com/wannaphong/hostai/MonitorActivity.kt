package com.wannaphong.hostai

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import androidx.appcompat.app.AppCompatActivity
import com.wannaphong.hostai.databinding.ActivityMonitorBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * "Best-effort" resource usage monitor.
 *
 * Android 8.0+ (SELinux / API restrictions) prevents standard apps from
 * reading total system CPU/GPU/NPU usage or most thermal sensors without
 * root/ADB access. This screen shows what IS available through public APIs:
 * RAM, battery-derived power (current/voltage/watts), battery temperature
 * and thermal throttling status, device-wide network throughput, and the
 * app's own CPU time (as an indirect proxy for inference load).
 */
class MonitorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMonitorBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollJob: Job? = null

    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastSampleTimeMs = 0L
    private var lastAppCpuTimeMs = 0L

    @Volatile private var batteryTempC: Float? = null
    @Volatile private var batteryVoltageMv: Int? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tempTenths != Int.MIN_VALUE) {
                batteryTempC = tempTenths / 10f
            }
            val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            if (voltage != Int.MIN_VALUE && voltage > 0) {
                batteryVoltageMv = voltage
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1000L
        private const val BYTES_PER_KB = 1024.0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonitorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.monitor_usage_title)

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        lastSampleTimeMs = System.currentTimeMillis()
        lastAppCpuTimeMs = Process.getElapsedCpuTime()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Receiver may already be unregistered; safe to ignore.
        }
        scope.cancel()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                updateAll()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun updateAll() {
        updateMemory()
        updatePower()
        updateThermal()
        updateNetwork()
        updateAppCpu()
    }

    private fun updateMemory() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)

        val totalMb = info.totalMem / (1024 * 1024)
        val availMb = info.availMem / (1024 * 1024)
        val usedMb = (totalMb - availMb).coerceAtLeast(0)
        val pct = if (totalMb > 0) ((usedMb * 100) / totalMb).toInt().coerceIn(0, 100) else 0

        binding.ramText.text = "$usedMb MB / $totalMb MB"
        binding.ramProgress.progress = pct
    }

    private fun updatePower() {
        try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            // Microamps; sign/availability vary by OEM, take magnitude for display.
            val currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val currentMa = currentUa / 1000.0
            val voltageV = (batteryVoltageMv ?: 0) / 1000.0
            val watts = abs(currentMa) / 1000.0 * voltageV

            binding.powerCurrentText.text = "Current: %.0f mA".format(currentMa)
            binding.powerVoltageText.text = "Voltage: %.2f V".format(voltageV)
            binding.powerWattsText.text = "Power: %.2f W".format(watts)
        } catch (e: Exception) {
            binding.powerCurrentText.text = "Current: unavailable"
            binding.powerVoltageText.text = "Voltage: unavailable"
            binding.powerWattsText.text = "Power: unavailable"
        }
    }

    private fun updateThermal() {
        val tempC = batteryTempC
        binding.batteryTempText.text = if (tempC != null) {
            "Battery: %.1f °C".format(tempC)
        } else {
            "Battery: unavailable"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val statusLabel = when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "None"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> "Unknown"
            }
            val headroom = pm.getThermalHeadroom(10)
            binding.thermalStatusText.text = if (!headroom.isNaN()) {
                "Status: $statusLabel (headroom: %.2f)".format(headroom)
            } else {
                "Status: $statusLabel"
            }
        } else {
            binding.thermalStatusText.text = "Status: requires Android 10+"
        }
    }

    private fun updateNetwork() {
        val now = System.currentTimeMillis()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val elapsedSec = ((now - lastSampleTimeMs).coerceAtLeast(1)) / 1000.0

        if (rx != TrafficStats.UNSUPPORTED.toLong() && lastRxBytes != TrafficStats.UNSUPPORTED.toLong()) {
            val downKbps = ((rx - lastRxBytes).coerceAtLeast(0) / BYTES_PER_KB) / elapsedSec
            binding.netDownText.text = "Down: %.1f KB/s".format(downKbps)
        } else {
            binding.netDownText.text = "Down: unavailable"
        }

        if (tx != TrafficStats.UNSUPPORTED.toLong() && lastTxBytes != TrafficStats.UNSUPPORTED.toLong()) {
            val upKbps = ((tx - lastTxBytes).coerceAtLeast(0) / BYTES_PER_KB) / elapsedSec
            binding.netUpText.text = "Up: %.1f KB/s".format(upKbps)
        } else {
            binding.netUpText.text = "Up: unavailable"
        }

        lastRxBytes = rx
        lastTxBytes = tx
        lastSampleTimeMs = now
    }

    private fun updateAppCpu() {
        val now = Process.getElapsedCpuTime()
        val deltaMs = (now - lastAppCpuTimeMs).coerceAtLeast(0)
        lastAppCpuTimeMs = now
        binding.cpuAppText.text = "App CPU time: $deltaMs ms / $POLL_INTERVAL_MS ms"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
