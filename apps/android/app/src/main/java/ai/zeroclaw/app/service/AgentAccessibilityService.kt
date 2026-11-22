package ai.zeroclaw.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.UUID

/**
 * Accessibility bridge for OpenClaw-style `mobile.ui.observe` / `mobile.ui.act`.
 *
 * The operator enables the service in system accessibility settings. A
 * snapshot assigns stable refs (`n0`, `n1`, ...) to the visible tree and keeps
 * each ref's child-index path, so a later act re-resolves the node against the
 * current window instead of holding stale node objects.
 */
class AgentAccessibilityService : AccessibilityService() {

    private data class Snapshot(
        val id: String,
        val packageName: String,
        val paths: Map<String, IntArray>,
        val lines: List<String>,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun observe(): SnapshotResult? {
        val root = rootInActiveWindow ?: return null
        val id = UUID.randomUUID().toString().take(8)
        val paths = LinkedHashMap<String, IntArray>()
        val lines = ArrayList<String>()
        var index = 0
        data class Entry(val node: AccessibilityNodeInfo, val path: IntArray, val depth: Int)

        val queue = ArrayDeque<Entry>()
        queue.add(Entry(root, IntArray(0), 0))
        while (queue.isNotEmpty() && index < MAX_NODES) {
            val entry = queue.removeFirst()
            val node = entry.node
            val ref = "n$index"
            index += 1
            paths[ref] = entry.path
            lines += describe(ref, node)
            if (entry.depth < MAX_DEPTH) {
                for (child in 0 until node.childCount) {
                    val childNode = runCatching { node.getChild(child) }.getOrNull() ?: continue
                    queue.add(Entry(childNode, entry.path + child, entry.depth + 1))
                }
            }
        }
        val result = Snapshot(id, root.packageName?.toString() ?: "unknown", paths, lines)
        snapshot = result
        return SnapshotResult(result.id, result.packageName, result.lines)
    }

    fun act(
        snapshotId: String,
        action: Action,
    ): ActionResult {
        val current = snapshot ?: return ActionResult(false, "No snapshot exists; call mobile.ui.observe first.")
        if (current.id != snapshotId) {
            return ActionResult(false, "Snapshot $snapshotId is stale; call mobile.ui.observe again.")
        }
        val root = rootInActiveWindow ?: return ActionResult(false, "No active window.")
        return when (action) {
            is Action.Click -> {
                val node = resolve(root, current, action.ref)
                    ?: return ActionResult(false, "Unknown node ref ${action.ref}")
                val clicked = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    .getOrDefault(false)
                if (clicked) {
                    ActionResult(true, "Clicked ${action.ref}.")
                } else {
                    tap(center(boundsOf(node)))
                }
            }
            is Action.SetText -> {
                val node = resolve(root, current, action.ref)
                    ?: return ActionResult(false, "Unknown node ref ${action.ref}")
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val bundle = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        action.text,
                    )
                }
                val ok = runCatching {
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                }.getOrDefault(false)
                ActionResult(ok, if (ok) "Set text on ${action.ref}." else "Set-text action was rejected.")
            }
            is Action.Scroll -> {
                val node = resolve(root, current, action.ref)
                    ?: return ActionResult(false, "Unknown node ref ${action.ref}")
                val direction = if (action.forward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                val ok = runCatching { node.performAction(direction) }.getOrDefault(false)
                ActionResult(ok, if (ok) "Scrolled ${action.ref}." else "Scroll action was rejected.")
            }
            is Action.Tap -> tap(Point(action.x, action.y))
            is Action.Swipe -> swipe(action)
            is Action.Global -> {
                val global = when (action.name) {
                    "back" -> GLOBAL_ACTION_BACK
                    "home" -> GLOBAL_ACTION_HOME
                    "recents" -> GLOBAL_ACTION_RECENTS
                    "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                    "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
                    else -> return ActionResult(false, "Unknown global action ${action.name}")
                }
                val ok = performGlobalAction(global)
                ActionResult(ok, if (ok) "Performed $global." else "Global action was rejected.")
            }
        }
    }

    private fun resolve(
        root: AccessibilityNodeInfo,
        snapshot: Snapshot,
        ref: String,
    ): AccessibilityNodeInfo? {
        val path = snapshot.paths[ref] ?: return null
        var node: AccessibilityNodeInfo = root
        for (index in path) {
            node = runCatching { node.getChild(index) }.getOrNull() ?: return null
        }
        return node
    }

    private fun describe(ref: String, node: AccessibilityNodeInfo): String = buildString {
        append(ref)
        append(" role=").append(node.className?.toString()?.substringAfterLast('.') ?: "view")
        node.viewIdResourceName?.let { append(" id=").append(it.substringAfterLast('/')) }
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
            append(" text=\"").append(it.take(120)).append('"')
        }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            append(" desc=\"").append(it.take(120)).append('"')
        }
        if (node.isClickable) append(" clickable")
        if (node.isEditable) append(" editable")
        val bounds = boundsOf(node)
        append(" bounds=").append(bounds.left).append(',').append(bounds.top)
            .append(',').append(bounds.right).append(',').append(bounds.bottom)
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds
    }

    private fun center(bounds: Rect): Point =
        Point((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2)

    private fun tap(point: Point): ActionResult {
        val path = Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val ok = dispatchGesture(gesture, null, null)
        return ActionResult(ok, if (ok) "Tapped ${point.x},${point.y}." else "Tap gesture was rejected.")
    }

    private fun swipe(action: Action.Swipe): ActionResult {
        val path = Path().apply {
            moveTo(action.x1.toFloat(), action.y1.toFloat())
            lineTo(action.x2.toFloat(), action.y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    action.durationMs.coerceIn(50, 3000).toLong(),
                ),
            )
            .build()
        val ok = dispatchGesture(gesture, null, null)
        return ActionResult(ok, if (ok) "Swiped ${action.x1},${action.y1} → ${action.x2},${action.y2}." else "Swipe was rejected.")
    }

    data class SnapshotResult(val id: String, val packageName: String, val lines: List<String>)

    data class ActionResult(val ok: Boolean, val message: String)

    data class Point(val x: Int, val y: Int)

    sealed interface Action {
        data class Click(val ref: String) : Action
        data class SetText(val ref: String, val text: String) : Action
        data class Scroll(val ref: String, val forward: Boolean) : Action
        data class Tap(val x: Int, val y: Int) : Action
        data class Swipe(
            val x1: Int,
            val y1: Int,
            val x2: Int,
            val y2: Int,
            val durationMs: Int,
        ) : Action
        data class Global(val name: String) : Action
    }

    companion object {
        private const val MAX_NODES = 800
        private const val MAX_DEPTH = 20

        @Volatile
        private var instance: AgentAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        fun observe(): SnapshotResult? = instance?.observe()

        fun act(snapshotId: String, action: Action): ActionResult =
            instance?.act(snapshotId, action)
                ?: ActionResult(false, "Accessibility service is not connected.")
    }
}
