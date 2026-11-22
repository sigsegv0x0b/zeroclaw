package ai.zeroclaw.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Typed reads for the capability argument objects the model sends. */
internal fun JsonObject.string(key: String): String? {
    val value = this[key] as? JsonPrimitive ?: return null
    return if (value.isString) value.content else null
}

internal fun JsonObject.int(key: String): Int? {
    val value = this[key] as? JsonPrimitive ?: return null
    return value.content.toIntOrNull()
}

internal fun JsonObject.long(key: String): Long? {
    val value = this[key] as? JsonPrimitive ?: return null
    return value.content.toLongOrNull()
}

internal fun JsonObject.bool(key: String, default: Boolean): Boolean {
    val value = this[key] as? JsonPrimitive ?: return default
    return when (value.content.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> default
    }
}
