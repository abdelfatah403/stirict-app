package com.strictmode.blocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.strictmode.blocker.data.LockStateManager

class StrictAccessibilityService : AccessibilityService() {

    companion object {
        // Only the actual Android Settings app and Package Installers
        private val SETTINGS_AND_INSTALLER_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller"
        )

        // Exact system Power Menu dialog class names
        private val POWER_MENU_CLASS_KEYWORDS = listOf(
            "globalactions",
            "powermenu",
            "powerdialog",
            "shutdownthread"
        )

        private val POWER_OFF_KEYWORDS = listOf(
            "power off",
            "shut down",
            "إيقاف التشغيل",
            "ايقاف التشغيل"
        )

        private val RESTART_KEYWORDS = listOf(
            "restart",
            "reboot",
            "إعادة التشغيل",
            "اعادة التشغيل"
        )

        private val UNINSTALL_TEXT_KEYWORDS = listOf(
            "uninstall",
            "force stop",
            "deactivate",
            "clear storage",
            "clear data",
            "إلغاء التثبيت",
            "الغاء التثبيت",
            "فرض الإيقاف",
            "إيقاف إجباري"
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
        if (event == null) return

        // Ignore background notifications and toast popups completely!
        // A blocked app (like TikTok) sending a notification while you use Telegram
        // must NEVER trigger a block.
        val eventClass = event.className?.toString().orEmpty()
        if (eventClass.startsWith("android.app.Notification") ||
            eventClass.startsWith("android.widget.Toast")
        ) {
            return
        }

        val state = LockStateManager.refreshState(this)
        if (!state.isLocked) {
            return
        }

        // Determine the TRUE foreground window package that the user is currently interacting with
        val activeRoot = rootInActiveWindow
        val foregroundPkg = activeRoot?.packageName?.toString()
            ?: if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                event.packageName?.toString()
            } else {
                null
            } ?: return

        // Never block StrictLock itself
        if (foregroundPkg == packageName) {
            return
        }

        // 1. Check if the Power Off / Restart menu dialog is open
        if (state.blockPowerMenu && detectPowerMenu(event, activeRoot, foregroundPkg)) {
            blockAndDismiss(
                reason = "Anti-Cheat: Power Off & Restart are locked during an active timer"
            )
            return
        }

        // 2. Check ONLY if the true foreground app is in the user's blockedPackages list
        if (foregroundPkg in state.blockedPackages) {
            val readableName = getReadableAppName(foregroundPkg)
            blockAndDismiss(
                reason = "Blocked App: $readableName is locked until its timer ends",
                blockedPackage = foregroundPkg
            )
            return
        }

        // 3. Check if the user opened Android Settings or tried to Uninstall/Force-Stop StrictLock
        if (state.blockSettingsAndUninstall) {
            // Only block Settings/Installer when it is the actual foreground window
            if (foregroundPkg in SETTINGS_AND_INSTALLER_PACKAGES &&
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            ) {
                blockAndDismiss(
                    reason = "Anti-Cheat: Settings & App Uninstall are locked during an active timer"
                )
                return
            }

            // Catch App Info / Uninstall dialogs from system installers or OEM app managers targeting StrictLock
            val isSystemOrInstaller = foregroundPkg in SETTINGS_AND_INSTALLER_PACKAGES ||
                foregroundPkg.contains("packageinstaller") ||
                foregroundPkg.contains("permissioncontroller") ||
                foregroundPkg.contains("securitycenter") ||
                foregroundPkg.contains("safecenter")
            if (isSystemOrInstaller && activeRoot != null && detectStrictLockUninstallDialog(activeRoot)) {
                blockAndDismiss(
                    reason = "Anti-Cheat: Uninstalling or stopping StrictLock is blocked!"
                )
                return
            }
        }
    }

    private fun detectPowerMenu(
        event: AccessibilityEvent,
        activeRoot: AccessibilityNodeInfo?,
        foregroundPkg: String
    ): Boolean {
        val eventPkg = event.packageName?.toString().orEmpty()
        val eventClassLower = event.className?.toString()?.lowercase().orEmpty()

        // 1. Match explicit Android SystemUI Power Menu dialog class names
        if ((eventPkg == "com.android.systemui" || eventPkg == "android") &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            POWER_MENU_CLASS_KEYWORDS.any { eventClassLower.contains(it) }
        ) {
            return true
        }

        // 2. Check if the active foreground window belongs to SystemUI/Android and displays
        // BOTH a "Power off" option AND a "Restart/Reboot" option (to never confuse it with
        // normal notifications or the status bar).
        if ((foregroundPkg == "com.android.systemui" || foregroundPkg == "android") && activeRoot != null) {
            val texts = mutableListOf<String>()
            collectNodeTexts(activeRoot, texts, maxDepth = 5)
            val combined = texts.joinToString(" | ").lowercase()

            val hasPowerOff = POWER_OFF_KEYWORDS.any { combined.contains(it) }
            val hasRestart = RESTART_KEYWORDS.any { combined.contains(it) }
            if (hasPowerOff && hasRestart) {
                return true
            }
        }

        return false
    }

    private fun detectStrictLockUninstallDialog(activeRoot: AccessibilityNodeInfo): Boolean {
        val texts = mutableListOf<String>()
        collectNodeTexts(activeRoot, texts, maxDepth = 6)
        val combined = texts.joinToString(" | ").lowercase()

        // Must explicitly mention our app name ("strictlock") AND an uninstall / force-stop action
        return combined.contains("strictlock") &&
            UNINSTALL_TEXT_KEYWORDS.any { combined.contains(it) }
    }

    private fun collectNodeTexts(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        depth: Int = 0,
        maxDepth: Int = 6
    ) {
        if (node == null || depth > maxDepth || out.size > 40) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectNodeTexts(child, out, depth + 1, maxDepth)
        }
    }

    private fun blockAndDismiss(reason: String, blockedPackage: String? = null) {
        LockStateManager.recordViolation(this, reason, blockedPackage)

        // Dismiss system dialogs, back out, and return to Home screen immediately
        runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
        runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }

        // Show full-screen Accessibility Overlay Shield above all windows
        OverlayShieldManager.showShield(
            context = this,
            reasonTitle = reason,
            blockedPackage = blockedPackage,
            useAccessibilityOverlay = true,
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
