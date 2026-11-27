package ai.zeroclaw.app.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Android intent discovery and execution.
 *
 * The catalog is the single source of truth: [recipes] both generates the
 * search index and validates every launch. The model can only name a recipe
 * (or a raw intent that passes the allowlists below); it can never hand us an
 * arbitrary [Intent].
 *
 * Android-free code lives here so unit tests can build and validate intents on
 * the JVM. [IntentHandlerProbe] is the only seam into `PackageManager`, and
 * [launch] is the only function that touches `android.content.Intent`.
 */

// ── Catalog model ─────────────────────────────────────────────────────────

enum class IntentRisk(val wire: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

enum class IntentParamType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    ENUM,
    ENUM_LIST,
}

/**
 * How a [IntentParamType.STRING] value is encoded into a URI. `TEXT` is
 * percent-encoded, `URL` keeps its reserved characters after scheme checks,
 * and `PHONE` keeps dial characters instead of escaping `+` to `%2B`.
 */
enum class IntentParamFormat {
    TEXT,
    URL,
    PHONE,
    EMAIL,
}

data class IntentParam(
    val name: String,
    val type: IntentParamType,
    val required: Boolean = false,
    val description: String = "",
    val values: List<String> = emptyList(),
    val minimum: Double? = null,
    val maximum: Double? = null,
    val maxLength: Int? = 512,
    val format: IntentParamFormat = IntentParamFormat.TEXT,
    val default: String? = null,
)

/** One way to build a URI for a recipe. The first variant whose [requires] are all present wins. */
data class UriVariant(
    val template: String,
    val requires: List<String> = emptyList(),
    val type: String? = null,
    val packageName: String? = null,
)

data class ExtraMapping(
    val key: String,
    val param: String,
)

data class SettingsTarget(
    val screen: String,
    val action: String,
    val uriPrefix: String? = null,
    /** Extra whose value supplies the package for `package:`-style screens. */
    val packageParam: String? = null,
)

sealed interface RecipePlan {
    data class Action(
        val action: String,
        val variants: List<UriVariant>,
        val extras: List<ExtraMapping> = emptyList(),
        val fallbackTemplate: String? = null,
        val chooserTitle: String? = null,
    ) : RecipePlan

    data class Screens(val targets: List<SettingsTarget>) : RecipePlan

    data class LaunchApp(val packageParam: String = "package") : RecipePlan
}

data class IntentRecipe(
    val id: String,
    val family: String,
    val title: String,
    val summary: String,
    val keywords: List<String>,
    val params: List<IntentParam>,
    val plan: RecipePlan,
    val risk: IntentRisk,
    /** Arguments used to build an availability probe and shown to the model as an example. */
    val exampleArgs: Map<String, String>,
) {
    val schema: JsonObject get() = intentSchema(params)
}

/** A resolved intent that is safe to hand to Android. */
data class ResolvedIntent(
    val action: String,
    val uri: String?,
    val type: String?,
    val packageName: String?,
    val categories: List<String>,
    val extras: Map<String, ExtraValue>,
    val chooserTitle: String?,
    /** Documented URL to use when the primary handler is not installed. */
    val fallbackUri: String? = null,
)

sealed interface ExtraValue {
    data class Text(val value: String) : ExtraValue
    data class Flag(val value: Boolean) : ExtraValue
    data class Number(val value: Long) : ExtraValue
    data class Decimal(val value: Double) : ExtraValue
    data class TextList(val values: List<String>) : ExtraValue
}

/** PackageManager seam: returns `"Label (package)"` strings for a candidate intent. */
fun interface IntentHandlerProbe {
    fun handlers(action: String, uri: String?, type: String?, packageName: String?): List<String>

    companion object {
        val None: IntentHandlerProbe = IntentHandlerProbe { _, _, _, _ -> emptyList() }
    }
}

object IntentCapabilities {

    const val SEARCH = "intent.search"
    const val LAUNCH = "intent.launch"

    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    private const val MAX_RAW_URI_CHARS = 2048
    private const val MAX_EXTRA_COUNT = 20
    private const val MAX_EXTRA_CHARS = 2048
    private const val MAX_EXTRA_VALUES = 50

    /**
     * Raw (non-recipe) intents are default-closed: only these actions are
     * accepted, on top of the scheme allowlist in [validateRaw].
     */
    private val ALLOWED_RAW_ACTIONS = setOf(
        Intent.ACTION_VIEW,
        Intent.ACTION_DIAL,
        Intent.ACTION_SENDTO,
        Intent.ACTION_SEND,
        "android.intent.action.WEB_SEARCH",
        "android.media.action.MEDIA_PLAY_FROM_SEARCH",
        Intent.ACTION_INSERT,
    )

    /**
     * Actions that must never be dispatched even if a future change widens the
     * allowlist. Kept explicit so a rejection names the real reason.
     */
    private val BLOCKED_ACTIONS = setOf(
        "android.intent.action.CALL",
        "android.intent.action.CALL_PRIVILEGED",
        "android.intent.action.INSTALL_PACKAGE",
        "android.intent.action.UNINSTALL_PACKAGE",
        "android.intent.action.DELETE",
        "android.intent.action.REBOOT",
        "android.intent.action.SHUTDOWN",
        "android.intent.action.FACTORY_RESET",
        "android.intent.action.MASTER_CLEAR",
        "android.intent.action.PACKAGE_REMOVED",
    )

    private val ALLOWED_URI_SCHEMES = setOf(
        "https",
        "http",
        "geo",
        "google.navigation",
        "tel",
        "mailto",
        "smsto",
    )

    private const val OWN_PACKAGE = "ai.zeroclaw.app"

    // ── Catalog ───────────────────────────────────────────────────────────

