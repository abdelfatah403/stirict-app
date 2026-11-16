package com.strictmode.blocker.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class AppTimerState(
    val packageName: String,
    val remainingMs: Long,
    val totalDurationMs: Long
)

data class LockSessionState(
    val isLocked: Boolean = false,
    val remainingMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val blockedPackages: Set<String> = emptySet(),
    val appTimers: Map<String, AppTimerState> = emptyMap(),
    val blockSettingsAndUninstall: Boolean = true,
    val blockPowerMenu: Boolean = true,
    val tamperAttempts: Int = 0,
    val lastBlockedReason: String? = null,
    val lastBlockedPackage: String? = null
)

object LockStateManager {
    private const val PREFS_NAME = "strict_lock_direct_boot_prefs"

    private const val KEY_APP_TIMERS_JSON = "app_timers_json"
    private const val KEY_LAST_BOOT_REALTIME_MS = "last_boot_realtime_ms"
    private const val KEY_DRAFT_SELECTED_PACKAGES = "draft_selected_packages"
    private const val KEY_BLOCK_SETTINGS = "block_settings"
    private const val KEY_BLOCK_POWER_MENU = "block_power_menu"
    private const val KEY_TAMPER_ATTEMPTS = "tamper_attempts"
    private const val KEY_LAST_BLOCKED_REASON = "last_blocked_reason"
    private const val KEY_LAST_BLOCKED_PACKAGE = "last_blocked_package"

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
     * Locks one or multiple apps for [durationMinutes].
     * - If multiple apps are passed, they share the same countdown duration.
     * - If an app is already locked with a shorter remaining time, its timer is extended.
     *   (An active timer can never be shortened or cancelled early.)
     */
    @Synchronized
    fun startLockSession(
        context: Context,
        durationMinutes: Int,
        blockedPackages: Set<String>,
        blockSettingsAndUninstall: Boolean = true,
        blockPowerMenu: Boolean = true
    ) {
        val durationMs = durationMinutes.coerceAtLeast(1) * 60_000L
        lockPackagesWithDurationMs(
            context = context,
            durationMs = durationMs,
            packages = blockedPackages,
            blockSettingsAndUninstall = blockSettingsAndUninstall,
            blockPowerMenu = blockPowerMenu
        )
    }

    /**
     * Locks multiple apps where each app can either share the default duration or have its own
     * custom duration in minutes.
     */
    @Synchronized
    fun startLockSessionWithCustomTimers(
        context: Context,
        appDurationMinutesMap: Map<String, Int>,
        blockSettingsAndUninstall: Boolean = true,
        blockPowerMenu: Boolean = true
    ) {
        if (appDurationMinutesMap.isEmpty()) return
        refreshState(context)

        val prefs = getPrefs(context)
        val currentMap = readAppTimersMap(prefs).toMutableMap()
        val nowRealtime = SystemClock.elapsedRealtime()

        for ((pkg, mins) in appDurationMinutesMap) {
            val durationMs = mins.coerceAtLeast(1) * 60_000L
            val existing = currentMap[pkg]
            if (existing == null || durationMs > existing.remainingMs) {
                currentMap[pkg] = AppTimerState(
                    packageName = pkg,
                    remainingMs = durationMs,
                    totalDurationMs = maxOf(durationMs, existing?.totalDurationMs ?: durationMs)
                )
            }
        }

        prefs.edit()
            .putString(KEY_APP_TIMERS_JSON, serializeAppTimersMap(currentMap))
            .putLong(KEY_LAST_BOOT_REALTIME_MS, nowRealtime)
            .putBoolean(KEY_BLOCK_SETTINGS, blockSettingsAndUninstall)
            .putBoolean(KEY_BLOCK_POWER_MENU, blockPowerMenu)
            .commit()

        refreshState(context)
    }
    @Synchronized
    fun lockPackagesWithDurationMs(
        context: Context,
        durationMs: Long,
        packages: Set<String>,
        blockSettingsAndUninstall: Boolean? = null,
        blockPowerMenu: Boolean? = null
    ) {
        if (packages.isEmpty() || durationMs <= 0L) return

        // First advance any existing running timers to the current monotonic timestamp
        refreshState(context)

        val prefs = getPrefs(context)
        val currentMap = readAppTimersMap(prefs).toMutableMap()
        val nowRealtime = SystemClock.elapsedRealtime()

        for (pkg in packages) {
            val existing = currentMap[pkg]
            // Anti-cheat rule: never allow shortening an already running timer for an app
            if (existing == null || durationMs > existing.remainingMs) {
                currentMap[pkg] = AppTimerState(
                    packageName = pkg,
                    remainingMs = durationMs,
                    totalDurationMs = maxOf(durationMs, existing?.totalDurationMs ?: durationMs)
                )
            }
        }

        val editor = prefs.edit()
            .putString(KEY_APP_TIMERS_JSON, serializeAppTimersMap(currentMap))
            .putLong(KEY_LAST_BOOT_REALTIME_MS, nowRealtime)

        if (blockSettingsAndUninstall != null) {
            editor.putBoolean(KEY_BLOCK_SETTINGS, blockSettingsAndUninstall)
        }
        if (blockPowerMenu != null) {
            editor.putBoolean(KEY_BLOCK_POWER_MENU, blockPowerMenu)
        }

        editor.commit()
        refreshState(context)
    }

