package com.strictmode.blocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.strictmode.blocker.data.LockStateManager

class StrictAccessibilityService : AccessibilityService() {

    companion object {
        // Settings, Package Installers, and OEM App Managers that could be used to uninstall,
        // force-stop, revoke permissions, or manipulate system date/time during a lock session.
        private val PROTECTED_SYSTEM_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.miui.securitycenter",
            "com.coloros.safecenter",
            "com.oplus.safecenter",
            "com.iqoo.secure",
            "com.vivo.permissionmanager",
            "com.huawei.systemmanager"
        )

        // Power menu class & text signatures across stock Android, Samsung OneUI, Xiaomi MIUI/HyperOS, etc.
        // Includes both English and Arabic labels for Power Off / Restart.
        private val POWER_MENU_CLASS_KEYWORDS = listOf(
            "globalactions",
            "shutdown",
            "powermenu",
            "powerdialog",
            "reboot"
        )

        private val POWER_MENU_TEXT_KEYWORDS = listOf(
            "power off",
            "restart",
            "reboot",
            "shut down",
            "turn off",
            "إيقاف التشغيل",
            "إعادة التشغيل",
            "اعادة التشغيل",
            "ايقاف التشغيل"
        )

        private val UNINSTALL_TEXT_KEYWORDS = listOf(
            "uninstall",
            "force stop",
            "deactivate",
            "delete app",
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
        val state = LockStateManager.refreshState(this)
        if (!state.isLocked) {
            return
        }

        // 1. Check all interactive windows first for the System Power Off / Restart Menu
        if (state.blockPowerMenu && detectPowerMenu(event)) {
            blockAndDismiss(
                reason = "Anti-Cheat: Power Off & Restart are locked during an active timer"
            )
            return
        }

        val pkgName = event?.packageName?.toString() ?: return

        // Never block our own app or the LockShieldActivity
        if (pkgName == packageName) {
            return
        }

        // 2. Check if the user opened a blocked app from their selection list
        if (pkgName in state.blockedPackages) {
            val readableName = getReadableAppName(pkgName)
            blockAndDismiss(
                reason = "Blocked App: $readableName is locked until its timer ends",
                blockedPackage = pkgName
            )
            return
        }

        // 3. Check if the user opened Settings, Package Installer, or an Uninstall prompt
        if (state.blockSettingsAndUninstall) {
            if (pkgName in PROTECTED_SYSTEM_PACKAGES) {
                blockAndDismiss(
                    reason = "Anti-Cheat: Settings & App Uninstall are locked during an active timer"
                )
                return
            }

            // Also catch home-screen launcher popups trying to uninstall or force-stop StrictLock
            if (detectUninstallAttempt(event)) {
                blockAndDismiss(
                    reason = "Anti-Cheat: Uninstalling or stopping StrictLock is blocked!"
                )
                return
            }
        }
    }

    private fun detectPowerMenu(event: AccessibilityEvent?): Boolean {
        val eventPkg = event?.packageName?.toString().orEmpty()
        val eventClass = event?.className?.toString()?.lowercase().orEmpty()

        // Check class name for GlobalActionsDialog / PowerMenu
        if (POWER_MENU_CLASS_KEYWORDS.any { eventClass.contains(it) }) {
            return true
        }

        // If SystemUI triggered a window change, inspect the node tree and interactive system windows
        if (eventPkg == "com.android.systemui" || eventPkg == "android") {
            val rootNode = rootInActiveWindow
            if (rootNode != null && containsPowerMenuNodes(rootNode)) {
                return true
            }
        }

        // Also inspect all interactive overlays (e.g. TYPE_SYSTEM_DIALOG / GlobalActions)
        val currentWindows = runCatching { windows }.getOrNull() ?: emptyList()
        for (window in currentWindows) {
            val root = window.root ?: continue
            val winPkg = root.packageName?.toString().orEmpty()
            if (winPkg == "com.android.systemui" || winPkg == "android") {
                if (containsPowerMenuNodes(root)) {
                    return true
                }
            }
        }

        return false
    }

    private fun containsPowerMenuNodes(root: AccessibilityNodeInfo): Boolean {
        val texts = mutableListOf<String>()
        collectNodeTexts(root, texts, maxDepth = 6)
        val combined = texts.joinToString(" | ").lowercase()

        // Must match at least one power/restart action keyword
        return POWER_MENU_TEXT_KEYWORDS.any { keyword ->
            combined.contains(keyword)
        }
    }

    private fun detectUninstallAttempt(event: AccessibilityEvent?): Boolean {
        val eventPkg = event?.packageName?.toString().orEmpty()
        val isInstallerOrPermission = eventPkg.contains("packageinstaller") ||
            eventPkg.contains("permissioncontroller")

        val root = rootInActiveWindow ?: return false
        val texts = mutableListOf<String>()
        collectNodeTexts(root, texts, maxDepth = 6)
        val combined = texts.joinToString(" | ").lowercase()

        if (isInstallerOrPermission && UNINSTALL_TEXT_KEYWORDS.any { combined.contains(it) }) {
            return true
        }

        // If a dialog mentions our app name ("strictlock") and an uninstall/stop keyword
        if (combined.contains("strictlock") && UNINSTALL_TEXT_KEYWORDS.any { combined.contains(it) }) {
            return true
        }

        return false
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
