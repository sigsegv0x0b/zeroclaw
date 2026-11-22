package ai.zeroclaw.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * Runtime permissions the node capabilities need. The device link (a
 * foreground service) cannot request permissions itself: the operator grants
 * them from Settings while the app is visible, and the capability returns a
 * pointer to that screen until then.
 */
object DevicePermissions {

    fun location(): Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /** Photo-library read access across the minSdk 26 → current range. */
    fun media(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasLocation(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasMedia(context: Context): Boolean = media().all { granted(context, it) }

    fun contacts(): Array<String> = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
    )

    fun calendar(): Array<String> = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    fun activity(): Array<String> = arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)

    fun sms(): Array<String> = arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
    )

    fun callLog(): Array<String> = arrayOf(Manifest.permission.READ_CALL_LOG)

    fun camera(): Array<String> = arrayOf(Manifest.permission.CAMERA)

    fun microphone(): Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    fun has(context: Context, permissions: Array<String>): Boolean =
        permissions.all { granted(context, it) }

    /**
     * Notification access is a special access grant, not a runtime permission:
     * the listener service only receives notifications once the operator
     * enables it in system settings.
     */
    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    fun notificationAccessSettingsIntent(): android.content.Intent =
        android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Accessibility is a special access grant; the service must be enabled by hand. */
    fun hasAccessibility(context: Context): Boolean {
        val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            ?: return false
        return manager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        ).any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    fun accessibilitySettingsIntent(): android.content.Intent =
        android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