    /**
     * Adds additional apps to match the longest currently running timer.
     */
    @Synchronized
    fun addPackagesToActiveLock(context: Context, additionalPackages: Set<String>) {
        val state = refreshState(context)
        if (!state.isLocked || additionalPackages.isEmpty()) return
        lockPackagesWithDurationMs(
            context = context,
            durationMs = state.remainingMs,
            packages = additionalPackages
        )
    }

    /**
     * Updates all active per-app monotonic timers and removes any apps whose timers have finished.
     * Completely immune to manual system clock manipulation.
     */
    @Synchronized
    fun refreshState(context: Context): LockSessionState {
        val prefs = getPrefs(context)
        val currentMap = readAppTimersMap(prefs)

        if (currentMap.isEmpty()) {
            val unlockedState = LockSessionState(
                isLocked = false,
                remainingMs = 0L,
                totalDurationMs = 0L,
                blockedPackages = emptySet(),
                appTimers = emptyMap(),
                blockSettingsAndUninstall = prefs.getBoolean(KEY_BLOCK_SETTINGS, true),
                blockPowerMenu = prefs.getBoolean(KEY_BLOCK_POWER_MENU, true),
                tamperAttempts = prefs.getInt(KEY_TAMPER_ATTEMPTS, 0)
            )
            _stateFlow.value = unlockedState
            return unlockedState
        }

        val lastBootRealtimeMs = prefs.getLong(KEY_LAST_BOOT_REALTIME_MS, 0L)
        val nowRealtimeMs = SystemClock.elapsedRealtime()

        val deltaMs = if (nowRealtimeMs >= lastBootRealtimeMs && lastBootRealtimeMs > 0L) {
            nowRealtimeMs - lastBootRealtimeMs
        } else {
            // Device rebooted: count monotonic time elapsed since reboot
            nowRealtimeMs.coerceAtLeast(0L)
        }

        val updatedMap = mutableMapOf<String, AppTimerState>()
        for ((pkg, timerState) in currentMap) {
            val newRemaining = (timerState.remainingMs - deltaMs).coerceAtLeast(0L)
            if (newRemaining > 0L) {
                updatedMap[pkg] = timerState.copy(remainingMs = newRemaining)
            }
        }

        if (updatedMap.isEmpty()) {
            // All app timers completed!
            prefs.edit()
                .remove(KEY_APP_TIMERS_JSON)
                .putLong(KEY_LAST_BOOT_REALTIME_MS, 0L)
                .remove(KEY_LAST_BLOCKED_REASON)
                .remove(KEY_LAST_BLOCKED_PACKAGE)
                .commit()

            val completedState = LockSessionState(
                isLocked = false,
                remainingMs = 0L,
                totalDurationMs = 0L,
                blockedPackages = emptySet(),
                appTimers = emptyMap(),
                blockSettingsAndUninstall = prefs.getBoolean(KEY_BLOCK_SETTINGS, true),
                blockPowerMenu = prefs.getBoolean(KEY_BLOCK_POWER_MENU, true),
                tamperAttempts = prefs.getInt(KEY_TAMPER_ATTEMPTS, 0)
            )
            _stateFlow.value = completedState
            return completedState
        }

        // Persist updated per-app timers checkpoint
        prefs.edit()
            .putString(KEY_APP_TIMERS_JSON, serializeAppTimersMap(updatedMap))
            .putLong(KEY_LAST_BOOT_REALTIME_MS, nowRealtimeMs)
            .commit()

        val maxRemainingMs = updatedMap.values.maxOf { it.remainingMs }
        val maxTotalDurationMs = updatedMap.values.maxOf { it.totalDurationMs }

        val activeState = LockSessionState(
            isLocked = true,
            remainingMs = maxRemainingMs,
            totalDurationMs = maxTotalDurationMs,
            blockedPackages = updatedMap.keys,
            appTimers = updatedMap,
            blockSettingsAndUninstall = prefs.getBoolean(KEY_BLOCK_SETTINGS, true),
            blockPowerMenu = prefs.getBoolean(KEY_BLOCK_POWER_MENU, true),
            tamperAttempts = prefs.getInt(KEY_TAMPER_ATTEMPTS, 0),
            lastBlockedReason = prefs.getString(KEY_LAST_BLOCKED_REASON, null),
            lastBlockedPackage = prefs.getString(KEY_LAST_BLOCKED_PACKAGE, null)
        )
        _stateFlow.value = activeState
        return activeState
    }

