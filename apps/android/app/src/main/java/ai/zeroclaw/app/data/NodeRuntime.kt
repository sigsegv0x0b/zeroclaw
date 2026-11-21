package ai.zeroclaw.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device-link lifecycle, shared between the service and the UI. */
sealed interface NodeStatus {
    data object Stopped : NodeStatus
    data object Connecting : NodeStatus
    data class Connected(val nodeId: String, val capabilityCount: Int) : NodeStatus
    data class Error(val message: String) : NodeStatus
}

/**
 * Process-wide node status. The foreground service owns the connection; the
 * view model only reads this.
 */
object NodeRuntime {
    private val _status = MutableStateFlow<NodeStatus>(NodeStatus.Stopped)
    val status: StateFlow<NodeStatus> = _status.asStateFlow()

    internal fun set(status: NodeStatus) {
        _status.value = status
    }
}