    val recipes: List<IntentRecipe> = listOf(
        IntentRecipe(
            id = "maps.navigate",
            family = "navigation",
            title = "Navigate to a destination",
            summary = "Turn-by-turn navigation to a place or address.",
            keywords = listOf(
                "navigate", "navigation", "directions", "route", "drive", "walk",
                "google maps", "maps", "take me to", "go to",
            ),
            params = listOf(
                IntentParam(
                    name = "destination",
                    type = IntentParamType.STRING,
                    required = true,
                    description = "Address, place name, or \"lat,lng\"",
                    maxLength = 200,
                ),
                IntentParam(
                    name = "mode",
                    type = IntentParamType.ENUM,
                    values = listOf("driving", "walking", "bicycling", "two_wheeler"),
                    description = "Travel mode",
                    default = "driving",
                ),
                IntentParam(
                    name = "avoid",
                    type = IntentParamType.ENUM_LIST,
                    values = listOf("tolls", "highways", "ferries"),
                    description = "Route features to avoid",
                ),
            ),
            plan = RecipePlan.Action(
                action = Intent.ACTION_VIEW,
                variants = listOf(
                    UriVariant(
                        template = "google.navigation:q={destination}&mode={modeCode}&avoid={avoidCodes}",
                        packageName = MAPS_PACKAGE,
                    ),
                ),
                fallbackTemplate = "https://www.google.com/maps/dir/?api=1" +
                    "&destination={destination}&travelmode={travelmode}&dir_action=navigate",
            ),
            risk = IntentRisk.LOW,
            exampleArgs = mapOf("destination" to "CN Tower, Toronto"),
        ),
        IntentRecipe(
            id = "maps.search",
            family = "navigation",
            title = "Search the map",
            summary = "Show a place, address, or coordinates on the map.",
            keywords = listOf(
                "map", "maps", "search map", "find place", "show on map",
                "coordinates", "latitude", "longitude", "pin",
            ),
            params = listOf(
                IntentParam(
                    name = "query",
                    type = IntentParamType.STRING,
                    description = "Place or address to search for",
                    maxLength = 200,
                ),
                IntentParam(
                    name = "lat",
                    type = IntentParamType.NUMBER,
                    description = "Latitude; pair with lng",
                    minimum = -90.0,
                    maximum = 90.0,
                ),
                IntentParam(
                    name = "lng",
                    type = IntentParamType.NUMBER,
                    description = "Longitude; pair with lat",
                    minimum = -180.0,
                    maximum = 180.0,
                ),
                IntentParam(
                    name = "zoom",
                    type = IntentParamType.INTEGER,
                    description = "Map zoom level",
                    minimum = 1.0,
                    maximum = 21.0,
                ),
            ),
            plan = RecipePlan.Action(
                action = Intent.ACTION_VIEW,
                variants = listOf(
                    UriVariant(
                        template = "geo:{lat},{lng}?z={zoom}",
                        requires = listOf("lat", "lng"),
                    ),
                    UriVariant(
                        template = "geo:0,0?q={query}",
                        requires = listOf("query"),
                    ),
                ),
            ),
            risk = IntentRisk.LOW,
            exampleArgs = mapOf("query" to "coffee"),
        ),
        IntentRecipe(
            id = "maps.directions",
            family = "navigation",
            title = "Preview directions",
            summary = "Show a route preview between two places in Maps.",
            keywords = listOf(
                "directions", "route", "how do i get", "from to", "maps url",
                "preview route", "transit",
            ),
            params = listOf(
                IntentParam(
                    name = "destination",
                    type = IntentParamType.STRING,
                    required = true,
                    description = "End point",
                    maxLength = 200,
                ),
                IntentParam(
                    name = "origin",
                    type = IntentParamType.STRING,
                    description = "Start point; defaults to the current location",
                    maxLength = 200,
                ),
                IntentParam(
                    name = "travelmode",
                    type = IntentParamType.ENUM,
                    values = listOf("driving", "walking", "bicycling", "transit"),
                    description = "Travel mode",
                    default = "driving",
                ),
            ),
            plan = RecipePlan.Action(
                action = Intent.ACTION_VIEW,
                variants = listOf(
                    UriVariant(
                        template = "https://www.google.com/maps/dir/?api=1" +
                            "&origin={origin}&destination={destination}" +
                            "&travelmode={travelmode}&dir_action=navigate",
                        requires = listOf("origin"),
                    ),
                    UriVariant(
                        template = "https://www.google.com/maps/dir/?api=1" +
                            "&destination={destination}&travelmode={travelmode}&dir_action=navigate",
                    ),
                ),
            ),
            risk = IntentRisk.LOW,
            exampleArgs = mapOf("destination" to "CN Tower, Toronto"),
        ),
        IntentRecipe(
            id = "phone.dial",
            family = "calls",
            title = "Open the dialer",
            summary = "Open the phone dialer with a number filled in. Does not place the call.",
            keywords = listOf("call", "dial", "phone", "ring", "telephone", "dialer"),
            params = listOf(
                IntentParam(
                    name = "number",
                    type = IntentParamType.STRING,
                    required = true,
                    description = "Phone number to pre-fill",
                    maxLength = 40,
                    format = IntentParamFormat.PHONE,
                ),
            ),
            plan = RecipePlan.Action(
                action = Intent.ACTION_DIAL,
                variants = listOf(UriVariant(template = "tel:{number}")),
            ),
            risk = IntentRisk.MEDIUM,
            exampleArgs = mapOf("number" to "+15550100"),
        ),
        IntentRecipe(
            id = "sms.compose",
            family = "messaging",
            title = "Compose an SMS",
            summary = "Open the messaging app with a recipient and draft message.",
            keywords = listOf("sms", "text message", "message", "send text", "compose sms"),
            params = listOf(
                IntentParam(
                    name = "to",
                    type = IntentParamType.STRING,
                    required = true,
                    description = "Recipient phone number",
                    maxLength = 40,
                    format = IntentParamFormat.PHONE,
                ),
                IntentParam(
                    name = "body",
                    type = IntentParamType.STRING,
                    description = "Draft message body",
                    maxLength = 2000,
                ),
            ),
            plan = RecipePlan.Action(
                action = Intent.ACTION_SENDTO,
                variants = listOf(UriVariant(template = "smsto:{to}")),
                extras = listOf(ExtraMapping("sms_body", "body")),
            ),
            risk = IntentRisk.HIGH,
            exampleArgs = mapOf("to" to "+15550100", "body" to "On my way"),
        ),
        IntentRecipe(
            id = "email.compose",
            family = "email",
            title = "Compose an email",
            summary = "Open the email app with recipient, subject, and body filled in.",
            keywords = listOf("email", "mail", "compose email", "send email", "gmail"),
            params = listOf(
                IntentParam(
                    name = "to",
                    type = IntentParamType.STRING,
                    required = true,
                    description = "Recipient address",
                    maxLength = 320,
                    format = IntentParamFormat.EMAIL,
                ),
                IntentParam(
                    name = "subject",
                    type = IntentParamType.STRING,
                    description = "Subject line",
                    maxLength = 200,
                ),
                IntentParam(
                    name = "body",
                    type = IntentParamType.STRING,
                    description = "Message body",
                    maxLength = 5000,
                ),
            ),
            plan = RecipePlan.Action(
                action = Intent.ACTION_SENDTO,
                variants = listOf(UriVariant(template = "mailto:{to}")),
                extras = listOf(
                    ExtraMapping(Intent.EXTRA_SUBJECT, "subject"),
                    ExtraMapping(Intent.EXTRA_TEXT, "body"),
                ),
            ),
            risk = IntentRisk.HIGH,
            exampleArgs = mapOf("to" to "person@example.com", "subject" to "Hello"),
        ),
        IntentRecipe(
            id = "media.play_search",
            family = "media",
            title = "Play media by search",
            summary = "Ask the default media app to play music, an artist, or an album.",
            keywords = listOf(
                "play", "music", "song", "album", "artist", "podcast",
                "spotify", "youtube music", "media",
            ),
            params = listOf(
                IntentParam(
                    name = "query",
                    type = IntentParamType.STRING,
                    required = true,
                    description = "What to play",
                    maxLength = 200,
                ),
                IntentParam(
                    name = "focus",
                    type = IntentParamType.ENUM,
                    values = listOf("music", "artist", "album", "playlist"),
                    description = "What kind of media the query names",
                ),
            ),
            plan = RecipePlan.Action(
                action = MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH,
                variants = emptyList(),
                extras = listOf(
                    ExtraMapping("query", "query"),
                    ExtraMapping(MediaStore.EXTRA_MEDIA_FOCUS, "focus"),
                ),
            ),
            risk = IntentRisk.LOW,
            exampleArgs = mapOf("query" to "jazz"),
        ),
        IntentRecipe(
            id = "web.open",
            family = "web",
            title = "Open a link",
            summary = "Open an http(s) URL or app deep link in the browser or its app.",
            keywords = listOf("open link", "url", "website", "browser", "deep link", "web page"),
            params = listOf(
                IntentParam(
                    name = "url",
                    type = IntentParamType.STRING,
                    required = true,
                    description = "http:// or https:// URL",
                    maxLength = MAX_RAW_URI_CHARS,
                    format = IntentParamFormat.URL,
                ),
            ),
            plan = RecipePlan.Action(
                action = Intent.ACTION_VIEW,
                variants = listOf(UriVariant(template = "{url}")),
            ),
            risk = IntentRisk.LOW,
            exampleArgs = mapOf("url" to "https://example.com"),
        ),
        IntentRecipe(
            id = "web.search",
            family = "web",
            title = "Search the web",
            summary = "Run a web search in the device's default search app.",
            keywords = listOf("search web", "google", "look up", "browse", "web search"),
            params = listOf(
                IntentParam(
                    name = "query",
                    type = IntentParamType.STRING,
                    required = true,
                    description = "Search terms",
                    maxLength = 300,
                ),
            ),
            plan = RecipePlan.Action(
                action = "android.intent.action.WEB_SEARCH",
                variants = emptyList(),
                extras = listOf(ExtraMapping("query", "query")),
            ),
            risk = IntentRisk.LOW,
            exampleArgs = mapOf("query" to "zeroclaw"),
        ),
        IntentRecipe(
            id = "share.text",
            family = "share",
            title = "Share text",
            summary = "Open the Android share sheet with text or a link.",
            keywords = listOf("share", "send to", "share sheet", "send link", "copy to app"),
            params = listOf(
                IntentParam(
                    name = "text",
                    type = IntentParamType.STRING,
                    required = true,
                    description = "Text to share",
                    maxLength = 5000,
                ),
                IntentParam(
                    name = "subject",
                    type = IntentParamType.STRING,
                    description = "Optional subject for apps that use one",
                    maxLength = 200,
                ),
            ),
            plan = RecipePlan.Action(
                action = Intent.ACTION_SEND,
                variants = listOf(UriVariant(template = "", type = "text/plain")),
                extras = listOf(
                    ExtraMapping(Intent.EXTRA_TEXT, "text"),
                    ExtraMapping(Intent.EXTRA_SUBJECT, "subject"),
                ),
                chooserTitle = "Share via",
            ),
            risk = IntentRisk.MEDIUM,
            exampleArgs = mapOf("text" to "Hello from ZeroClaw"),
        ),
        IntentRecipe(
            id = "settings.open",
            family = "settings",
            title = "Open a system setting",
            summary = "Open a specific Android Settings screen.",
            keywords = listOf(
                "settings", "wifi", "bluetooth", "location settings", "airplane mode",
                "sound", "display", "battery saver", "app info", "system settings",
            ),
            params = listOf(
                IntentParam(
                    name = "screen",
                    type = IntentParamType.ENUM,
                    required = true,
                    values = listOf(
                        "main", "wifi", "bluetooth", "location", "airplane",
                        "sound", "display", "battery_saver", "app_details",
                    ),
                    description = "Settings screen to open",
                ),
                IntentParam(
                    name = "package",
                    type = IntentParamType.STRING,
                    description = "Package name for app_details",
                    maxLength = 200,
                ),
            ),
            plan = RecipePlan.Screens(
                targets = listOf(
                    SettingsTarget("main", Settings.ACTION_SETTINGS),
                    SettingsTarget("wifi", Settings.ACTION_WIFI_SETTINGS),
                    SettingsTarget("bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS),
                    SettingsTarget("location", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                    SettingsTarget("airplane", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
                    SettingsTarget("sound", Settings.ACTION_SOUND_SETTINGS),
                    SettingsTarget("display", Settings.ACTION_DISPLAY_SETTINGS),
                    SettingsTarget("battery_saver", Settings.ACTION_BATTERY_SAVER_SETTINGS),
                    SettingsTarget(
                        screen = "app_details",
                        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        uriPrefix = "package:",
                        packageParam = "package",
                    ),
                ),
            ),
            risk = IntentRisk.LOW,
            exampleArgs = mapOf("screen" to "wifi"),
        ),
        IntentRecipe(
            id = "app.launch",
            family = "apps",
            title = "Launch an app",
            summary = "Open an installed app by package name.",
            keywords = listOf("open app", "launch app", "start app", "open application", "package"),
            params = listOf(
                IntentParam(
                    name = "package",
                    type = IntentParamType.STRING,
                    required = true,
                    description = "Application package name",
                    maxLength = 200,
                ),
            ),
            plan = RecipePlan.LaunchApp(),
            risk = IntentRisk.MEDIUM,
            exampleArgs = mapOf("package" to "com.android.settings"),
        ),
    )

    private val byId: Map<String, IntentRecipe> = recipes.associateBy { it.id }

    /** Rank recipes against a free-text query, best first. Used by `tool.search`. */
    fun rank(query: String, maxResults: Int = 5): List<IntentRecipe> = recipes
        .map { it to score(it, query) }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<IntentRecipe, Int>> { it.second }.thenBy { it.first.id })
        .take(maxResults.coerceIn(1, 20))
        .map { it.first }

    /** Canonical id names sanitized the way gateway tool names are, for lookup convenience. */
    private val bySanitizedName: Map<String, IntentRecipe> =
        recipes.associateBy { it.id.replace('.', '_').lowercase() }

    // ── Parameters exposed to the model ───────────────────────────────────

    val searchParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put(
                    "description",
                    "Keywords to find an intent recipe, or \"select:<recipe_id>\" for one " +
                        "recipe's full parameter schema.",
                )
            }
            putJsonObject("action") {
                put("type", "string")
                put("description", "Optional raw intent action to probe (advanced).")
            }
            putJsonObject("uri") {
                put("type", "string")
                put("description", "Optional raw intent data URI to probe (advanced).")
            }
            putJsonObject("type") {
                put("type", "string")
                put("description", "Optional MIME type for the raw probe.")
            }
            putJsonObject("package") {
                put("type", "string")
                put("description", "Optional package to probe the raw intent against.")
            }
            putJsonObject("max_results") {
                put("type", "integer")
                put("default", 5)
                put("minimum", 1)
                put("maximum", 20)
            }
        }
        putJsonArray("required") { add(JsonPrimitive("query")) }
    }

    val launchParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("recipe") {
                put("type", "string")
                put(
                    "description",
                    "Recipe id from intent.search, e.g. maps.navigate, phone.dial, app.launch.",
                )
                putJsonArray("enum") { recipes.forEach { add(JsonPrimitive(it.id)) } }
            }
            putJsonObject("package") {
                put("type", "string")
                put("description", "Package name for recipe=app.launch or settings.open app_details.")
            }
            putJsonObject("destination") { put("type", "string") }
            putJsonObject("origin") { put("type", "string") }
            putJsonObject("mode") {
                put("type", "string")
                putJsonArray("enum") {
                    listOf("driving", "walking", "bicycling", "two_wheeler")
                        .forEach { add(JsonPrimitive(it)) }
                }
            }
            putJsonObject("avoid") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "string")
                    putJsonArray("enum") {
                        listOf("tolls", "highways", "ferries").forEach { add(JsonPrimitive(it)) }
                    }
                }
            }
            putJsonObject("travelmode") {
                put("type", "string")
                putJsonArray("enum") {
                    listOf("driving", "walking", "bicycling", "transit")
                        .forEach { add(JsonPrimitive(it)) }
                }
            }
            putJsonObject("query") { put("type", "string") }
            putJsonObject("focus") {
                put("type", "string")
                putJsonArray("enum") {
                    listOf("music", "artist", "album", "playlist").forEach { add(JsonPrimitive(it)) }
                }
            }
            putJsonObject("lat") { put("type", "number") }
            putJsonObject("lng") { put("type", "number") }
            putJsonObject("zoom") { put("type", "integer") }
            putJsonObject("number") { put("type", "string") }
            putJsonObject("to") { put("type", "string") }
            putJsonObject("subject") { put("type", "string") }
            putJsonObject("body") { put("type", "string") }
            putJsonObject("text") { put("type", "string") }
            putJsonObject("url") { put("type", "string") }
            putJsonObject("screen") {
                put("type", "string")
                putJsonArray("enum") {
                    listOf(
                        "main", "wifi", "bluetooth", "location", "airplane",
                        "sound", "display", "battery_saver", "app_details",
                    ).forEach { add(JsonPrimitive(it)) }
                }
            }

            putJsonObject("action") {
                put("type", "string")
                put(
                    "description",
                    "Raw mode only, used instead of recipe. One of VIEW, DIAL, SENDTO, " +
                        "SEND, WEB_SEARCH, MEDIA_PLAY_FROM_SEARCH, INSERT.",
                )
            }
            putJsonObject("uri") {
                put("type", "string")
                put("description", "Raw mode data URI. Scheme allowlist applies.")
            }
            putJsonObject("categories") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("extras") {
                put("type", "object")
                put(
                    "description",
                    "Raw mode extras: string, boolean, or number values only. " +
                        "No nested objects.",
                )
            }
            putJsonObject("chooser_title") {
                put("type", "string")
                put("description", "Show the Android share sheet with this title.")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("recipe")) }
    }

    val definitions: List<NodeCapabilityDef> = listOf(
        NodeCapabilityDef(
            name = SEARCH,
            description = "Find an Android intent this phone can run: navigation, map search, " +
                "dialing, SMS or email compose, media playback, web links and search, sharing, " +
                "system settings, and app launching. Call this before intent.launch when you " +
                "do not already know the recipe id. Families: navigation, calls, messaging, " +
                "email, media, web, share, settings, apps. Use \"select:<id>\" for one " +
                "recipe's full parameter schema. This runs on the phone, not on the ZeroClaw host.",
            parameters = searchParameters,
        ),
        NodeCapabilityDef(
            name = LAUNCH,
            description = "Open another app on this phone from an intent.search recipe, or from " +
                "an allowlisted raw intent. Prefer a recipe: it is validated and encoded for " +
                "you. Raw mode accepts only VIEW, DIAL, SENDTO, SEND, WEB_SEARCH, " +
                "MEDIA_PLAY_FROM_SEARCH and INSERT. This runs on the phone, not on the " +
                "ZeroClaw host.",
            parameters = launchParameters,
        ),
    )

    // ── Search ────────────────────────────────────────────────────────────

    fun search(args: JsonObject, probe: IntentHandlerProbe = IntentHandlerProbe.None): CapabilityOutcome {
        val query = args.string("query")?.trim().orEmpty()
        val rawAction = args.string("action")
        val rawUri = args.string("uri")
        if (query.isEmpty() && rawAction == null && rawUri == null) {
            return CapabilityOutcome(false, "", "query is required")
        }
        val maxResults = (args.int("max_results") ?: 5).coerceIn(1, 20)

        if (rawAction != null || rawUri != null) {
            return probeRaw(args, rawAction, rawUri, args.string("type"), args.string("package"), probe)
        }

        if (query.startsWith("select:")) {
            val requested = query.removePrefix("select:")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (requested.isEmpty()) {
                return CapabilityOutcome(false, "", "select: needs at least one recipe id")
            }
            val found = requested.mapNotNull { resolveRecipe(it) }
            val missing = requested.filter { resolveRecipe(it) == null }
            if (found.isEmpty()) {
                return CapabilityOutcome(
                    false,
                    "",
                    "Not found: ${missing.joinToString(", ")}. " +
                        "Known recipes: ${recipes.joinToString(", ") { it.id }}",
                )
            }
            val body = buildJsonObject {
                put("ok", true)
                putJsonArray("intents") { found.forEach { add(recipeDetail(it, probe)) } }
                if (missing.isNotEmpty()) {
                    put("not_found", JsonArray(missing.map { JsonPrimitive(it) }))
                }
                put("hint", "Call intent.launch with recipe and its arguments.")
            }
            return CapabilityOutcome(true, body.toString())
        }

        val scored = recipes
            .map { it to score(it, query) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<IntentRecipe, Int>> { it.second }.thenBy { it.first.id })
            .take(maxResults)

        if (scored.isEmpty()) {
            return CapabilityOutcome(
                true,
                buildJsonObject {
                    put("ok", true)
                    putJsonArray("intents") {}
                    put(
                        "message",
                        "No intent recipe matched. Families: " +
                            recipes.map { it.family }.distinct().joinToString(", ") +
                            ". Use intent.search with select:<id> if you know the id.",
                    )
                }.toString(),
            )
        }

        val body = buildJsonObject {
            put("ok", true)
            putJsonArray("intents") { scored.forEach { add(recipeDetail(it.first, probe)) } }
            put("hint", "Call intent.launch with recipe and its arguments.")
        }
        return CapabilityOutcome(true, body.toString())
    }

    private fun probeRaw(
        args: JsonObject,
        action: String?,
        uri: String?,
        type: String?,
        packageName: String?,
        probe: IntentHandlerProbe,
    ): CapabilityOutcome {
        val validation = validateRaw(
            action = action,
            uri = uri,
            type = type,
            packageName = packageName,
            categories = stringList(args["categories"]),
            extras = args["extras"] as? JsonObject,
            chooserTitle = args.string("chooser_title"),
        )
        return when (validation) {
            is RawValidation.Invalid -> CapabilityOutcome(
                false,
                "",
                validation.reason,
            )

            is RawValidation.Valid -> {
                val resolved = validation.intent
                val handlers = probe.handlers(
                    resolved.action,
                    resolved.uri,
                    resolved.type,
                    resolved.packageName,
                )
                CapabilityOutcome(
                    true,
                    buildJsonObject {
                        put("ok", true)
                        put("allowed", true)
                        put("action", resolved.action)
                        resolved.uri?.let { put("uri", it) }
                        resolved.type?.let { put("type", it) }
                        resolved.packageName?.let { put("package", it) }
                        putJsonArray("handlers") { handlers.forEach { add(JsonPrimitive(it)) } }
                        put("available", handlers.isNotEmpty())
                    }.toString(),
                )
            }
        }
    }

    // ── Launch planning (pure) ────────────────────────────────────────────

    sealed interface LaunchPlan {
        data class Direct(val recipe: IntentRecipe?, val intent: ResolvedIntent) : LaunchPlan
        data class LaunchApp(val packageName: String) : LaunchPlan
    }

    sealed interface PlanResult {
        data class Ready(val plan: LaunchPlan) : PlanResult
        data class Invalid(val reason: String) : PlanResult
    }

    fun plan(args: JsonObject): PlanResult {
        val recipeId = args.string("recipe")?.trim()
        val rawAction = args.string("action")
        val rawUri = args.string("uri")

        if (recipeId.isNullOrEmpty()) {
            if (rawAction == null && rawUri == null) {
                return PlanResult.Invalid("recipe is required (or raw action/uri)")
            }
            val validation = validateRaw(
                action = rawAction,
                uri = rawUri,
                type = args.string("type"),
                packageName = args.string("package"),
                categories = stringList(args["categories"]),
                extras = args["extras"] as? JsonObject,
                chooserTitle = args.string("chooser_title"),
            )
            return when (validation) {
                is RawValidation.Invalid -> PlanResult.Invalid(validation.reason)
                is RawValidation.Valid -> PlanResult.Ready(LaunchPlan.Direct(null, validation.intent))
            }
        }

        val recipe = resolveRecipe(recipeId)
            ?: return PlanResult.Invalid(
                "unknown recipe \"$recipeId\". Known recipes: " +
                    recipes.joinToString(", ") { it.id },
            )

        return when (val plan = recipe.plan) {
            is RecipePlan.LaunchApp -> {
                val packageName = args.string(plan.packageParam)
                    ?: return PlanResult.Invalid("${plan.packageParam} is required for ${recipe.id}")
                if (!validPackageName(packageName) || isOwnPackage(packageName)) {
                    return PlanResult.Invalid("package is not allowed: $packageName")
                }
                PlanResult.Ready(LaunchPlan.LaunchApp(packageName))
            }

            is RecipePlan.Screens -> {
                val screen = args.string("screen")
                    ?: return PlanResult.Invalid("screen is required for ${recipe.id}")
                val target = plan.targets.firstOrNull { it.screen.equals(screen.trim(), ignoreCase = true) }
                    ?: return PlanResult.Invalid(
                        "unknown screen \"$screen\". Known screens: " +
                            plan.targets.joinToString(", ") { it.screen },
                    )
                val uri = target.uriPrefix?.let { prefix ->
                    val packageName = target.packageParam?.let { args.string(it) }
                        ?: return PlanResult.Invalid("${target.packageParam} is required for screen ${target.screen}")
                    if (!validPackageName(packageName)) {
                        return PlanResult.Invalid("package is not allowed: $packageName")
                    }
                    prefix + packageName
                }
                PlanResult.Ready(
                    LaunchPlan.Direct(
                        recipe,
                        ResolvedIntent(
                            action = target.action,
                            uri = uri,
                            type = null,
                            packageName = null,
                            categories = emptyList(),
                            extras = emptyMap(),
                            chooserTitle = null,
                        ),
                    ),
                )
            }

            is RecipePlan.Action -> when (val validated = validateRecipeArgs(recipe, args)) {
                is RecipeArgs.Invalid -> PlanResult.Invalid(validated.reason)
                is RecipeArgs.Valid -> {
                    val tokens = tokenValues(recipe, validated.values)
                    val variant = plan.variants.firstOrNull { candidate ->
                        candidate.requires.all { validated.values.containsKey(it) }
                    }
                    if (variant == null && plan.variants.isNotEmpty()) {
                        val needed = plan.variants.flatMap { it.requires }.distinct()
                        return PlanResult.Invalid(
                            "provide at least one of ${needed.joinToString(", ")} for ${recipe.id}",
                        )
                    }
                    val uri = variant?.let { substitute(it.template, tokens) }
                        ?.takeIf { it.isNotEmpty() }
                    val fallbackUri = plan.fallbackTemplate
                        ?.let { substitute(it, tokens) }
                        ?.takeIf { it.isNotEmpty() }
                    val extras = buildExtras(plan.extras, validated.values)
                    PlanResult.Ready(
                        LaunchPlan.Direct(
                            recipe,
                            ResolvedIntent(
                                action = plan.action,
                                uri = uri,
                                type = variant?.type ?: planType(recipe),
                                packageName = variant?.packageName,
                                categories = emptyList(),
                                extras = extras,
                                chooserTitle = plan.chooserTitle,
                                fallbackUri = fallbackUri,
                            ),
                        ),
                    )
                }
            }
        }
    }

    /** Build the availability probe for a recipe from its example arguments. */
    fun probeIntent(recipe: IntentRecipe): ResolvedIntent? {
        val args = JsonObject(
            recipe.exampleArgs.mapValues { (_, value) -> JsonPrimitive(value) } +
                mapOf("recipe" to JsonPrimitive(recipe.id)),
        )
        return when (val result = plan(args)) {
            is PlanResult.Ready -> (result.plan as? LaunchPlan.Direct)?.intent
            is PlanResult.Invalid -> null
        }
    }

    // ── Execution (Android) ───────────────────────────────────────────────

    suspend fun launch(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.Main) {
            when (val result = plan(args)) {
                is PlanResult.Invalid -> CapabilityOutcome(false, "", result.reason)
                is PlanResult.Ready -> when (val plan = result.plan) {
                    is LaunchPlan.LaunchApp -> launchPackage(context, plan.packageName)
                    is LaunchPlan.Direct -> launchResolved(context, plan.recipe, plan.intent)
                }
            }
        }

    private fun launchResolved(
        context: Context,
        recipe: IntentRecipe?,
        resolved: ResolvedIntent,
    ): CapabilityOutcome {
        val direct = toAndroidIntent(resolved)
        direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val delivered = if (resolved.chooserTitle != null) {
            Intent.createChooser(direct, resolved.chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            direct
        }

        val chosen = when {
            resolved.chooserTitle != null -> delivered
            recipe != null -> preferableIntent(context, recipe, resolved)
            else -> delivered
        }

        return try {
            context.startActivity(chosen)
            CapabilityOutcome(
                true,
                buildJsonObject {
                    put("ok", true)
                    recipe?.let { put("recipe", it.id) }
                    put("action", resolved.action)
                    resolved.uri?.let { put("uri", it) }
                    resolved.packageName?.let { put("package", it) }
                    put("delivery", "direct")
                }.toString(),
            )
        } catch (_: ActivityNotFoundException) {
            CapabilityOutcome(
                false,
                "",
                "No app on this phone can handle ${resolved.uri ?: resolved.action}.",
            )
        } catch (error: SecurityException) {
            CapabilityOutcome(false, "", "Android refused the intent: ${error.message}")
        }
    }

    private fun launchPackage(context: Context, packageName: String): CapabilityOutcome {
        val intent = try {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } catch (error: SecurityException) {
            return CapabilityOutcome(
                false,
                "",
                "Android hid the package $packageName: ${error.message}",
            )
        } ?: return CapabilityOutcome(
            false,
            "",
            "No launchable activity for $packageName. Check device.apps for installed packages.",
        )

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            CapabilityOutcome(
                true,
                buildJsonObject {
                    put("ok", true)
                    put("recipe", "app.launch")
                    put("package", packageName)
                    put("delivery", "direct")
                }.toString(),
            )
        } catch (_: ActivityNotFoundException) {
            CapabilityOutcome(false, "", "No launchable activity for $packageName.")
        } catch (error: SecurityException) {
            CapabilityOutcome(false, "", "Android refused the launch: ${error.message}")
        }
    }

    /**
     * Prefer the recipe's primary intent, but fall back to its documented URL
     * when the primary handler is missing (for example Maps not installed).
     */
    private fun preferableIntent(
        context: Context,
        recipe: IntentRecipe,
        resolved: ResolvedIntent,
    ): Intent {
        val primary = toAndroidIntent(resolved).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (recipe.plan !is RecipePlan.Action) return primary
        if (resolved.chooserTitle != null) return primary
        val pm = context.packageManager
        if (primary.resolveActivity(pm) != null) return primary
        val fallbackUri = resolved.fallbackUri ?: return primary
        return Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun toAndroidIntent(resolved: ResolvedIntent): Intent {
        val intent = Intent(resolved.action)
        resolved.uri?.takeIf { it.isNotEmpty() }?.let { intent.data = Uri.parse(it) }
        resolved.type?.takeIf { it.isNotEmpty() }?.let { intent.type = it }
        resolved.packageName?.let { intent.setPackage(it) }
        resolved.categories.forEach(intent::addCategory)
        resolved.extras.forEach { (key, value) ->
            when (value) {
                is ExtraValue.Text -> intent.putExtra(key, value.value)
                is ExtraValue.Flag -> intent.putExtra(key, value.value)
                is ExtraValue.Number -> intent.putExtra(key, value.value)
                is ExtraValue.Decimal -> intent.putExtra(key, value.value)
                is ExtraValue.TextList -> intent.putStringArrayListExtra(
                    key,
                    ArrayList(value.values),
                )
            }
        }
        return intent
    }

    /** PackageManager-backed probe used by `intent.search` in production. */
    fun probe(context: Context): IntentHandlerProbe = IntentHandlerProbe { action, uri, type, packageName ->
        val intent = Intent(action)
        uri?.takeIf { it.isNotEmpty() }?.let { intent.data = Uri.parse(it) }
        type?.takeIf { it.isNotEmpty() }?.let { intent.type = it }
        packageName?.let { intent.setPackage(it) }
        try {
            context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .mapNotNull { info ->
                    val label = info.loadLabel(context.packageManager).toString()
                        .takeIf { it.isNotBlank() }
                    val packageId = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (label != null) "$label ($packageId)" else packageId
                }
                .distinct()
                .take(10)
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    // ── Validation ────────────────────────────────────────────────────────

    private sealed interface RecipeArgs {
        data class Valid(val values: Map<String, JsonElement>) : RecipeArgs
        data class Invalid(val reason: String) : RecipeArgs
    }

    private fun validateRecipeArgs(recipe: IntentRecipe, args: JsonObject): RecipeArgs {
        val values = mutableMapOf<String, JsonElement>()
        for (param in recipe.params) {
            val element = args[param.name]
            if (element == null || element is kotlinx.serialization.json.JsonNull) {
                if (param.required) {
                    return RecipeArgs.Invalid(
                        "missing parameter \"${param.name}\" for ${recipe.id}",
                    )
                }
                param.default?.let { values[param.name] = JsonPrimitive(it) }
                continue
            }
            when (val checked = checkParam(param, element)) {
                is ParamCheck.Invalid -> return RecipeArgs.Invalid(checked.reason)
                is ParamCheck.Valid -> values[param.name] = checked.value
            }
        }
        return RecipeArgs.Valid(values)
    }

    private sealed interface ParamCheck {
        data class Valid(val value: JsonElement) : ParamCheck
        data class Invalid(val reason: String) : ParamCheck
    }

    private fun checkParam(param: IntentParam, element: JsonElement): ParamCheck {
        return when (param.type) {
            IntentParamType.STRING -> {
                val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return ParamCheck.Invalid("${param.name} must be a string")
                val limit = param.maxLength ?: 512
                if (text.length > limit) {
                    return ParamCheck.Invalid("${param.name} is longer than $limit characters")
                }
                if (text.any { it == '\u0000' }) {
                    return ParamCheck.Invalid("${param.name} contains a NUL character")
                }
                when (param.format) {
                    IntentParamFormat.TEXT -> Unit
                    IntentParamFormat.URL -> validateUrl(text)?.let {
                        return ParamCheck.Invalid("${param.name}: $it")
                    }
                    IntentParamFormat.PHONE -> if (sanitizePhone(text) == null) {
                        return ParamCheck.Invalid(
                            "${param.name} is not a dialable number (digits and +*# allowed)",
                        )
                    }
                    IntentParamFormat.EMAIL -> if (!validEmail(text)) {
                        return ParamCheck.Invalid("${param.name} is not a valid email address")
                    }
                }
                ParamCheck.Valid(JsonPrimitive(text))
            }

            IntentParamType.INTEGER -> {
                val number = (element as? JsonPrimitive)?.content?.toDoubleOrNull()
                    ?: return ParamCheck.Invalid("${param.name} must be an integer")
                if (number != number.toLong().toDouble()) {
                    return ParamCheck.Invalid("${param.name} must be an integer")
                }
                rangeError(param, number)?.let { return ParamCheck.Invalid(it) }
                ParamCheck.Valid(JsonPrimitive(number.toLong()))
            }

            IntentParamType.NUMBER -> {
                val number = (element as? JsonPrimitive)?.content?.toDoubleOrNull()
                    ?: return ParamCheck.Invalid("${param.name} must be a number")
                rangeError(param, number)?.let { return ParamCheck.Invalid(it) }
                ParamCheck.Valid(JsonPrimitive(number))
            }

            IntentParamType.BOOLEAN -> {
                val raw = (element as? JsonPrimitive)?.content?.lowercase()
                    ?: return ParamCheck.Invalid("${param.name} must be a boolean")
                when (raw) {
                    "true", "1" -> ParamCheck.Valid(JsonPrimitive(true))
                    "false", "0" -> ParamCheck.Valid(JsonPrimitive(false))
                    else -> ParamCheck.Invalid("${param.name} must be a boolean")
                }
            }

            IntentParamType.ENUM -> {
                val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return ParamCheck.Invalid("${param.name} must be a string")
                val match = param.values.firstOrNull { it.equals(text.trim(), ignoreCase = true) }
                    ?: return ParamCheck.Invalid(
                        "${param.name} must be one of ${param.values.joinToString(", ")}",
                    )
                ParamCheck.Valid(JsonPrimitive(match))
            }

            IntentParamType.ENUM_LIST -> {
                val array = element as? JsonArray
                    ?: return ParamCheck.Invalid("${param.name} must be an array")
                val selected = mutableListOf<JsonPrimitive>()
                for (item in array) {
                    val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: return ParamCheck.Invalid("${param.name} entries must be strings")
                    val match = param.values.firstOrNull { it.equals(text.trim(), ignoreCase = true) }
                        ?: return ParamCheck.Invalid(
                            "${param.name} entries must be one of ${param.values.joinToString(", ")}",
                        )
                    selected += JsonPrimitive(match)
                }
                ParamCheck.Valid(JsonArray(selected))
            }
        }
    }

    private fun rangeError(param: IntentParam, value: Double): String? {
        param.minimum?.let {
            if (value < it) return "${param.name} must be >= $it"
        }
        param.maximum?.let {
            if (value > it) return "${param.name} must be <= $it"
        }
        return null
    }

    private sealed interface RawValidation {
        data class Valid(val intent: ResolvedIntent) : RawValidation
        data class Invalid(val reason: String) : RawValidation
    }

    /**
     * Accept the short action names the model reads in `intent.launch`'s
     * schema (`VIEW`, `DIAL`, …) as well as fully-qualified actions. Without
     * this, a model that copies the schema lands on a confusing rejection and
     * has to retry with the full string.
     */
    internal fun normalizeRawAction(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return when (trimmed.uppercase()) {
            "VIEW" -> Intent.ACTION_VIEW
            "DIAL" -> Intent.ACTION_DIAL
            "SEND" -> Intent.ACTION_SEND
            "SENDTO" -> Intent.ACTION_SENDTO
            "WEB_SEARCH" -> "android.intent.action.WEB_SEARCH"
            "MEDIA_PLAY_FROM_SEARCH" -> "android.media.action.MEDIA_PLAY_FROM_SEARCH"
            "INSERT" -> Intent.ACTION_INSERT
            else -> trimmed
        }
    }

    private fun validateRaw(
        action: String?,
        uri: String?,
        type: String?,
        packageName: String?,
        categories: List<String>,
        extras: JsonObject?,
        chooserTitle: String?,
    ): RawValidation {
        val cleanAction = normalizeRawAction(action)
        val cleanUri = uri?.trim()?.takeIf { it.isNotEmpty() }

        if (cleanAction.isEmpty() && cleanUri == null) {
            return RawValidation.Invalid("raw mode needs an action or a uri")
        }

        if (cleanAction.isNotEmpty()) {
            if (cleanAction in BLOCKED_ACTIONS) {
                return RawValidation.Invalid("action is blocked: $cleanAction")
            }
            if (cleanAction !in ALLOWED_RAW_ACTIONS) {
                return RawValidation.Invalid(
                    "action is not allowed: $cleanAction. Allowed: " +
                        ALLOWED_RAW_ACTIONS.joinToString(", "),
                )
            }
        }

        if (cleanUri != null) {
            if (cleanUri.length > MAX_RAW_URI_CHARS) {
                return RawValidation.Invalid("uri is longer than $MAX_RAW_URI_CHARS characters")
            }
            val scheme = cleanUri.substringBefore(':', missingDelimiterValue = "").lowercase()
            if (scheme.isEmpty()) {
                return RawValidation.Invalid("uri needs a scheme")
            }
            if (scheme !in ALLOWED_URI_SCHEMES) {
                return RawValidation.Invalid(
                    "uri scheme is not allowed: $scheme. Allowed: " +
                        ALLOWED_URI_SCHEMES.joinToString(", "),
                )
            }
            if (cleanUri.startsWith("file:", ignoreCase = true)) {
                return RawValidation.Invalid("file: URIs are not allowed")
            }
            if (cleanUri.startsWith("content:", ignoreCase = true)) {
                return RawValidation.Invalid("content: URIs are not allowed in raw mode")
            }
            if (scheme == "tel" && cleanAction != Intent.ACTION_DIAL) {
                return RawValidation.Invalid("tel: URIs require action DIAL")
            }
            if (scheme == "google.navigation" && cleanAction != Intent.ACTION_VIEW) {
                return RawValidation.Invalid("google.navigation: URIs require action VIEW")
            }
        }

        if (packageName != null) {
            if (!validPackageName(packageName)) {
                return RawValidation.Invalid("package is not a valid package name")
            }
            if (isOwnPackage(packageName)) {
                return RawValidation.Invalid("package $packageName is not allowed")
            }
        }

        if (type != null && type.length > 120) {
            return RawValidation.Invalid("type is too long")
        }

        if (categories.size > 10) {
            return RawValidation.Invalid("too many categories")
        }
        if (categories.any { it.length > 120 }) {
            return RawValidation.Invalid("category name is too long")
        }

        if (extras != null) {
            if (extras.size > MAX_EXTRA_COUNT) {
                return RawValidation.Invalid("too many extras")
            }
            for ((key, value) in extras) {
                if (key.length > 120) return RawValidation.Invalid("extra key is too long")
                when (value) {
                    is JsonArray -> {
                        if (value.size > MAX_EXTRA_VALUES) {
                            return RawValidation.Invalid("extra $key has too many values")
                        }
                        for (item in value) {
                            val primitive = item as? JsonPrimitive
                            if (primitive == null || !primitive.isString) {
                                return RawValidation.Invalid(
                                    "extra $key entries must be strings",
                                )
                            }
                            if (primitive.content.length > MAX_EXTRA_CHARS) {
                                return RawValidation.Invalid("extra $key entry is too large")
                            }
                        }
                    }
                    is JsonPrimitive -> {
                        if (value.isString && value.content.length > MAX_EXTRA_CHARS) {
                            return RawValidation.Invalid("extra $key is too large")
                        }
                    }
                    else -> return RawValidation.Invalid(
                        "extra $key must be a string, boolean, number, or string array",
                    )
                }
            }
        }

        if (chooserTitle != null && chooserTitle.length > 120) {
            return RawValidation.Invalid("chooser_title is too long")
        }

        val actionForIntent = cleanAction.ifEmpty {
            when (cleanUri!!.substringBefore(':').lowercase()) {
                "tel" -> Intent.ACTION_DIAL
                "smsto" -> Intent.ACTION_SENDTO
                "mailto" -> Intent.ACTION_SENDTO
                else -> Intent.ACTION_VIEW
            }
        }

        val extraValues = mutableMapOf<String, ExtraValue>()
        extras?.forEach { (key, value) ->
            extraValues[key] = when (value) {
                is JsonArray -> ExtraValue.TextList(
                    value.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                )
                is JsonPrimitive -> when {
                    value.isString -> ExtraValue.Text(value.content)
                    value.content.equals("true", true) -> ExtraValue.Flag(true)
                    value.content.equals("false", true) -> ExtraValue.Flag(false)
                    value.content.toLongOrNull() != null -> ExtraValue.Number(
                        value.content.toLong(),
                    )
                    value.content.toDoubleOrNull() != null -> ExtraValue.Decimal(
                        value.content.toDouble(),
                    )
                    else -> ExtraValue.Text(value.content)
                }
                else -> ExtraValue.Text(value.toString())
            }
        }

        return RawValidation.Valid(
            ResolvedIntent(
                action = actionForIntent,
                uri = cleanUri,
                type = type,
                packageName = packageName,
                categories = categories,
                extras = extraValues,
                chooserTitle = chooserTitle,
            ),
        )
    }

    // ── URI building ──────────────────────────────────────────────────────

    private fun tokenValues(recipe: IntentRecipe, values: Map<String, JsonElement>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (param in recipe.params) {
            val element = values[param.name] ?: continue
            when (param.type) {
                IntentParamType.ENUM -> {
                    val text = element.jsonPrimitiveContent()
                    when (param.name) {
                        "mode" -> {
                            out["modeCode"] = modeCode(text)
                            out["travelmode"] = travelMode(text)
                        }
                        "travelmode" -> out["travelmode"] = text
                        else -> out[param.name] = encodeComponent(text)
                    }
                }

                IntentParamType.ENUM_LIST -> {
                    val items = (element as? JsonArray)?.mapNotNull {
                        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                    } ?: emptyList()
                    if (param.name == "avoid") {
                        out["avoidCodes"] = items.mapNotNull(::avoidCode).joinToString("")
                    } else {
                        out[param.name] = encodeComponent(items.joinToString(","))
                    }
                }

                IntentParamType.STRING -> {
                    val text = element.jsonPrimitiveContent()
                    out[param.name] = when (param.format) {
                        IntentParamFormat.TEXT -> encodeComponent(text)
                        IntentParamFormat.URL -> text
                        IntentParamFormat.PHONE -> sanitizePhone(text) ?: ""
                        IntentParamFormat.EMAIL -> text
                    }
                }

                else -> out[param.name] = element.jsonPrimitiveContent()
            }
        }
        return out
    }

    private fun tokenValuesFromStrings(args: Map<String, String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        args.forEach { (key, value) ->
            out[key] = encodeComponent(value)
            if (key == "mode") {
                out["modeCode"] = modeCode(value)
                out["travelmode"] = travelMode(value)
            }
        }
        return out
    }

    /**
     * Fill `{placeholders}` and drop query segments whose value is empty.
     *
     * Deliberately regex-free: Android's ICU-backed Pattern rejects a bare
     * `}` that the desktop JVM accepts, so the same pattern can pass unit
     * tests and fail on-device. Scanning is also easier to reason about.
     */
    private fun substitute(template: String, values: Map<String, String>): String {
        if (template.isEmpty()) return ""
        var result = template
        for ((key, value) in values) {
            if (value.isEmpty()) result = dropSegment(result, "{${key}}")
        }
        for ((key, value) in values) {
            result = result.replace("{$key}", value)
        }
        result = dropUnresolvedSegments(result)
        return result.trimEnd('?', '&')
    }

    /** Remove the `?name={key}` or `&name={key}` segment containing [token]. */
    private fun dropSegment(text: String, token: String): String {
        var result = text
        while (true) {
            val index = result.indexOf(token)
            if (index < 0) return result
            val start = result.lastIndexOfAny(charArrayOf('&', '?'), index)
            result = if (start < 0) {
                result.removeRange(index, index + token.length)
            } else {
                result.removeRange(start, index + token.length)
            }
        }
    }

    /** Remove any leftover `?name={placeholder}` or bare `{placeholder}`. */
    private fun dropUnresolvedSegments(text: String): String {
        var result = text
        while (true) {
            val open = result.indexOf('{')
            if (open < 0) return result
            val close = result.indexOf('}', open + 1)
            if (close < 0) return result.substring(0, open)
            val start = result.lastIndexOfAny(charArrayOf('&', '?'), open)
            result = if (start < 0) {
                result.removeRange(open, close + 1)
            } else {
                result.removeRange(start, close + 1)
            }
        }
    }

    private fun buildExtras(
        mappings: List<ExtraMapping>,
        values: Map<String, JsonElement>,
    ): Map<String, ExtraValue> {
        val out = mutableMapOf<String, ExtraValue>()
        for (mapping in mappings) {
            val text = values[mapping.param]?.jsonPrimitiveContent().orEmpty()
            if (text.isEmpty()) continue
            val extra = if (mapping.key == MediaStore.EXTRA_MEDIA_FOCUS) {
                mediaFocus(text)
            } else {
                text
            }
            out[mapping.key] = ExtraValue.Text(extra)
        }
        return out
    }

    /** EXTRA_MEDIA_FOCUS takes a MediaStore content type, not a plain word. */
    private fun mediaFocus(value: String): String = when (value.lowercase()) {
        "music" -> "vnd.android.cursor.dir/audio"
        "artist" -> "vnd.android.cursor.dir/artist"
        "album" -> "vnd.android.cursor.dir/album"
        "playlist" -> "vnd.android.cursor.dir/playlist"
        else -> value
    }

    private fun planType(recipe: IntentRecipe): String? = when (recipe.plan) {
        is RecipePlan.Action -> recipe.plan.variants.firstOrNull()?.type
        else -> null
    }

    // ── Formatting ────────────────────────────────────────────────────────

    private fun recipeDetail(recipe: IntentRecipe, probe: IntentHandlerProbe): JsonObject {
        val probeIntent = probeIntent(recipe)
        val handlers = probeIntent?.let {
            probe.handlers(it.action, it.uri, it.type, it.packageName)
        }.orEmpty()
        val fallback = (recipe.plan as? RecipePlan.Action)?.fallbackTemplate?.let { template ->
            val uri = substitute(template, tokenValuesFromStrings(recipe.exampleArgs))
            probe.handlers(Intent.ACTION_VIEW, uri, null, null)
        }.orEmpty()

        return buildJsonObject {
            put("id", recipe.id)
            put("title", recipe.title)
            put("family", recipe.family)
            put("summary", recipe.summary)
            put("risk", recipe.risk.wire)
            // app.launch has no single probe intent; any installed app can
            // satisfy it, so it is available wherever the package exists.
            val launchApp = recipe.plan is RecipePlan.LaunchApp
            put("available", launchApp || handlers.isNotEmpty() || fallback.isNotEmpty())
            putJsonArray("handlers") {
                (handlers + fallback).distinct().forEach { add(JsonPrimitive(it)) }
            }
            put("parameters", recipe.schema)
            putJsonObject("example") {
                put("recipe", recipe.id)
                recipe.exampleArgs.forEach { (key, value) -> put(key, value) }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    fun resolveRecipe(id: String): IntentRecipe? {
        val direct = byId[id.trim()]
        if (direct != null) return direct
        val stripped = stripNodePrefix(id.trim())
        return byId[stripped]
            ?: bySanitizedName[stripped.replace('.', '_').lowercase()]
            ?: bySanitizedName[stripped.lowercase()]
    }

    private fun stripNodePrefix(name: String): String {
        val marker = name.indexOf("__")
        return if (name.startsWith("node_") && marker > 0) name.substring(marker + 2) else name
    }

    private fun score(recipe: IntentRecipe, query: String): Int {
        val terms = query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 && it !in STOP_WORDS }
        if (terms.isEmpty()) return 0

        var total = 0
        for (term in terms) {
            var best = 0
            if (recipe.id.lowercase().split('.', '_').any { it == term }) best = maxOf(best, 6)
            if (recipe.keywords.any { it.equals(term, ignoreCase = true) }) best = maxOf(best, 10)
            if (recipe.keywords.any { it.contains(term, ignoreCase = true) }) best = maxOf(best, 4)
            if (recipe.title.contains(term, ignoreCase = true)) best = maxOf(best, 5)
            if (recipe.family.contains(term, ignoreCase = true)) best = maxOf(best, 3)
            if (recipe.summary.contains(term, ignoreCase = true)) best = maxOf(best, 2)
            if (best == 0) return 0
            total += best
        }
        return total
    }

    private fun modeCode(mode: String): String = when (mode.lowercase()) {
        "walking" -> "w"
        "bicycling" -> "b"
        "two_wheeler" -> "l"
        else -> "d"
    }

    private fun travelMode(mode: String): String = when (mode.lowercase()) {
        "walking" -> "walking"
        "bicycling" -> "bicycling"
        "transit" -> "transit"
        else -> "driving"
    }

    private fun avoidCode(value: String): String? = when (value.lowercase()) {
        "tolls" -> "t"
        "highways" -> "h"
        "ferries" -> "f"
        else -> null
    }

    private fun validateUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.length > MAX_RAW_URI_CHARS) return "url is too long"
        if (trimmed.any { it == '\u0000' || it == '\n' || it == '\r' }) return "url has control characters"
        val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme != "https" && scheme != "http") return "url must start with http:// or https://"
        if (trimmed.length <= scheme.length + 1) return "url has no host"
        return null
    }

    private fun sanitizePhone(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 40) return null
        val allowed = "0123456789+#*,;"
        if (trimmed.any { it !in allowed && it !in " -()" }) return null
        val cleaned = trimmed.filter { it in allowed }
        return cleaned.ifEmpty { null }
    }

    private fun validEmail(raw: String): Boolean {
        val text = raw.trim()
        if (text.length !in 3..320) return false
        if (text.count { it == '@' } != 1) return false
        val (local, domain) = text.split('@')
        if (local.isEmpty() || domain.isEmpty() || '.' !in domain) return false
        return text.all {
            it.isLetterOrDigit() || it in "._%+-@"
        }
    }

    private fun validPackageName(name: String): Boolean {
        if (name.length > 200) return false
        val parts = name.split('.')
        if (parts.size < 2) return false
        return parts.all { part ->
            part.isNotEmpty() &&
                part.first().isJavaIdentifierStart() &&
                part.all { it.isJavaIdentifierPart() }
        }
    }

    private fun isOwnPackage(name: String): Boolean =
        name == OWN_PACKAGE || name.startsWith("$OWN_PACKAGE.")

    private fun stringList(element: JsonElement?): List<String> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }

    private fun JsonElement.jsonPrimitiveContent(): String =
        (this as? JsonPrimitive)?.content.orEmpty()


    private val HEX = "0123456789ABCDEF".toCharArray()

    /**
     * RFC 3986 percent-encoding for URI components. `+` is not used for spaces,
     * so query values survive apps that decode with `Uri.decode` only.
     */
    internal fun encodeComponent(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '-' || char == '.' || char == '_' || char == '~'
            ) {
                out.append(char)
            } else {
                out.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
            }
        }
        return out.toString()
    }

    private val STOP_WORDS = setOf(
        "a", "an", "the", "to", "for", "on", "in", "of", "my", "me", "it",
        "please", "can", "you", "and", "or", "with", "this", "that", "open",
        "send", "go",
    )
}