    /**
     * Returns the remaining lock time for a specific package, or the overall max remaining time
     * if [packageName] is null (e.g. when Settings or Power Menu is blocked).
     */
    @Synchronized
    fun getRemainingMsForPackage(context: Context, packageName: String?): Long {
        val state = refreshState(context)
        if (!state.isLocked) return 0L
        if (packageName != null) {
            state.appTimers[packageName]?.let { return it.remainingMs }
        }
        return state.remainingMs
    }

    /**
     * Records a cheat/tamper or blocked-app open attempt during an active lock.
     */
    @Synchronized
    fun recordViolation(context: Context, reason: String, blockedPackage: String? = null) {
        val prefs = getPrefs(context)
        val state = refreshState(context)
        if (!state.isLocked) return
        val attempts = prefs.getInt(KEY_TAMPER_ATTEMPTS, 0) + 1
        val editor = prefs.edit()
            .putInt(KEY_TAMPER_ATTEMPTS, attempts)
            .putString(KEY_LAST_BLOCKED_REASON, reason)
        if (blockedPackage != null) {
            editor.putString(KEY_LAST_BLOCKED_PACKAGE, blockedPackage)
        } else {
            editor.remove(KEY_LAST_BLOCKED_PACKAGE)
        }
        editor.commit()
        refreshState(context)
    }

    /**
     * Saves draft selected packages in the UI picker.
     */
    @Synchronized
    fun saveSelectedPackages(context: Context, packages: Set<String>) {
        val prefs = getPrefs(context)
        prefs.edit().putStringSet(KEY_DRAFT_SELECTED_PACKAGES, packages).apply()
    }

    @Synchronized
    fun getDraftSelectedPackages(context: Context): Set<String> {
        val prefs = getPrefs(context)
        return prefs.getStringSet(KEY_DRAFT_SELECTED_PACKAGES, emptySet()) ?: emptySet()
    }

    @Volatile
    var activeAllowedUserAppPkg: String? = null
        private set

    @Volatile
    var activeAllowedUserAppTimeMs: Long = 0L
        private set

    private var cachedHomePackages: Set<String>? = null

    private val AUTH_PROMPT_KEYWORDS = listOf(
        "biometric",
        "fingerprint",
        "credential",
        "confirmlock",
        "password",
        "keyguard",
        "autofill",
        "faceunlock",
        "applock"
    )

    private val IGNORED_BACKGROUND_SYSTEM_PREFIXES = listOf(
        "com.android.systemui",
        "android",
        "com.google.android.inputmethod",
        "com.samsung.android.honeyboard",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller"
    )

