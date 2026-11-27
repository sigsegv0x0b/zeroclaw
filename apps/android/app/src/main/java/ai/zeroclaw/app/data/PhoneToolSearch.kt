package ai.zeroclaw.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Deferred-schema discovery for this phone's capabilities.
 *
 * The gateway registers one tool per capability, so Settings → Tools and the
 * gateway's approval policy keep working per capability. What changes is the
 * payload: parameterized capabilities advertise a one-line summary and an open
 * schema, and the model fetches the real schema from `tool.search` on demand.
 *
 * This mirrors `crates/zeroclaw-tools/src/tool_search.rs`: same `select:` mode,
 * same `<functions>` envelope, same "activate only what you need" contract.
 */
data class PhoneCapabilitySpec(
    val name: String,
    val summary: String,
    val fullDescription: String,
    val fullParameters: JsonObject,
    val eagerSchema: Boolean,
)

object PhoneToolSearch {

    const val NAME = "tool.search"

    private const val DEFAULT_MAX_RESULTS = 5
    private const val MAX_SUMMARY_CHARS = 160

    val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put(
                    "description",
                    "Keywords, or \"select:<name1>,<name2>\" for exact tools. " +
                        "Accepts alarm.set, alarm_set, and " +
                        "node_android-1a2b3c4d__alarm_set.",
                )
            }
            putJsonObject("max_results") {
                put("type", "integer")
                put("default", DEFAULT_MAX_RESULTS)
                put("minimum", 1)
                put("maximum", 20)
            }
        }
        putJsonArray("required") { add(JsonPrimitive("query")) }
    }

    val definition: NodeCapabilityDef = NodeCapabilityDef(
        name = NAME,
        description = "Fetch the full parameter schemas for this phone's tools. The tool list " +
            "usually shows a one-line summary and an open schema, so call this before using a " +
            "phone tool whose arguments you are unsure about. Use " +
            "\"select:alarm.set,timer.set\" for exact tools or keywords to search. Also " +
            "returns the matching Android intent recipes for intent.launch. Discovery never " +
            "grants permission: tool approvals and the phone's own permission checks still " +
            "apply. This runs on the phone, not on the ZeroClaw host.",
        parameters = parameters,
    )

    /** Built lazily so this object never observes a partially initialized [DeviceCapabilities]. */
    val specs: List<PhoneCapabilitySpec> by lazy {
        DeviceCapabilities.fullDefinitions.map { definition ->
            PhoneCapabilitySpec(
                name = definition.name,
                summary = summaryLine(definition.description),
                fullDescription = definition.description,
                fullParameters = definition.parameters,
                eagerSchema = isEager(definition),
            )
        }
    }

    private val specsByName: Map<String, PhoneCapabilitySpec> by lazy {
        specs.associateBy { it.name }
    }

    private val specsBySanitized: Map<String, PhoneCapabilitySpec> by lazy {
        specs.associateBy { sanitize(it.name) }
    }

    fun execute(args: JsonObject): CapabilityOutcome {
        val query = args.string("query")?.trim().orEmpty()
        if (query.isEmpty()) {
            return CapabilityOutcome(false, "", "query is required")
        }
        val maxResults = (args.int("max_results") ?: DEFAULT_MAX_RESULTS).coerceIn(1, 20)
        if (query.startsWith("select:")) {
            return select(query.removePrefix("select:"))
        }
        return search(query, maxResults)
    }

    // ── Selection ─────────────────────────────────────────────────────────

    private fun select(raw: String): CapabilityOutcome {
        val requested = raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (requested.isEmpty()) {
            return CapabilityOutcome(false, "", "select: needs at least one tool name")
        }

        val functions = StringBuilder()
        val recipes = mutableListOf<IntentRecipe>()
        val missing = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        for (name in requested) {
            val canonical = resolveName(name)
            if (canonical != null) {
                if (!seen.add(canonical)) continue
                specsByName[canonical]?.let { spec ->
                    writeFunction(functions, spec.name, spec.fullDescription, spec.fullParameters)
                }
                continue
            }
            val recipe = IntentCapabilities.resolveRecipe(name)
            if (recipe != null) {
                if (recipes.none { it.id == recipe.id }) recipes += recipe
                continue
            }
            missing += name
        }

        if (functions.isEmpty() && recipes.isEmpty()) {
            return CapabilityOutcome(
                false,
                "",
                "Not found: ${missing.joinToString(", ")}. " +
                    "Use keywords to search, or device.apps for installed packages.",
            )
        }

        if (recipes.isNotEmpty()) {
            writeFunction(
                functions,
                IntentCapabilities.LAUNCH,
                intentLaunchDescription(),
                IntentCapabilities.launchParameters,
            )
        }

        val output = StringBuilder("<functions>\n")
            .append(functions)
            .append("</functions>\n")
        if (recipes.isNotEmpty()) output.append(recipeBlock(recipes))
        if (missing.isNotEmpty()) {
            output.append("Not found: ").append(missing.joinToString(", ")).append('\n')
        }
        return CapabilityOutcome(true, output.toString())
    }

    // ── Search ────────────────────────────────────────────────────────────

    private fun search(query: String, maxResults: Int): CapabilityOutcome {
        val terms = query.lowercase()
            .split(Regex("[^a-z0-9.]+"))
            .filter { it.length > 1 }
        if (terms.isEmpty()) {
            return CapabilityOutcome(false, "", "query has no searchable terms")
        }

        val rankedSpecs = specs
            .map { it to specScore(it, terms) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<PhoneCapabilitySpec, Int>> { it.second }
                .thenBy { it.first.name })
            .take(maxResults)

        val recipes = IntentCapabilities.rank(query, maxResults)

        if (rankedSpecs.isEmpty() && recipes.isEmpty()) {
            return CapabilityOutcome(
                true,
                "No matching tools found. Try a capability family such as clock, location, " +
                    "files, gallery, camera, device, contacts, calendar, sms, notifications, " +
                    "accessibility, voice, navigation, or settings.",
            )
        }

        val functions = StringBuilder()
        for ((spec, _) in rankedSpecs) {
            writeFunction(functions, spec.name, spec.fullDescription, spec.fullParameters)
        }
        if (recipes.isNotEmpty()) {
            writeFunction(
                functions,
                IntentCapabilities.LAUNCH,
                intentLaunchDescription(),
                IntentCapabilities.launchParameters,
            )
        }

        val output = StringBuilder("<functions>\n")
            .append(functions)
            .append("</functions>\n")
        if (recipes.isNotEmpty()) output.append(recipeBlock(recipes))
        return CapabilityOutcome(true, output.toString())
    }

    private fun specScore(spec: PhoneCapabilitySpec, terms: List<String>): Int {
        var total = 0
        for (term in terms) {
            var best = 0
            if (spec.name.split('.', '_').any { it.equals(term, ignoreCase = true) }) best = 10
            if (spec.summary.contains(term, ignoreCase = true)) best = maxOf(best, 5)
            if (spec.fullDescription.contains(term, ignoreCase = true)) best = maxOf(best, 3)
            if (parameterNames(spec.fullParameters).any { it.contains(term, ignoreCase = true) }) {
                best = maxOf(best, 2)
            }
            if (best == 0) return 0
            total += best
        }
        return total
    }

    private fun parameterNames(parameters: JsonObject): List<String> {
        val properties = parameters["properties"] as? JsonObject ?: return emptyList()
        return properties.keys.toList()
    }

    // ── Formatting ────────────────────────────────────────────────────────

    private fun writeFunction(
        out: StringBuilder,
        name: String,
        description: String,
        parameters: JsonObject,
    ) {
        out.append("<function>{")
        out.append("\"name\": ").append(JsonPrimitive(name).toString()).append(", ")
        out.append("\"description\": ").append(JsonPrimitive(description).toString()).append(", ")
        out.append("\"parameters\": ").append(parameters.toString())
        out.append("}</function>\n")
    }

    private fun recipeBlock(recipes: List<IntentRecipe>): String = buildJsonObject {
        putJsonArray("intent_recipes") {
            recipes.forEach { recipe ->
                add(
                    buildJsonObject {
                        put("id", recipe.id)
                        put("title", recipe.title)
                        put("family", recipe.family)
                        put("summary", recipe.summary)
                        put("risk", recipe.risk.wire)
                        put("parameters", recipe.schema)
                        putJsonObject("example") {
                            put("recipe", recipe.id)
                            recipe.exampleArgs.forEach { (key, value) -> put(key, value) }
                        }
                    },
                )
            }
        }
        put("hint", "Call intent.launch with recipe and its arguments.")
    }.let { "<intent-recipes>\n$it\n</intent-recipes>\n" }

    private fun intentLaunchDescription(): String =
        "Run an Android intent recipe on this phone. Prefer this over guessing a URL. " +
            "Use the recipe id and parameters returned by tool.search or intent.search."

    // ── Naming ────────────────────────────────────────────────────────────

    /**
     * Resolve the spellings a model or policy might use to one canonical
     * capability name: `alarm.set`, `alarm_set`, and
     * `node_android-1a2b3c4d__alarm_set` all mean the same tool.
     */
    fun resolveName(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val stripped = stripNodePrefix(raw)
        specsByName[stripped]?.let { return it.name }
        specsBySanitized[sanitize(stripped)]?.let { return it.name }
        return null
    }

    internal fun stripNodePrefix(name: String): String {
        val marker = name.indexOf("__")
        return if (name.startsWith("node_") && marker > 0) name.substring(marker + 2) else name
    }

    internal fun sanitize(name: String): String = name.replace('.', '_').lowercase()

    /** First non-empty line, capped for the compact registration description. */
    internal fun summaryLine(description: String): String {
        val line = description.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        if (line.length <= MAX_SUMMARY_CHARS) return line
        val cut = line.take(MAX_SUMMARY_CHARS)
        val boundary = cut.lastIndexOf(". ")
        val clipped = if (boundary > 40) cut.substring(0, boundary + 1) else cut.trimEnd()
        return if (clipped.endsWith(".")) clipped else "$clipped."
    }

    /**
     * Keep the full schema for discovery tools and zero-argument capabilities:
     * those are cheap and the model can call them without a lookup.
     */
    fun isEager(definition: NodeCapabilityDef): Boolean {
        if (definition.name == NAME ||
            definition.name == IntentCapabilities.SEARCH ||
            definition.name == IntentCapabilities.LAUNCH
        ) {
            return true
        }
        val properties = definition.parameters["properties"] as? JsonObject
        return properties == null || properties.isEmpty()
    }
}
