package com.strictmode.blocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.strictmode.blocker.R
import com.strictmode.blocker.data.LockSessionState
import com.strictmode.blocker.data.LockStateManager
import com.strictmode.blocker.ui.MainActivity
import com.strictmode.blocker.util.PermissionHelper
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
    private var lastProcessedEventTimeMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        lastProcessedEventTimeMs = System.currentTimeMillis()
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

        startCountdownAndEnforcementLoop()
        return START_STICKY
    }

    private fun startCountdownAndEnforcementLoop() {
        if (tickerJob?.isActive == true) return
        lastProcessedEventTimeMs = System.currentTimeMillis()
        tickerJob = serviceScope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            var tickCount = 0
            while (isActive) {
                val state = LockStateManager.refreshState(this@LockEnforcementService)
                if (!state.isLocked) {
                    OverlayShieldManager.dismissShield(this@LockEnforcementService)
                    stopSelf()
                    break
                }

                // Poll foreground app every 300ms via UsageStatsManager (100% InstaPay / Banking safe!)
                checkForegroundAppViaUsageStats(state)

                // Update notification once every ~900ms (every 3rd tick)
                if (tickCount % 3 == 0) {
                    val notification = buildNotification(state.remainingMs, state.blockedPackages.size)
                    runCatching { nm.notify(NOTIFICATION_ID, notification) }
                }
                tickCount++
                delay(300L)
            }
        }
    }

    private fun checkForegroundAppViaUsageStats(state: LockSessionState) {
        if (!PermissionHelper.isUsageStatsPermissionGranted(this)) return

        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        val queryStart = maxOf(
            now - 2500L,
            lastProcessedEventTimeMs,
            OverlayShieldManager.lastDismissedTimestampMs
        )
        if (queryStart >= now) return

        val usageEvents = runCatching { usm.queryEvents(queryStart, now) }.getOrNull() ?: return

        val event = UsageEvents.Event()
        var latestResumedPkg: String? = null
        var latestResumedClass: String? = null
        var latestEventTimestamp = lastProcessedEventTimeMs

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.timeStamp <= queryStart) continue

            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latestResumedPkg = event.packageName
                latestResumedClass = event.className
                if (event.timeStamp > latestEventTimestamp) {
                    latestEventTimestamp = event.timeStamp
                }
            }
        }

        if (latestEventTimestamp > lastProcessedEventTimeMs) {
            lastProcessedEventTimeMs = latestEventTimestamp
        }

        val currentPkg = latestResumedPkg ?: return
        if (currentPkg == packageName) return

        // 1. Block ONLY if the foreground package is explicitly in the user's selected blockedPackages list
        if (currentPkg in state.blockedPackages) {
            val appName = getReadableAppName(currentPkg)
            val reason = "Blocked App: $appName is locked until its timer ends"
            LockStateManager.recordViolation(this, reason, currentPkg)
            OverlayShieldManager.navigateToHomeLauncher(this)
            OverlayShieldManager.showShield(
                context = this,
                reasonTitle = reason,
                blockedPackage = currentPkg,
                useAccessibilityOverlay = false
            )
            return
        }

        // 2. Only block the explicit Device Admin deactivation screen if user tries to disable Device Admin
        val classLower = latestResumedClass?.lowercase().orEmpty()
        if (state.blockSettingsAndUninstall &&
            currentPkg == "com.android.settings" &&
            classLower.contains("deviceadmin")
        ) {
            val reason = "Anti-Cheat: Deactivating Device Admin is locked during an active timer"
            LockStateManager.recordViolation(this, reason)
            OverlayShieldManager.navigateToHomeLauncher(this)
            OverlayShieldManager.showShield(
                context = this,
                reasonTitle = reason,
                useAccessibilityOverlay = false
            )
            return
        }

        // 3. If the user switched to any allowed app (like InstaPay, Telegram, or Home Launcher)
        // and the shield was still open from an earlier attempt (> 1200ms ago), auto-dismiss it!
        if (OverlayShieldManager.isShieldShowing() &&
            now - OverlayShieldManager.lastShownTimestampMs > 1200L
        ) {
            OverlayShieldManager.dismissShield(this)
        }
    }

    private fun getReadableAppName(pkgName: String): String {
        return runCatching {
            val appInfo = packageManager.getApplicationInfo(pkgName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(pkgName)
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
