package com.parchment.launcher

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.app.PendingIntent
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification

data class LauncherNotification(
    val key: String,
    val appName: String,
    val title: String,
    val message: String,
    val icon: Icon?,
    val postedAt: Long,
    val contentIntent: PendingIntent?
)

object NotificationCenter {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notifications = linkedMapOf<String, LauncherNotification>()
    private val observers =
        mutableSetOf<(List<LauncherNotification>) -> Unit>()

    @Volatile
    private var listener: ParchmentNotificationListener? = null

    fun attach(service: ParchmentNotificationListener) {
        listener = service
    }

    fun detach(service: ParchmentNotificationListener) {
        if (listener === service) {
            listener = null
        }
    }

    @Synchronized
    fun observe(observer: (List<LauncherNotification>) -> Unit) {
        observers.add(observer)
        val initialItems = notifications.values.toList()

        mainHandler.post {
            observer(initialItems)
        }
    }

    @Synchronized
    fun stopObserving(observer: (List<LauncherNotification>) -> Unit) {
        observers.remove(observer)
    }

    fun snapshot(): List<LauncherNotification> {
        return synchronized(this) {
            notifications.values.sortedByDescending { it.postedAt }
        }
    }

    fun update(item: LauncherNotification) {
        synchronized(this) {
            notifications[item.key] = item
        }
        notifyObservers()
    }

    fun remove(key: String) {
        synchronized(this) {
            notifications.remove(key)
        }
        notifyObservers()
    }

    fun dismiss(key: String) {
        listener?.cancelNotification(key)
        remove(key)
    }

    fun dismissAll() {
        listener?.cancelAllNotifications()

        synchronized(this) {
            notifications.clear()
        }

        notifyObservers()
    }

    private fun notifyObservers() {
        val currentItems = snapshot()
        val currentObservers = synchronized(this) {
            observers.toList()
        }

        mainHandler.post {
            currentObservers.forEach { observer ->
                observer(currentItems)
            }
        }
    }
}

class ParchmentNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationCenter.attach(this)

        activeNotifications
            ?.forEach { notification ->
                updateNotification(notification)
            }
    }

    override fun onListenerDisconnected() {
        NotificationCenter.detach(this)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(
        notification: StatusBarNotification
    ) {
        updateNotification(notification)
    }

    override fun onNotificationRemoved(
        notification: StatusBarNotification
    ) {
        NotificationCenter.remove(notification.key)
    }

    private fun updateNotification(
        statusNotification: StatusBarNotification
    ) {
        val notification = statusNotification.notification
        val extras = notification.extras

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(
                statusNotification.packageName,
                0
            )
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            statusNotification.packageName
        }

        val title = extras
            .getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            .orEmpty()

        val message = (
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
                )
            ?.toString()
            .orEmpty()

        NotificationCenter.update(
            LauncherNotification(
                key = statusNotification.key,
                appName = appName,
                title = title,
                message = message,
                icon = notification.smallIcon,
                postedAt = statusNotification.postTime,
                contentIntent = notification.contentIntent
            )
        )
    }
}