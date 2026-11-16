package com.strictmode.blocker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.strictmode.blocker.data.LockStateManager
import com.strictmode.blocker.service.LockEnforcementService
import com.strictmode.blocker.service.OverlayShieldManager

class BootAndTamperReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            Intent.ACTION_SHUTDOWN,
            Intent.ACTION_REBOOT -> {
                // Anchor the monotonic timer before device shuts down
                LockStateManager.onDeviceShuttingDown(context)
            }

            Intent.ACTION_TIME_SET,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                // System clock or timezone changed — our timer uses SystemClock.elapsedRealtime()
                // so it is completely unaffected, but we log the tamper attempt if locked.
                val state = LockStateManager.refreshState(context)
                if (state.isLocked) {
                    LockStateManager.recordViolation(
                        context,
                        "Anti-Cheat: System clock change detected (Timer uses hardware monotonic clock and was not affected)"
                    )
                    LockEnforcementService.startOrUpdate(context)
                }
            }

            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val state = LockStateManager.refreshState(context)
                if (state.isLocked) {
                    LockStateManager.recordViolation(
                        context,
                        "Device restarted during active lock — Timer automatically resumed"
                    )
                    LockEnforcementService.startOrUpdate(context)
                    OverlayShieldManager.showShield(
                        context = context,
                        reasonTitle = "Device Restarted: Your StrictLock timer is still active!"
                    )
                }
            }
        }
    }
}
