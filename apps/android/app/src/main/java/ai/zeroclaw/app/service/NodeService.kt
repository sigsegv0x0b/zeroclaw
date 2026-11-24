package ai.zeroclaw.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ai.zeroclaw.app.MainActivity
import ai.zeroclaw.app.R
import ai.zeroclaw.app.data.DeviceCapabilities
import ai.zeroclaw.app.data.MtlsTransport
import ai.zeroclaw.app.data.NodeClient
import ai.zeroclaw.app.data.NodeRuntime
import ai.zeroclaw.app.data.NodeStatus
import ai.zeroclaw.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the device-node WebSocket alive.
 *
 * The link is what lets the agent set alarms and timers on this phone even
 * when the chat app is not on screen. It is a `connectedDevice` foreground
 * service: the notification is the user-visible proof that the phone is
 * reachable.
 */
class NodeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var client: NodeClient? = null

    private val certificateRenewed = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CERTIFICATE_RENEWED) connect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ContextCompat.registerReceiver(
            this,
            certificateRenewed,
            IntentFilter(ACTION_CERTIFICATE_RENEWED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("Linking this phone…"))
        connect()
        return START_STICKY
    }

    private fun connect() {
        scope.launch {
            client?.stop()
            client = null
            val settings = SettingsStore(applicationContext).current()
            if (settings.gatewayUrl.isBlank() || settings.token.isBlank() || settings.deviceId.isBlank()) {
                NodeRuntime.set(NodeStatus.Stopped)
                stopSelf()
                return@launch
            }
            val transport = when (val result = MtlsTransport.build(settings)) {
                is MtlsTransport.Result.Ready -> result.built.client
                is MtlsTransport.Result.Unavailable -> MtlsTransport.plainClient()
            }
            val nodeClient = NodeClient(
                scope = scope,
                http = transport,
                onStatus = { status ->
                    NodeRuntime.set(status)
                    notify(buildNotification(describe(status)))
                },
                execute = { capability, args ->
                    DeviceCapabilities.execute(applicationContext, capability, args)
                },
            )
            client = nodeClient
            nodeClient.start(
                NodeClient.Config(
                    baseUrl = settings.gatewayUrl,
                    token = settings.token,
                    nodeId = settings.deviceId,
                ),
            )
        }
    }

    private fun describe(status: NodeStatus): String = when (status) {
        is NodeStatus.Connected ->
            "Linked as ${status.nodeId} · ${status.capabilityCount} capabilities"
        is NodeStatus.Connecting -> "Linking this phone…"
        is NodeStatus.Disabled -> "Phone actions unavailable: node endpoint disabled"
        is NodeStatus.Error -> "Link lost: ${status.message}"
        NodeStatus.Stopped -> "Device link stopped"
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ZeroClaw device link")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Device link",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps this phone reachable for alarms, timers and other device actions."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(certificateRenewed) }
        client?.stop()
        client = null
        NodeRuntime.set(NodeStatus.Stopped)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_CERTIFICATE_RENEWED = "ai.zeroclaw.app.mtls.CERTIFICATE_RENEWED"
        private const val CHANNEL_ID = "zeroclaw_node"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_START = "ai.zeroclaw.app.node.START"
        private const val ACTION_STOP = "ai.zeroclaw.app.node.STOP"

        fun start(context: Context) {
            val intent = Intent(context, NodeService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, NodeService::class.java)) }
            NodeRuntime.set(NodeStatus.Stopped)
        }

        /**
         * Ask a running link to rebuild its TLS client after the certificate
         * was rotated. The explicit package keeps the broadcast inside the
         * app; if the service is not running the next start reads the new
         * material from settings.
         */
        fun notifyCertificateRenewed(context: Context) {
            context.sendBroadcast(
                Intent(ACTION_CERTIFICATE_RENEWED).setPackage(context.packageName),
            )
        }
    }
}
