package com.parchment.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.provider.Settings.Secure
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class NotificationDrawerActivity : Activity() {

    private var notificationList: LinearLayout? = null

    private val notificationObserver:
                (List<LauncherNotification>) -> Unit = { notifications ->
        renderNotifications(notifications)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        buildScreen()
        NotificationCenter.observe(notificationObserver)
        renderNotifications(NotificationCenter.snapshot())
    }

    override fun onResume() {
        super.onResume()
        renderNotifications(NotificationCenter.snapshot())
    }

    override fun onDestroy() {
        NotificationCenter.stopObserving(notificationObserver)
        super.onDestroy()
    }

    private fun buildScreen() {
        val root = vertical().apply {
            setBackgroundColor(BACKGROUND)
            setPadding(dp(22), dp(12), dp(22), dp(8))
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            text("‹  PARCHMENT", 15, TEXT).apply {
                typeface = Typeface.SERIF
                setPadding(0, dp(8), dp(12), dp(8))
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(0, dp(42), 1f)
        )

        header.addView(
            text("CLEAR ALL", 12, MUTED).apply {
                setPadding(dp(8), dp(8), 0, dp(8))
                setOnClickListener { clearNotifications() }
            }
        )

        root.addView(header)
        root.addView(rule())

        root.addView(
            text("Notifications", 28, TEXT).apply {
                typeface = Typeface.SERIF
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(18)
                bottomMargin = dp(12)
            }
        )

        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }

        notificationList = vertical()
        scrollView.addView(notificationList)

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        setContentView(root)
    }

    private fun renderNotifications(
        notifications: List<LauncherNotification>
    ) {
        val list = notificationList ?: return
        list.removeAllViews()

        if (!hasNotificationAccess()) {
            showAccessMessage(list)
            return
        }

        if (notifications.isEmpty()) {
            list.addView(
                text("No active notifications", 15, MUTED).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(40), 0, dp(40))
                }
            )
            return
        }

        notifications.forEach { notification ->
            list.addView(notificationRow(notification))
            list.addView(rule())
        }
    }

    private fun showAccessMessage(list: LinearLayout) {
        list.addView(
            text(
                "Parchment needs Notification access to show your notifications here.",
                15,
                MUTED
            ).apply {
                setPadding(dp(4), dp(20), dp(4), dp(16))
            }
        )

        val enableButton = text("ENABLE NOTIFICATION ACCESS", 13, TEXT).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = outlineBackground()
            setOnClickListener {
                openNotificationSettings()
            }
        }

        list.addView(enableButton)
    }

    private fun notificationRow(
        notification: LauncherNotification
    ): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(8), dp(12))
            background = cardBackground()
            isClickable = true
            isFocusable = true
        }

        val iconView = ImageView(this)
        val iconDrawable = try {
            notification.icon?.loadDrawable(this)
        } catch (_: Exception) {
            null
        }

        if (iconDrawable != null) {
            iconView.setImageDrawable(iconDrawable)
            iconView.colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply {
                    setSaturation(0f)
                }
            )
        } else {
            iconView.setImageDrawable(null)
            iconView.background = outlineBackground()
        }

        row.addView(
            iconView,
            LinearLayout.LayoutParams(dp(36), dp(36))
        )

        val details = vertical()
        row.addView(
            details,
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(12)
            }
        )

        details.addView(
            text(notification.appName, 12, MUTED).apply {
                typeface = Typeface.SERIF
            }
        )

        val title = notification.title
            .ifBlank { notification.message }
            .ifBlank { "Notification" }

        details.addView(
            text(title, 16, TEXT).apply {
                typeface = Typeface.SERIF
                maxLines = 2
            }
        )

        if (notification.message.isNotBlank() &&
            notification.message != notification.title
        ) {
            details.addView(
                text(notification.message, 14, MUTED).apply {
                    maxLines = 3
                }
            )
        }

        val time = DateFormat
            .getTimeInstance(DateFormat.SHORT, Locale.getDefault())
            .format(Date(notification.postedAt))

        details.addView(
            text(time, 11, MUTED).apply {
                setPadding(0, dp(4), 0, 0)
            }
        )

        val dismissButton = text("×", 22, MUTED).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                NotificationCenter.dismiss(notification.key)
            }
        }

        row.addView(
            dismissButton,
            LinearLayout.LayoutParams(dp(36), dp(44))
        )

        row.setOnClickListener {
            try {
                notification.contentIntent?.send()
                finish()
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "This notification can’t be opened",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        return row
    }

    private fun hasNotificationAccess(): Boolean {
        val enabledListeners = Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()

        val ourService = ComponentName(
            this,
            ParchmentNotificationListener::class.java
        ).flattenToString()

        return enabledListeners
            .split(':')
            .contains(ourService)
    }

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            )
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Notification access settings aren’t available",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun clearNotifications() {
        if (!hasNotificationAccess()) {
            openNotificationSettings()
            return
        }

        NotificationCenter.dismissAll()
    }

    private fun vertical(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
    }

    private fun text(
        value: String,
        size: Int,
        color: Int
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
        }
    }

    private fun rule(): View {
        return View(this).apply {
            setBackgroundColor(RULE)
            layoutParams = LinearLayout.LayoutParams(-1, dp(1))
        }
    }

    private fun cardBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(SURFACE)
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), RULE)
        }
    }

    private fun outlineBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), RULE)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    companion object {
        private val BACKGROUND = Color.rgb(18, 18, 18)
        private val SURFACE = Color.rgb(30, 30, 30)
        private val TEXT = Color.rgb(245, 245, 245)
        private val MUTED = Color.rgb(175, 175, 175)
        private val RULE = Color.rgb(68, 68, 68)
    }
}