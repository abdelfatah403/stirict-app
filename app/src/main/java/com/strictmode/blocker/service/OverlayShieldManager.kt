package com.strictmode.blocker.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.strictmode.blocker.data.LockStateManager
import java.util.Locale

object OverlayShieldManager {

    private var overlayView: View? = null
    private var reasonTextView: TextView? = null
    private var timerTextView: TextView? = null
    private var attemptsTextView: TextView? = null
    private var activeBlockedPackage: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isShowing = false

    private val tickerRunnable = object : Runnable {
        override fun run() {
            val view = overlayView ?: return
            val state = LockStateManager.refreshState(view.context)
            val pkg = activeBlockedPackage
            val targetRemainingMs = if (pkg != null) {
                state.appTimers[pkg]?.remainingMs ?: 0L
            } else {
                state.remainingMs
            }

            if (!state.isLocked || targetRemainingMs <= 0L) {
                dismissShield(view.context)
                return
            }
            timerTextView?.text = formatDuration(targetRemainingMs)
            attemptsTextView?.text = "Blocked Cheat / Open Attempts: ${state.tamperAttempts}"
            mainHandler.postDelayed(this, 1000L)
        }
    }

    /**
     * Shows an instant full-screen blocking shield using TYPE_ACCESSIBILITY_OVERLAY
     * (when called from StrictAccessibilityService) or TYPE_APPLICATION_OVERLAY.
     */
    fun showShield(
        context: Context,
        reasonTitle: String,
        blockedPackage: String? = null,
        useAccessibilityOverlay: Boolean = false,
        onGoHomeClicked: (() -> Unit)? = null
    ) {
        mainHandler.post {
            val state = LockStateManager.refreshState(context)
            val targetRemainingMs = if (blockedPackage != null) {
                state.appTimers[blockedPackage]?.remainingMs ?: state.remainingMs
            } else {
                state.remainingMs
            }
            if (!state.isLocked || targetRemainingMs <= 0L) return@post

            activeBlockedPackage = blockedPackage

            if (isShowing && overlayView != null) {
                reasonTextView?.text = reasonTitle
                timerTextView?.text = formatDuration(targetRemainingMs)
                attemptsTextView?.text = "Blocked Cheat / Open Attempts: ${state.tamperAttempts}"
                return@post
            }

            if (!useAccessibilityOverlay && !Settings.canDrawOverlays(context)) {
                return@post
            }

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val windowType = if (useAccessibilityOverlay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            val rootLayout = buildShieldView(context, reasonTitle, targetRemainingMs, state.tamperAttempts) {
                onGoHomeClicked?.invoke() ?: navigateToHomeLauncher(context)
                dismissShield(context)
            }

            runCatching {
                wm.addView(rootLayout, params)
                overlayView = rootLayout
                isShowing = true
                mainHandler.removeCallbacks(tickerRunnable)
                mainHandler.postDelayed(tickerRunnable, 1000L)
            }
        }
    }

    fun dismissShield(context: Context) {
        mainHandler.post {
            mainHandler.removeCallbacks(tickerRunnable)
            val view = overlayView ?: return@post
            val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            runCatching {
                wm.removeViewImmediate(view)
            }
            overlayView = null
            reasonTextView = null
            timerTextView = null
            attemptsTextView = null
            activeBlockedPackage = null
            isShowing = false
        }
    }

    private fun buildShieldView(
        context: Context,
        reasonTitle: String,
        remainingMs: Long,
        tamperAttempts: Int,
        onGoHome: () -> Unit
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F50D1117"))
            setPadding(dp(28), dp(40), dp(28), dp(40))
        }

        val badgeView = TextView(context).apply {
            text = "STRICT LOCK ACTIVE"
            setTextColor(Color.parseColor("#FCA5A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#33EF4444"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#EF4444"))
            }
        }

        val titleView = TextView(context).apply {
            text = "Access Blocked"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(8))
        }

        val reasonView = TextView(context).apply {
            text = reasonTitle
            setTextColor(Color.parseColor("#F87171"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(28))
        }
        reasonTextView = reasonView

        val timerCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(24), dp(32), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#161B22"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#30363D"))
            }
        }

        val timerLabel = TextView(context).apply {
            text = "TIME REMAINING"
            setTextColor(Color.parseColor("#8B949E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val countdownView = TextView(context).apply {
            text = formatDuration(remainingMs)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        timerTextView = countdownView

        timerCard.addView(timerLabel)
        timerCard.addView(countdownView)

        val subMessageView = TextView(context).apply {
            text = "Anti-Cheat is enabled: Uninstalling, Settings, Force Stop, and Power Off / Restart are locked until the timer finishes."
            setTextColor(Color.parseColor("#8B949E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(24), dp(8), dp(12))
        }

        val attemptsView = TextView(context).apply {
            text = "Blocked Cheat / Open Attempts: $tamperAttempts"
            setTextColor(Color.parseColor("#FBBF24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(28))
        }
        attemptsTextView = attemptsView

        val homeButton = Button(context).apply {
            text = "Return to Home Screen"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(28), dp(14), dp(28), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EF4444"))
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { onGoHome() }
        }

        container.addView(badgeView)
        container.addView(titleView)
        container.addView(reasonView)
        container.addView(timerCard)
        container.addView(subMessageView)
        container.addView(attemptsView)
        container.addView(homeButton)

        return container
    }

    fun navigateToHomeLauncher(context: Context) {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        runCatching { context.startActivity(homeIntent) }
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
