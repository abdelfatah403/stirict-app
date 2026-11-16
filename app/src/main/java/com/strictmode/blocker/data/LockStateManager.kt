package com.strictmode.blocker.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LockSessionState(
    val isLocked: Boolean = false,
    val remainingMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val blockedPackages: Set<String> = emptySet(),
    val blockSettingsAndUninstall: Boolean = true,
    val blockPowerMenu: Boolean = true,
    val tamperAttempts: Int = 0,
    val lastBlockedReason: String? = null
)

object LockStateManager {
    private const val PREFS_NAME = "strict_lock_direct_boot_prefs"

    private const val KEY_IS_LOCKED = "is_locked"
    private const val KEY_TOTAL_DURATION_MS = "total_duration_ms"
    private const val KEY_ACCUMULATED_ELAPSED_MS = "accumulated_elapsed_ms"
    private const val KEY_LAST_BOOT_REALTIME_MS = "last_boot_realtime_ms"
    private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    private const val KEY_BLOCK_SETTINGS = "block_settings"
    private const val KEY_BLOCK_POWER_MENU = "block_power_menu"
    private const val KEY_TAMPER_ATTEMPTS = "tamper_attempts"
    private const val KEY_LAST_BLOCKED_REASON = "last_blocked_reason"

    private val _stateFlow = MutableStateFlow(LockSessionState())
    val stateFlow: StateFlow<LockSessionState> = _stateFlow.asStateFlow()

    /**
     * Uses Device-Protected Storage so the lock state is readable immediately on boot
     * (Direct Boot mode) even before the user unlocks their lockscreen PIN/pattern.
     */
    private fun getPrefs(context: Context): SharedPreferences {
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.applicationContext.createDeviceProtectedStorageContext()
        } else {
            context.applicationContext
        }
        return storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Starts a new strict lock session using the monotonic hardware clock
     * (SystemClock.elapsedRealtime()) so changing the system date/time cannot cheat the timer.
     */
    @Synchronized
    fun startLockSession(
        context: Context,
        durationMinutes: Int,
        blockedPackages: Set<String>,
        blockSettingsAndUninstall: Boolean = true,
        blockPowerMenu: Boolean = true
    ) {
        val totalDurationMs = durationMinutes.coerceAtLeast(1) * 60_000L
        val nowRealtime = SystemClock.elapsedRealtime()
        val prefs = getPrefs(context)

        prefs.edit()
            .putBoolean(KEY_IS_LOCKED, true)
            .putLong(KEY_TOTAL_DURATION_MS, totalDurationMs)
            .putLong(KEY_ACCUMULATED_ELAPSED_MS, 0L)
            .putLong(KEY_LAST_BOOT_REALTIME_MS, nowRealtime)
            .putStringSet(KEY_BLOCKED_PACKAGES, blockedPackages)
            .putBoolean(KEY_BLOCK_SETTINGS, blockSettingsAndUninstall)
            .putBoolean(KEY_BLOCK_POWER_MENU, blockPowerMenu)
            .putInt(KEY_TAMPER_ATTEMPTS, 0)
            .remove(KEY_LAST_BLOCKED_REASON)
            .commit()

        refreshState(context)
    }

    /**
     * Updates the monotonic elapsed time checkpoint and returns the current LockSessionState.
     * Completely immune to manual system clock manipulation.
     * Across hardware reboots, resumes from the last persisted accumulated time + new boot uptime.
     */
    @Synchronized
    fun refreshState(context: Context): LockSessionState {
        val prefs = getPrefs(context)
        val isLocked = prefs.getBoolean(KEY_IS_LOCKED, false)

        if (!isLocked) {
            val unlockedState = LockSessionState(
                isLocked = false,
                blockedPackages = prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet()) ?: emptySet(),
                blockSettingsAndUninstall = prefs.getBoolean(KEY_BLOCK_SETTINGS, true),
                blockPowerMenu = prefs.getBoolean(KEY_BLOCK_POWER_MENU, true)
            )
            _stateFlow.value = unlockedState
            return unlockedState
        }