    fun getHomeLauncherPackages(context: Context): Set<String> {
        cachedHomePackages?.let { if (it.isNotEmpty()) return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val pkgs = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
        if (pkgs.isNotEmpty()) {
            cachedHomePackages = pkgs
        }
        return pkgs
    }

    /**
     * Records foreground package transitions so we know whether the user is currently inside
     * an allowed user app (like InstaPay) or on the Home Screen.
     */
    @Synchronized
    fun onForegroundAppObserved(
        context: Context,
        pkg: String,
        state: LockSessionState
    ) {
        if (pkg == "com.android.settings") return
        if (IGNORED_BACKGROUND_SYSTEM_PREFIXES.any { pkg == it || pkg.startsWith(it) }) return
        if (pkg.contains("inputmethod") || pkg.contains("keyboard")) return

        val homePkgs = getHomeLauncherPackages(context)
        if (pkg == context.packageName || pkg in homePkgs || pkg in state.blockedPackages) {
            activeAllowedUserAppPkg = null
            activeAllowedUserAppTimeMs = 0L
        } else {
            // User opened an allowed app (such as InstaPay or Telegram)
            activeAllowedUserAppPkg = pkg
            activeAllowedUserAppTimeMs = System.currentTimeMillis()
        }
    }

    /**
     * Determines whether a com.android.settings window should be blocked:
     * - Blocked when opened from the Home Screen, App Drawer, Quick Settings, or StrictLock.
     * - Allowed when triggered as a biometric/PIN/security sub-check inside an active allowed app like InstaPay.
     */
    @Synchronized
    fun shouldBlockSettingsWindow(
        className: String?,
        state: LockSessionState
    ): Boolean {
        if (!state.blockSettingsAndUninstall) return false
        val lowerClass = className?.lowercase().orEmpty()

        // Always block deactivating Device Admin
        if (lowerClass.contains("deviceadmin")) return true

        // Never block fingerprint / PIN / pattern / device credential authentication prompts
        if (AUTH_PROMPT_KEYWORDS.any { lowerClass.contains(it) }) return false

        // Ignore non-activity view containers
        if (lowerClass.startsWith("android.widget.") || lowerClass.startsWith("android.view.")) {
            return false
        }

        // If the user is currently inside an allowed app (like InstaPay) and hasn't returned to Home Screen,
        // allow sub-settings checks triggered by that app so InstaPay is never falsely blocked.
        val allowedPkg = activeAllowedUserAppPkg
        val elapsedSinceAllowedApp = System.currentTimeMillis() - activeAllowedUserAppTimeMs
        if (allowedPkg != null && elapsedSinceAllowedApp in 0..45_000L) {
            return false
        }

        return true
    }

    /**
     * Called when SHUTDOWN or REBOOT broadcast arrives to anchor the monotonic counter.
     */
    @Synchronized
    fun onDeviceShuttingDown(context: Context) {
        val state = refreshState(context)
        if (state.isLocked) {
            val prefs = getPrefs(context)
            prefs.edit().putLong(KEY_LAST_BOOT_REALTIME_MS, 0L).commit()
        }
    }

    private fun readAppTimersMap(prefs: SharedPreferences): Map<String, AppTimerState> {
        val rawJson = prefs.getString(KEY_APP_TIMERS_JSON, null)
        if (rawJson.isNullOrBlank()) return emptyMap()

        return runCatching {
            val root = JSONObject(rawJson)
            val result = mutableMapOf<String, AppTimerState>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                val obj = root.optJSONObject(pkg) ?: continue
                val remaining = obj.optLong("remainingMs", 0L)
                val total = obj.optLong("totalDurationMs", remaining)
                if (remaining > 0L) {
                    result[pkg] = AppTimerState(
                        packageName = pkg,
                        remainingMs = remaining,
                        totalDurationMs = total
                    )
                }
            }
            result
        }.getOrDefault(emptyMap())
    }

    private fun serializeAppTimersMap(map: Map<String, AppTimerState>): String {
        val root = JSONObject()
        for ((pkg, state) in map) {
            if (state.remainingMs > 0L) {
                val obj = JSONObject().apply {
                    put("remainingMs", state.remainingMs)
                    put("totalDurationMs", state.totalDurationMs)
                }
                root.put(pkg, obj)
            }
        }
        return root.toString()
    }
}
