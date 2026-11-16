package com.strictmode.blocker.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.strictmode.blocker.R
import com.strictmode.blocker.data.LockStateManager
import com.strictmode.blocker.service.OverlayShieldManager

class StrictDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val state = LockStateManager.refreshState(context)
        if (state.isLocked) {
            LockStateManager.recordViolation(
                context,
                "Anti-Cheat: Attempted to disable Device Admin during an active timer"
            )
            OverlayShieldManager.navigateToHomeLauncher(context)
            OverlayShieldManager.showShield(
                context = context,
                reasonTitle = "Anti-Cheat: Device Admin cannot be disabled while the timer is running!"
            )
        }
        return context.getString(R.string.device_admin_disable_warning)
    }
}