        val totalDurationMs = prefs.getLong(KEY_TOTAL_DURATION_MS, 0L)
        val previousAccumulatedMs = prefs.getLong(KEY_ACCUMULATED_ELAPSED_MS, 0L)
        val lastBootRealtimeMs = prefs.getLong(KEY_LAST_BOOT_REALTIME_MS, 0L)
        val nowRealtimeMs = SystemClock.elapsedRealtime()

        // If nowRealtimeMs >= lastBootRealtimeMs, we are in the same boot session.
        // If nowRealtimeMs < lastBootRealtimeMs, the device rebooted, so delta since reboot is nowRealtimeMs.
        val deltaMs = if (nowRealtimeMs >= lastBootRealtimeMs && lastBootRealtimeMs > 0L) {
            nowRealtimeMs - lastBootRealtimeMs
        } else {
            // Device rebooted: count monotonic time elapsed since the reboot
            nowRealtimeMs.coerceAtLeast(0L)
        }

        val newAccumulatedMs = (previousAccumulatedMs + deltaMs).coerceAtMost(totalDurationMs)
        val remainingMs = (totalDurationMs - newAccumulatedMs).coerceAtLeast(0L)

        if (remainingMs <= 0L) {
            // Timer completed legitimately!
            prefs.edit()
                .putBoolean(KEY_IS_LOCKED, false)
                .putLong(KEY_ACCUMULATED_ELAPSED_MS, 0L)
                .putLong(KEY_LAST_BOOT_REALTIME_MS, 0L)
                .remove(KEY_LAST_BLOCKED_REASON)
                .commit()

            val completedState = LockSessionState(
                isLocked = false,
                remainingMs = 0L,
                totalDurationMs = 0L,
                blockedPackages = prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet()) ?: emptySet(),
                blockSettingsAndUninstall = prefs.getBoolean(KEY_BLOCK_SETTINGS, true),
                blockPowerMenu = prefs.getBoolean(KEY_BLOCK_POWER_MENU, true),
                tamperAttempts = prefs.getInt(KEY_TAMPER_ATTEMPTS, 0)
            )
            _stateFlow.value = completedState
            return completedState
        }

        // Persist checkpoint so forced power-offs cannot lose progress or reset state
        prefs.edit()
            .putLong(KEY_ACCUMULATED_ELAPSED_MS, newAccumulatedMs)
            .putLong(KEY_LAST_BOOT_REALTIME_MS, nowRealtimeMs)
            .commit()

        val activeState = LockSessionState(
            isLocked = true,
            remainingMs = remainingMs,
            totalDurationMs = totalDurationMs,
            blockedPackages = prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet()) ?: emptySet(),
            blockSettingsAndUninstall = prefs.getBoolean(KEY_BLOCK_SETTINGS, true),
            blockPowerMenu = prefs.getBoolean(KEY_BLOCK_POWER_MENU, true),
            tamperAttempts = prefs.getInt(KEY_TAMPER_ATTEMPTS, 0),
            lastBlockedReason = prefs.getString(KEY_LAST_BLOCKED_REASON, null)
        )
        _stateFlow.value = activeState
        return activeState
    }

    /**
     * Records a cheat/tamper or blocked-app open attempt during an active lock.
     */
    @Synchronized
    fun recordViolation(context: Context, reason: String) {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_IS_LOCKED, false)) return
        val attempts = prefs.getInt(KEY_TAMPER_ATTEMPTS, 0) + 1
        prefs.edit()
            .putInt(KEY_TAMPER_ATTEMPTS, attempts)
            .putString(KEY_LAST_BLOCKED_REASON, reason)
            .commit()
        refreshState(context)
    }

    /**
     * Saves the user's selected blocked packages even before starting the timer.
     */
    @Synchronized
    fun saveSelectedPackages(context: Context, packages: Set<String>) {
        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_IS_LOCKED, false)) return // Cannot edit while locked!
        prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, packages).apply()
        refreshState(context)
    }

    /**
     * Called when SHUTDOWN or REBOOT broadcast arrives to anchor the monotonic counter.
     */
    @Synchronized
    fun onDeviceShuttingDown(context: Context) {
        val state = refreshState(context)
        if (state.isLocked) {
            val prefs = getPrefs(context)
            // Reset last_boot_realtime_ms to 0 so the next boot starts counting from 0
            prefs.edit().putLong(KEY_LAST_BOOT_REALTIME_MS, 0L).commit()
        }
    }
}
