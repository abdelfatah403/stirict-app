package com.strictmode.blocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.strictmode.blocker.R
import com.strictmode.blocker.data.LockStateManager
import com.strictmode.blocker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LockEnforcementService : Service() {

    companion object {
        private const val CHANNEL_ID = "strict_lock_timer_channel"
        private const val NOTIFICATION_ID = 1001

        fun startOrUpdate(context: Context) {
            val intent = Intent(context, LockEnforcementService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = LockStateManager.refreshState(this)
        val notification = buildNotification(state.remainingMs, state.blockedPackages.size)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!state.isLocked) {
            OverlayShieldManager.dismissShield(this)
            stopSelf()
            return START_NOT_STICKY
        }

        startCountdownLoop()
        return START_STICKY
    }

    private fun startCountdownLoop() {
        if (tickerJob?.isActive == true) return
        tickerJob = serviceScope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            while (isActive) {
                val state = LockStateManager.refreshState(this@LockEnforcementService)
                if (!state.isLocked) {
                    OverlayShieldManager.dismissShield(this@LockEnforcementService)
                    stopSelf()
                    break
                }
                val notification = buildNotification(state.remainingMs, state.blockedPackages.size)
                runCatching { nm.notify(NOTIFICATION_ID, notification) }
                delay(1000L)
            }
        }
    }

    private fun buildNotification(remainingMs: Long, blockedCount: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formattedTime = OverlayShieldManager.formatDuration(remainingMs)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_lock)
            .setContentTitle("StrictLock Active • $formattedTime left")
            .setContentText("Blocking $blockedCount apps • Anti-Cheat & Uninstall Protection ON")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active StrictLock Timer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the remaining countdown time while StrictLock is active"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
