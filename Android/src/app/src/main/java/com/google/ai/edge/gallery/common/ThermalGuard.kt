package com.google.ai.edge.gallery.common

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.delay

/**
 * ThermalGuard monitors device temperature via Android's ThermalService and
 * enforces mandatory cool-down pauses before each LLM inference call.
 *
 * Android thermal severity levels:
 *  0 = NONE      → Normal operation
 *  1 = LIGHT     → Minor throttling; proceed with short delay
 *  2 = MODERATE  → GPU clock cut; pause and wait
 *  3 = SEVERE    → Aggressive throttle; longer pause
 *  4 = CRITICAL  → Emergency; abort inference
 *  5+= EMERGENCY / SHUTDOWN
 */
object ThermalGuard {
    private const val TAG = "AGThermalGuard"

    // Minimum cool-down between any two inference calls (ms)
    private const val INTER_INFERENCE_DELAY_MS = 1500L

    // Max thermal status before we abort inference entirely (CRITICAL = 4)
    private const val ABORT_THRESHOLD = 4

    // Max thermal status before we pause and wait (MODERATE = 2)
    private const val PAUSE_THRESHOLD = 2

    // How long to wait when MODERATE (ms)
    private const val MODERATE_COOLDOWN_MS = 8_000L

    // How long to wait when SEVERE (ms)
    private const val SEVERE_COOLDOWN_MS = 20_000L

    // Max total time we'll wait for the device to cool (ms)
    private const val MAX_WAIT_MS = 90_000L

    /**
     * Call this BEFORE each Gemma inference. Returns true if safe to proceed,
     * false if the device is too hot and inference should be skipped/aborted.
     */
    suspend fun awaitSafeTemperature(context: Context): Boolean {
        // Mandatory inter-inference breathing room
        delay(INTER_INFERENCE_DELAY_MS)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true // Can't check, proceed optimistically

        val startWait = System.currentTimeMillis()

        while (true) {
            val status = getThermalStatus(powerManager)
            val elapsed = System.currentTimeMillis() - startWait

            when {
                status >= ABORT_THRESHOLD -> {
                    Log.e(TAG, "Thermal status CRITICAL ($status) — aborting inference to protect device.")
                    return false
                }
                status >= PAUSE_THRESHOLD -> {
                    val cooldown = if (status >= 3) SEVERE_COOLDOWN_MS else MODERATE_COOLDOWN_MS
                    Log.w(TAG, "Thermal status=$status — pausing ${cooldown / 1000}s to cool down (elapsed=${elapsed / 1000}s)...")
                    delay(cooldown)
                    // Re-check after cool-down
                }
                else -> {
                    // NONE or LIGHT — safe to proceed
                    if (status == 1) {
                        Log.d(TAG, "Thermal status LIGHT — proceeding with extra 2s delay.")
                        delay(2000L)
                    }
                    return true
                }
            }
        }
    }

    fun getCurrentStatus(context: Context): Int {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return 0
        return getThermalStatus(powerManager)
    }

    fun statusLabel(status: Int): String = when (status) {
        0 -> "Normal"
        1 -> "Light throttling"
        2 -> "Moderate throttling"
        3 -> "Severe throttling"
        4 -> "Critical — inference paused"
        5 -> "Emergency"
        else -> "Shutdown"
    }

    private fun getThermalStatus(powerManager: PowerManager): Int {
        return try {
            powerManager.currentThermalStatus
        } catch (e: Exception) {
            Log.w(TAG, "Could not read thermal status", e)
            0
        }
    }
}