private fun intentSchema(params: List<IntentParam>): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        params.forEach { param ->
            putJsonObject(param.name) {
                put("type", schemaType(param))
                if (param.description.isNotBlank()) put("description", param.description)
                if (param.type == IntentParamType.ENUM) {
                    putJsonArray("enum") {
                        param.values.forEach { add(JsonPrimitive(it)) }
                    }
                }
                if (param.type == IntentParamType.ENUM_LIST) {
                    putJsonObject("items") {
                        put("type", "string")
                        putJsonArray("enum") {
                            param.values.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                }
                param.minimum?.let { put("minimum", it) }
                param.maximum?.let { put("maximum", it) }
                if (param.type == IntentParamType.STRING) {
                    param.maxLength?.let { put("maxLength", it) }
                }
                param.default?.let { put("default", defaultElement(param, it)) }
            }
        }
    }
    val required = params.filter { it.required }.map { it.name }
    if (required.isNotEmpty()) {
        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }
}

private fun schemaType(param: IntentParam): String = when (param.type) {
    IntentParamType.STRING, IntentParamType.ENUM -> "string"
    IntentParamType.INTEGER -> "integer"
    IntentParamType.NUMBER -> "number"
    IntentParamType.BOOLEAN -> "boolean"
    IntentParamType.ENUM_LIST -> "array"
}

private fun defaultElement(param: IntentParam, value: String): JsonElement = when (param.type) {
    IntentParamType.INTEGER -> JsonPrimitive(value.toLongOrNull() ?: 0L)
    IntentParamType.NUMBER -> JsonPrimitive(value.toDoubleOrNull() ?: 0.0)
    IntentParamType.BOOLEAN -> JsonPrimitive(value.equals("true", ignoreCase = true))
    else -> JsonPrimitive(value)
}
