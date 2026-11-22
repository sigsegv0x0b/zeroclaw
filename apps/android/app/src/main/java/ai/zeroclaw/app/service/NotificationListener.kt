package ai.zeroclaw.app.service

import android.app.RemoteInput
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notification access bridge for the `notifications.list` / `.actions`
 * capability pair.
 *
 * Notification access is a special grant the operator turns on in system
 * settings. The service keeps the currently posted notifications in memory
 * only: nothing is persisted, and everything disappears when access is
 * revoked.
 */
class NotificationListener : NotificationListenerService() {

    private val lock = Any()
    private val posted = LinkedHashMap<String, StatusBarNotification>()

    override fun onListenerConnected() {
        instance = this
        refresh()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        synchronized(lock) { posted[sbn.key] = sbn }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(lock) { posted.remove(sbn.key) }
    }

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        synchronized(lock) {
            posted.clear()
            for (sbn in active) posted[sbn.key] = sbn
        }
    }

    fun snapshots(): List<StatusBarNotification> = synchronized(lock) {
        posted.values.sortedByDescending { it.postTime }
    }

    fun open(key: String): Boolean {
        val sbn = snapshot(key) ?: return false
        val intent = sbn.notification.contentIntent ?: return false
        return runCatching {
            intent.send()
            true
        }.getOrDefault(false)
    }

    fun dismiss(key: String): Boolean = runCatching {
        cancelNotification(key)
        synchronized(lock) { posted.remove(key) }
        true
    }.getOrDefault(false)

    fun reply(key: String, text: String): Boolean {
        val sbn = snapshot(key) ?: return false
        val actions = sbn.notification.actions ?: return false
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            if (remoteInputs.isEmpty()) continue
            val intent = Intent()
            val bundle = android.os.Bundle()
            for (input in remoteInputs) {
                bundle.putCharSequence(input.resultKey, text)
                RemoteInput.addResultsToIntent(arrayOf(input), intent, bundle)
            }
            return runCatching {
                action.actionIntent.send(this@NotificationListener, 0, intent)
                true
            }.getOrDefault(false)
        }
        return false
    }

    private fun snapshot(key: String): StatusBarNotification? =
        synchronized(lock) { posted[key] }

    companion object {
        @Volatile
        private var instance: NotificationListener? = null

        fun isConnected(): Boolean = instance != null

        fun snapshots(): List<StatusBarNotification> = instance?.snapshots().orEmpty()

        fun open(key: String): Boolean = instance?.open(key) == true

        fun dismiss(key: String): Boolean = instance?.dismiss(key) == true

        fun reply(key: String, text: String): Boolean = instance?.reply(key, text) == true
    }
}
