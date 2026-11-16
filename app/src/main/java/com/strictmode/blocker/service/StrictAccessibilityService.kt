package com.strictmode.blocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.strictmode.blocker.data.LockStateManager

class StrictAccessibilityService : AccessibilityService() {

    companion object {
        // Exact system Power Menu dialog class names
        private val POWER_MENU_CLASS_KEYWORDS = listOf(
            "globalactions",
            "powermenu",
            "powerdialog",
            "shutdownthread"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSettingsBlockRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val state = LockStateManager.refreshState(this)
        if (state.isLocked) {
            LockEnforcementService.startOrUpdate(this)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val eventClass = event.className?.toString().orEmpty()
        if (eventClass.startsWith("android.app.Notification") ||
            eventClass.startsWith("android.widget.Toast")
        ) {
            return
        }

        val state = LockStateManager.refreshState(this)
        if (!state.isLocked) {
            cancelPendingSettingsBlock()
            return
        }

        val foregroundPkg = event.packageName?.toString() ?: return

        // Update shared foreground app tracker
        LockStateManager.onForegroundAppObserved(this, foregroundPkg, state)

        if (foregroundPkg == packageName) {
            cancelPendingSettingsBlock()
            return
        }

        // 1. Check if the Power Off / Restart menu dialog is open via class name
        if (state.blockPowerMenu && detectPowerMenuByClass(foregroundPkg, eventClass)) {
            cancelPendingSettingsBlock()
            blockAndDismiss(
                reason = "Anti-Cheat: Power Off & Restart are locked during an active timer"
            )
            return
        }

        // 2. Block if the opened window belongs to an app explicitly in state.blockedPackages
        if (foregroundPkg in state.blockedPackages) {
            cancelPendingSettingsBlock()
            val readableName = getReadableAppName(foregroundPkg)
            blockAndDismiss(
                reason = "Blocked App: $readableName is locked until its timer ends",
                blockedPackage = foregroundPkg
            )
            return
        }

        // 3. Check if Phone Settings was opened directly (and not as a sub-check inside InstaPay)
        if (foregroundPkg == "com.android.settings") {
            if (LockStateManager.shouldBlockSettingsWindow(eventClass, state)) {
                scheduleSettingsBlockVerification()
            }
            return
        }

        // 4. If user switched to an allowed user app (like InstaPay or Telegram),
        // cancel any pending Settings block and immediately dismiss any leftover shield!
        if (LockStateManager.activeAllowedUserAppPkg != null) {
            cancelPendingSettingsBlock()
            if (OverlayShieldManager.isShieldShowing()) {
                OverlayShieldManager.dismissShield(this)
            }
        }
    }

    private fun scheduleSettingsBlockVerification() {
        cancelPendingSettingsBlock()
        val runnable = Runnable {
            val state = LockStateManager.refreshState(this)
            if (state.isLocked &&
                state.blockSettingsAndUninstall &&
                LockStateManager.activeAllowedUserAppPkg == null
            ) {
                blockAndDismiss(
                    reason = "Anti-Cheat: Phone Settings are locked during an active timer"
                )
            }
        }
        pendingSettingsBlockRunnable = runnable
        handler.postDelayed(runnable, 350L)
    }

    private fun cancelPendingSettingsBlock() {
        pendingSettingsBlockRunnable?.let { handler.removeCallbacks(it) }
        pendingSettingsBlockRunnable = null
    }

    private fun detectPowerMenuByClass(foregroundPkg: String, eventClass: String): Boolean {
        if (foregroundPkg != "com.android.systemui" && foregroundPkg != "android") {
            return false
        }
        val lowerClass = eventClass.lowercase()
        return POWER_MENU_CLASS_KEYWORDS.any { lowerClass.contains(it) }
    }

    private fun blockAndDismiss(reason: String, blockedPackage: String? = null) {
        LockStateManager.recordViolation(this, reason, blockedPackage)

        runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
        runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }

        OverlayShieldManager.showShield(
            context = this,
            reasonTitle = reason,
            blockedPackage = blockedPackage,
            useAccessibilityOverlay = false,
            onGoHomeClicked = {
                runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
                OverlayShieldManager.navigateToHomeLauncher(this)
            }
        )
    }

    private fun getReadableAppName(pkgName: String): String {
        return runCatching {
            val appInfo = packageManager.getApplicationInfo(pkgName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(pkgName)
    }

    override fun onInterrupt() {
        // Keep running
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cancelPendingSettingsBlock()
        OverlayShieldManager.dismissShield(this)
        return super.onUnbind(intent)
    }
}
