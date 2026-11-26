package ai.zeroclaw.app.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.zeroclaw.app.service.NodeService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared rules for rotating the client certificate at half its lifetime.
 *
 * The certificate only has to be current when a connection is opened, so this
 * is not a periodic check: one deferred work item sleeps until the midpoint,
 * and the foreground app also checks before it connects.
 */
object CertificateRenewal {

    /** Serializes renewal and enrollment, so two of them cannot replace each
     *  other's key or certificate halfway through. */
    private val mutex = Mutex()

    suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }

    /** The gateway-side identity is stable; only the client leaf is replaced. */
    internal fun hasMaterial(settings: StoredSettings): Boolean =
        settings.mtlsEnabled &&
            settings.mtlsClientCertPem.isNotBlank() &&
            settings.hasClientKey &&
            settings.mtlsNotAfter > settings.mtlsIssuedAt &&
            settings.mtlsIssuedAt > 0

    internal fun midpoint(settings: StoredSettings): Long =
        settings.mtlsIssuedAt + (settings.mtlsNotAfter - settings.mtlsIssuedAt) / 2

    internal fun isDue(settings: StoredSettings, now: Long = System.currentTimeMillis()): Boolean =
        hasMaterial(settings) && now >= midpoint(settings)

    /**
     * Renew when due (or always, when `force`), store the new identity in one
     * write, and only then delete the key it replaced. Returns the stored
     * settings after a renewal, or null when nothing was due.
     */
    suspend fun renewIfDue(store: SettingsStore, force: Boolean): StoredSettings? = serialized {
        val latest = store.current()
        if (!hasMaterial(latest)) return@serialized null
        if (!force && !isDue(latest)) return@serialized null
        // A run that died between generating a key and saving it left a key
        // nothing refers to.
        ClientKeys.deleteAllExcept(latest.mtlsClientKeyAlias)
        val renewed = MtlsEnrollment.renew(latest, latest.deviceName.ifBlank { "Android" })
        try {
            store.saveRenewedIdentity(renewed)
        } catch (error: Exception) {
            ClientKeys.delete(renewed.keyAlias)
            throw error
        }
        ClientKeys.deleteAllExcept(renewed.keyAlias)
        store.current()
    }
}

/**
 * One-shot WorkManager scheduling for certificate renewal.
 *
 * `initialDelay` is the only timer: WorkManager batches the wakeup with other
 * maintenance, respects Doze and App Standby, and survives reboots. There is
 * no hourly poll. A certificate that is renewed sooner than the deadline just
 * causes the worker to re-arm itself.
 */
object CertificateRenewalScheduler {

    private const val WORK_NAME = "mtls-certificate-renewal"

    /**
     * Re-arm from settings. KEEP avoids cancelling a worker that is already
     * rotating the certificate; if an older deadline is pending, the worker
     * re-checks the midpoint and appends the next one.
     */
    suspend fun schedule(context: Context) {
        val app = context.applicationContext
        val settings = runCatching { SettingsStore(app).current() }.getOrNull()
        if (settings == null || !CertificateRenewal.hasMaterial(settings)) {
            cancel(app)
            return
        }
        enqueue(app, ExistingWorkPolicy.KEEP, CertificateRenewal.midpoint(settings))
    }

    /**
     * A worker cannot REPLACE the unique chain it is currently running, so a
     * successful run appends its successor instead.
     */
    internal suspend fun scheduleNext(context: Context) {
        val app = context.applicationContext
        val settings = runCatching { SettingsStore(app).current() }.getOrNull() ?: return
        if (!CertificateRenewal.hasMaterial(settings)) {
            cancel(app)
            return
        }
        enqueue(app, ExistingWorkPolicy.APPEND_OR_REPLACE, CertificateRenewal.midpoint(settings))
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    private fun enqueue(app: Context, policy: ExistingWorkPolicy, midpoint: Long) {
        val delayMs = (midpoint - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<CertificateRenewalWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            // Renewal happens fifteen days before expiry; a six-hour backoff
            // still leaves ample margin and avoids a tight retry loop.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 6, TimeUnit.HOURS)
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, policy, request)
    }
}

/**
 * Renews the stored client certificate once, without user interaction.
 *
 * The bearer token and the current client certificate authenticate the call.
 * The gateway issues a new leaf for the same device and retires the old one
 * once the new one completes a handshake. The foreground view model performs
 * the same operation when it notices the midpoint passed, and the mutex above
 * keeps the two from racing.
 */
class CertificateRenewalWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = SettingsStore(applicationContext)
        val initial = runCatching { store.current() }.getOrNull() ?: return Result.success()
        if (!CertificateRenewal.hasMaterial(initial)) return Result.success()

        // An expired credential cannot authenticate its own renewal.
        if (System.currentTimeMillis() >= initial.mtlsNotAfter) return Result.failure()

        return try {
            val renewed = CertificateRenewal.renewIfDue(store, force = false)
            if (renewed != null) {
                // The running device link still holds the old key manager.
                NodeService.notifyCertificateRenewed(applicationContext)
            }
            CertificateRenewalScheduler.scheduleNext(applicationContext)
            Result.success()
        } catch (error: Exception) {
            if (error is GatewayException && (error.status == 401 || error.status == 403)) {
                // The token or certificate was revoked; only re-enrollment fixes it.
                Result.failure()
            } else {
                // WorkManager retries with the configured backoff. The gateway
                // keeps the current certificate valid until a replacement is
                // used, so a failed attempt never strands the device.
                Result.retry()
            }
        }
    }
}
