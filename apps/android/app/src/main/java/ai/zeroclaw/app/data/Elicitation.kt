package ai.zeroclaw.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One selectable value in a form-mode elicitation. */
data class ElicitationOption(
    /** Wire value sent back in `content` (`choice-0`, `choice-1`, …). */
    val value: String,
    /** User-visible label from the schema title. */
    val label: String,
)

/**
 * The subset of ACP form-mode elicitation the app can render today:
 * a single choice, multiple choices, an optional typed answer, or a
 * free-form-only answer. The wire schema stays authoritative; this model
 * is just the parsed view the Compose dialog needs.
 */
data class ElicitationPrompt(
    val requestId: String,
    val message: String,
    /** `choice`, `choices`, or null for a free-form-only form. */
    val choiceProperty: String?,
    val options: List<ElicitationOption>,
    val multiSelect: Boolean,
    /** `custom`, `answer`, or null when the form has no text field. */
    val freeFormProperty: String?,
    val freeFormLabel: String,
    val freeFormRequired: Boolean,
    val timeoutSecs: Int?,
)

/**
 * Parse the restricted JSON Schema carried by an `elicitation_request`.
 * Returns null when the form uses a shape this app cannot render; the caller
 * should then leave the gateway timeout to fail the tool closed.
 */
internal fun parseElicitationPrompt(
    requestId: String,
    message: String,
    schema: JsonObject,
    timeoutSecs: Int?,
): ElicitationPrompt? {
    val properties = schema["properties"] as? JsonObject ?: return null
    val required = (schema["required"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
        ?.toSet()
        .orEmpty()

    var choiceProperty: String? = null
    var multiSelect = false
    var options = emptyList<ElicitationOption>()

    // Property names are not fixed by ACP; find the first single- or
    // multi-select property the app can render.
    for ((property, value) in properties) {
        val field = value as? JsonObject ?: continue
        val single = parseOptions(field["oneOf"] as? JsonArray)
            .ifEmpty { parseEnumOptions(field["enum"] as? JsonArray) }
        if (single.isNotEmpty() && field.string("type") == "string") {
            choiceProperty = property
            options = single
            break
        }

        val items = field["items"] as? JsonObject
        val multiple = parseOptions(items?.get("anyOf") as? JsonArray)
            .ifEmpty { parseEnumOptions(items?.get("enum") as? JsonArray) }
        if (multiple.isNotEmpty()) {
            choiceProperty = property
            options = multiple
            multiSelect = true
            break
        }
    }

    var freeFormProperty: String? = null
    var freeFormLabel = "Type your own"
    val custom = properties["custom"] as? JsonObject
    if (custom?.string("type") == "string") {
        freeFormProperty = "custom"
        freeFormLabel = custom.string("title") ?: "Type your own"
    } else if (choiceProperty == null) {
        // Free-form-only form: the first string property is the answer field.
        val entry = properties.entries.firstOrNull { (_, value) ->
            (value as? JsonObject)?.string("type") == "string"
        }
        if (entry != null) {
            freeFormProperty = entry.key
            freeFormLabel = (entry.value as? JsonObject)?.string("title") ?: "Your answer"
        }
    }

    if (choiceProperty == null && freeFormProperty == null) return null

    return ElicitationPrompt(
        requestId = requestId,
        message = message,
        choiceProperty = choiceProperty,
        options = options,
        multiSelect = multiSelect,
        freeFormProperty = freeFormProperty,
        freeFormLabel = freeFormLabel,
        freeFormRequired = freeFormProperty != null && freeFormProperty in required,
        timeoutSecs = timeoutSecs,
    )
}

private fun parseOptions(array: JsonArray?): List<ElicitationOption> =
    array?.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val value = obj.string("const") ?: return@mapNotNull null
        ElicitationOption(value = value, label = obj.string("title") ?: value)
    }.orEmpty()

/** Bare enum entries carry display text; the wire value stays index-based. */
private fun parseEnumOptions(array: JsonArray?): List<ElicitationOption> =
    array?.mapIndexedNotNull { index, item ->
        val text = (item as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
            ?: return@mapIndexedNotNull null
        ElicitationOption(value = "choice-$index", label = text)
    }.orEmpty()
