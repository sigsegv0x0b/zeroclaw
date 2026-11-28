package ai.zeroclaw.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.zeroclaw.app.data.CertificateRenewalScheduler
import ai.zeroclaw.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restarts the device link (and with it the chat socket) after a reboot or an
 * app update, but only when the operator has paired and kept the device link
 * enabled.
 *
 * Deliberately not `LOCKED_BOOT_COMPLETED`: the pairing settings live in
 * credential-protected storage, which is unavailable before the first unlock.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = SettingsStore(context).current()
                if (settings.gatewayUrl.isNotBlank() &&
                    settings.token.isNotBlank() &&
                    settings.nodeEnabled
                ) {
                    NodeService.start(context)
                    CertificateRenewalScheduler.schedule(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
