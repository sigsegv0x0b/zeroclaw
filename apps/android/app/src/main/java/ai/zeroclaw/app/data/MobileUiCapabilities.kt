package ai.zeroclaw.app.data

import android.content.Context
import ai.zeroclaw.app.service.AgentAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Accessibility UI control, mirroring OpenClaw's `mobile.ui.observe` and
 * `mobile.ui.act`.
 *
 * Observe returns the active window's accessibility tree with stable refs;
 * act targets those refs or raw coordinates. The service must be enabled in
 * system accessibility settings.
 */
object MobileUiCapabilities {

    suspend fun observe(context: Context): CapabilityOutcome = withContext(Dispatchers.IO) {
        if (!DevicePermissions.hasAccessibility(context)) return@withContext DENIED
        val result = AgentAccessibilityService.observe()
            ?: return@withContext CapabilityOutcome(
                false,
                "",
                "The accessibility service is not connected or no window is active.",
            )
        CapabilityOutcome(
            true,
            "snapshotId=${result.id} package=${result.packageName} nodes=${result.lines.size}\n" +
                result.lines.joinToString("\n"),
        )
    }

    suspend fun act(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            if (!DevicePermissions.hasAccessibility(context)) return@withContext DENIED
            val snapshotId = args.string("snapshot_id") ?: args.string("snapshotId")
                ?: return@withContext CapabilityOutcome(
                    false,
                    "",
                    "snapshot_id is required (from mobile.ui.observe).",
                )
            val action = (args["action"] as? JsonObject) ?: args
            val type = action.string("type")?.trim()?.lowercase()
                ?: return@withContext CapabilityOutcome(false, "", "action.type is required")
            val parsed: AgentAccessibilityService.Action = when (type) {
                "click" -> AgentAccessibilityService.Action.Click(
                    action.string("ref") ?: return@withContext missing("ref"),
                )
                "set_text" -> AgentAccessibilityService.Action.SetText(
                    action.string("ref") ?: return@withContext missing("ref"),
                    action.string("text") ?: return@withContext missing("text"),
                )
                "scroll" -> AgentAccessibilityService.Action.Scroll(
                    action.string("ref") ?: return@withContext missing("ref"),
                    forward = (action.string("direction")?.lowercase() ?: "forward") != "backward",
                )
                "tap" -> AgentAccessibilityService.Action.Tap(
                    action.int("x") ?: return@withContext missing("x"),
                    action.int("y") ?: return@withContext missing("y"),
                )
                "swipe" -> AgentAccessibilityService.Action.Swipe(
                    action.int("x1") ?: return@withContext missing("x1"),
                    action.int("y1") ?: return@withContext missing("y1"),
                    action.int("x2") ?: return@withContext missing("x2"),
                    action.int("y2") ?: return@withContext missing("y2"),
                    durationMs = action.int("duration_ms") ?: 300,
                )
                "global" -> AgentAccessibilityService.Action.Global(
                    action.string("name") ?: return@withContext missing("name"),
                )
                else -> return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Unknown action type '$type' (click|set_text|scroll|tap|swipe|global).",
                )
            }
            val result = AgentAccessibilityService.act(snapshotId, parsed)
            CapabilityOutcome(result.ok, if (result.ok) result.message else "", result.message.takeIf { !result.ok })
        }

    private fun missing(field: String): CapabilityOutcome =
        CapabilityOutcome(false, "", "action.$field is required")

    private val DENIED = CapabilityOutcome(
        false,
        "",
        "Accessibility is not enabled. Open ZeroClaw → Settings → Device capabilities → " +
            "Accessibility and enable the ZeroClaw service.",
    )
}
