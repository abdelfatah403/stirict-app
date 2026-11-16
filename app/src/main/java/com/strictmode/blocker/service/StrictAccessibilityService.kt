package com.strictmode.blocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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
        // Ignore notifications, toasts, and non-activity popups
        if (eventClass.startsWith("android.app.Notification") ||
            eventClass.startsWith("android.widget.Toast")
        ) {
            return
        }

        val state = LockStateManager.refreshState(this)
        if (!state.isLocked) {
            return
        }

        val foregroundPkg = event.packageName?.toString() ?: return
        if (foregroundPkg == packageName) {
            return
        }

        // 1. Check if the Power Off / Restart menu dialog is open via class name
        if (state.blockPowerMenu && detectPowerMenuByClass(foregroundPkg, eventClass)) {
            blockAndDismiss(
                reason = "Anti-Cheat: Power Off & Restart are locked during an active timer"
            )
            return
        }

        // 2. Block ONLY if the opened window belongs to an app explicitly in state.blockedPackages
        if (foregroundPkg in state.blockedPackages) {
            val readableName = getReadableAppName(foregroundPkg)
            blockAndDismiss(
                reason = "Blocked App: $readableName is locked until its timer ends",
                blockedPackage = foregroundPkg
            )
            return
        }

        // 3. Only block the explicit Device Admin deactivation screen if user tries to disable Device Admin
        val classLower = eventClass.lowercase()
        if (state.blockSettingsAndUninstall &&
            foregroundPkg == "com.android.settings" &&
            classLower.contains("deviceadmin")
        ) {
            blockAndDismiss(
                reason = "Anti-Cheat: Deactivating Device Admin is locked during an active timer"
            )
            return
        }

        // 4. If user switched to an allowed app (like InstaPay, Telegram, or Home Launcher)
        // and the shield was still open from an earlier attempt (> 1200ms ago), auto-dismiss it!
        if (foregroundPkg != "com.android.systemui" &&
            foregroundPkg != "android" &&
            OverlayShieldManager.isShieldShowing() &&
            System.currentTimeMillis() - OverlayShieldManager.lastShownTimestampMs > 1200L
        ) {
            OverlayShieldManager.dismissShield(this)
        }
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
        OverlayShieldManager.dismissShield(this)
        return super.onUnbind(intent)
    }
}
