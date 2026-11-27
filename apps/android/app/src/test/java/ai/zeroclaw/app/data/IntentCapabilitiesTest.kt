package ai.zeroclaw.app.data

import android.provider.MediaStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Intent recipe construction and the allowlists that keep raw intents from
 * becoming a general-purpose "run anything" tool. All of this is pure JVM.
 */
class IntentCapabilitiesTest {

    private fun recipeArgs(
        recipe: String,
        vararg pairs: Pair<String, JsonElement>,
    ): JsonObject = JsonObject(
        mapOf("recipe" to JsonPrimitive(recipe)) + pairs.toMap(),
    )

    private fun resolved(result: IntentCapabilities.PlanResult): ResolvedIntent {
        val ready = result as? IntentCapabilities.PlanResult.Ready
            ?: error("expected Ready, got $result")
        val plan = ready.plan as? IntentCapabilities.LaunchPlan.Direct
            ?: error("expected Direct, got ${ready.plan}")
        return plan.intent
    }

    private fun failure(result: IntentCapabilities.PlanResult): String {
        val invalid = result as? IntentCapabilities.PlanResult.Invalid
            ?: error("expected Invalid, got $result")
        return invalid.reason
    }

    // ── Recipe construction ───────────────────────────────────────────────

    @Test
    fun `maps navigate builds the google navigation uri`() {
        val intent = resolved(
            IntentCapabilities.plan(
                recipeArgs("maps.navigate", "destination" to JsonPrimitive("CN Tower, Toronto")),
            ),
        )

        assertEquals("android.intent.action.VIEW", intent.action)
        assertEquals("com.google.android.apps.maps", intent.packageName)
        assertEquals(
            "google.navigation:q=CN%20Tower%2C%20Toronto&mode=d",
            intent.uri,
        )
        val fallback = requireNotNull(intent.fallbackUri)
        assertTrue(fallback.startsWith("https://www.google.com/maps/dir/?api=1"))
    }

    @Test
    fun `maps navigate encodes ampersands and maps mode and avoid`() {
        val intent = resolved(
            IntentCapabilities.plan(
                recipeArgs(
                    "maps.navigate",
                    "destination" to JsonPrimitive("A&B Cafe"),
                    "mode" to JsonPrimitive("walking"),
                    "avoid" to buildJsonArray {
                        add(JsonPrimitive("tolls"))
                        add(JsonPrimitive("ferries"))
                    },
                ),
            ),
        )

        assertEquals("google.navigation:q=A%26B%20Cafe&mode=w&avoid=tf", intent.uri)
    }

    @Test
    fun `maps search supports a query or coordinates`() {
        val byQuery = resolved(
            IntentCapabilities.plan(recipeArgs("maps.search", "query" to JsonPrimitive("coffee"))),
        )
        assertEquals("geo:0,0?q=coffee", byQuery.uri)

        val byPoint = resolved(
            IntentCapabilities.plan(
                recipeArgs(
                    "maps.search",
                    "lat" to JsonPrimitive(48.85),
                    "lng" to JsonPrimitive(2.35),
                    "zoom" to JsonPrimitive(12),
                ),
            ),
        )
        assertEquals("geo:48.85,2.35?z=12", byPoint.uri)
    }

    @Test
    fun `maps search needs a query or coordinates`() {
        assertTrue(
            failure(IntentCapabilities.plan(recipeArgs("maps.search")))
                .contains("provide at least one"),
        )
    }

    @Test
    fun `maps directions includes an origin when supplied`() {
        val intent = resolved(
            IntentCapabilities.plan(
                recipeArgs(
                    "maps.directions",
                    "destination" to JsonPrimitive("CN Tower"),
                    "origin" to JsonPrimitive("Union Station"),
                ),
            ),
        )
        val uri = requireNotNull(intent.uri)
        assertTrue(uri.contains("origin=Union%20Station"))
        assertTrue(uri.contains("destination=CN%20Tower"))
        assertTrue(uri.contains("travelmode=driving"))
    }

    @Test
    fun `phone dial uses ACTION_DIAL and keeps the plus sign`() {
        val intent = resolved(
            IntentCapabilities.plan(recipeArgs("phone.dial", "number" to JsonPrimitive("+1 (555) 010-0"))),
        )

        assertEquals("android.intent.action.DIAL", intent.action)
        assertEquals("tel:+15550100", intent.uri)
        assertNull(intent.packageName)
    }

    @Test
    fun `phone dial rejects letters`() {
        assertTrue(
            failure(
                IntentCapabilities.plan(recipeArgs("phone.dial", "number" to JsonPrimitive("call-mom"))),
            ).contains("not a dialable number"),
        )
    }

    @Test
    fun `sms compose fills the recipient and body`() {
        val intent = resolved(
            IntentCapabilities.plan(
                recipeArgs(
                    "sms.compose",
                    "to" to JsonPrimitive("+15550100"),
                    "body" to JsonPrimitive("On my way"),
                ),
            ),
        )

        assertEquals("android.intent.action.SENDTO", intent.action)
        assertEquals("smsto:+15550100", intent.uri)
        assertEquals("On my way", (intent.extras["sms_body"] as ExtraValue.Text).value)
    }

    @Test
    fun `email compose keeps the address readable and adds extras`() {
        val intent = resolved(
            IntentCapabilities.plan(
                recipeArgs(
                    "email.compose",
                    "to" to JsonPrimitive("person@example.com"),
                    "subject" to JsonPrimitive("Hello"),
                    "body" to JsonPrimitive("Hi there"),
                ),
            ),
        )

        assertEquals("mailto:person@example.com", intent.uri)
        assertEquals("Hello", (intent.extras["android.intent.extra.SUBJECT"] as ExtraValue.Text).value)
        assertEquals("Hi there", (intent.extras["android.intent.extra.TEXT"] as ExtraValue.Text).value)
    }

    @Test
    fun `email compose rejects a malformed address`() {
        assertTrue(
            failure(
                IntentCapabilities.plan(recipeArgs("email.compose", "to" to JsonPrimitive("nope"))),
            ).contains("not a valid email"),
        )
    }

    @Test
    fun `settings open maps the screen name to its action`() {
        val wifi = resolved(
            IntentCapabilities.plan(recipeArgs("settings.open", "screen" to JsonPrimitive("wifi"))),
        )
        assertEquals("android.settings.WIFI_SETTINGS", wifi.action)
        assertNull(wifi.uri)

        val appDetails = resolved(
            IntentCapabilities.plan(
                recipeArgs(
                    "settings.open",
                    "screen" to JsonPrimitive("app_details"),
                    "package" to JsonPrimitive("com.android.settings"),
                ),
            ),
        )
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", appDetails.action)
        assertEquals("package:com.android.settings", appDetails.uri)
    }

    @Test
    fun `app launch validates the package and refuses our own`() {
        val result = IntentCapabilities.plan(
            recipeArgs("app.launch", "package" to JsonPrimitive("com.android.settings")),
        )
        val ready = result as IntentCapabilities.PlanResult.Ready
        assertEquals(
            "com.android.settings",
            (ready.plan as IntentCapabilities.LaunchPlan.LaunchApp).packageName,
        )

        assertTrue(
            failure(
                IntentCapabilities.plan(recipeArgs("app.launch", "package" to JsonPrimitive("ai.zeroclaw.app"))),
            ).contains("not allowed"),
        )
    }

    @Test
    fun `share text uses ACTION_SEND with a chooser`() {
        val intent = resolved(
            IntentCapabilities.plan(recipeArgs("share.text", "text" to JsonPrimitive("hello"))),
        )

        assertEquals("android.intent.action.SEND", intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("Share via", intent.chooserTitle)
        assertNull(intent.uri)
        assertEquals("hello", (intent.extras["android.intent.extra.TEXT"] as ExtraValue.Text).value)
    }

    @Test
    fun `media play search fills the search extra`() {
        val intent = resolved(
            IntentCapabilities.plan(recipeArgs("media.play_search", "query" to JsonPrimitive("jazz"))),
        )

        assertEquals("android.media.action.MEDIA_PLAY_FROM_SEARCH", intent.action)
        assertEquals("jazz", (intent.extras["query"] as ExtraValue.Text).value)
    }

    @Test
    fun `web open accepts only http and https`() {
        val ok = resolved(
            IntentCapabilities.plan(recipeArgs("web.open", "url" to JsonPrimitive("https://example.com/a"))),
        )
        assertEquals("https://example.com/a", ok.uri)

        assertTrue(
            failure(
                IntentCapabilities.plan(recipeArgs("web.open", "url" to JsonPrimitive("javascript:alert(1)"))),
            ).contains("http"),
        )
    }

    @Test
    fun `unknown recipes and parameters are rejected`() {
        assertTrue(
            failure(IntentCapabilities.plan(recipeArgs("maps.route", "destination" to JsonPrimitive("x"))))
                .startsWith("unknown recipe"),
        )
        assertTrue(
            failure(IntentCapabilities.plan(recipeArgs("maps.navigate")))
                .contains("missing parameter"),
        )
        assertTrue(
            failure(
                IntentCapabilities.plan(
                    recipeArgs("maps.navigate", "destination" to JsonPrimitive("x"), "mode" to JsonPrimitive("flying")),
                ),
            ).contains("must be one of"),
        )
    }

    // ── Raw intent allowlists ─────────────────────────────────────────────

    private fun raw(vararg pairs: Pair<String, JsonElement>): JsonObject =
        JsonObject(pairs.toMap())

    @Test
    fun `raw mode blocks privileged actions`() {
        listOf(
            "android.intent.action.CALL",
            "android.intent.action.INSTALL_PACKAGE",
            "android.intent.action.UNINSTALL_PACKAGE",
            "android.intent.action.DELETE",
            "android.intent.action.REBOOT",
        ).forEach { action ->
            val reason = failure(IntentCapabilities.plan(raw("action" to JsonPrimitive(action))))
            assertTrue("action $action should be blocked, got: $reason", reason.contains("blocked"))
        }
    }

    @Test
    fun `raw mode blocks file and content uris`() {
        assertTrue(
            failure(
                IntentCapabilities.plan(
                    raw(
                        "action" to JsonPrimitive("android.intent.action.VIEW"),
                        "uri" to JsonPrimitive("file:///etc/passwd"),
                    ),
                ),
            ).contains("file"),
        )
        assertTrue(
            failure(
                IntentCapabilities.plan(
                    raw(
                        "action" to JsonPrimitive("android.intent.action.VIEW"),
                        "uri" to JsonPrimitive("content://ai.zeroclaw.app/files/x"),
                    ),
                ),
            ).contains("content"),
        )
    }

    @Test
    fun `raw mode refuses our own package`() {
        assertTrue(
            failure(
                IntentCapabilities.plan(
                    raw(
                        "action" to JsonPrimitive("android.intent.action.VIEW"),
                        "uri" to JsonPrimitive("https://example.com"),
                        "package" to JsonPrimitive("ai.zeroclaw.app"),
                    ),
                ),
            ).contains("not allowed"),
        )
    }

    @Test
    fun `raw mode allows the documented schemes`() {
        val nav = resolved(
            IntentCapabilities.plan(
                raw(
                    "action" to JsonPrimitive("android.intent.action.VIEW"),
                    "uri" to JsonPrimitive("google.navigation:q=CN%20Tower&mode=d"),
                ),
            ),
        )
        assertEquals("google.navigation:q=CN%20Tower&mode=d", nav.uri)

        val geo = resolved(
            IntentCapabilities.plan(
                raw(
                    "action" to JsonPrimitive("android.intent.action.VIEW"),
                    "uri" to JsonPrimitive("geo:0,0?q=coffee"),
                ),
            ),
        )
        assertEquals("geo:0,0?q=coffee", geo.uri)

        val https = resolved(
            IntentCapabilities.plan(
                raw(
                    "action" to JsonPrimitive("android.intent.action.VIEW"),
                    "uri" to JsonPrimitive("https://example.com"),
                ),
            ),
        )
        assertEquals("https://example.com", https.uri)
    }

    @Test
    fun `raw tel requires the dialer`() {
        val dial = resolved(
            IntentCapabilities.plan(
                raw(
                    "action" to JsonPrimitive("android.intent.action.DIAL"),
                    "uri" to JsonPrimitive("tel:+15550100"),
                ),
            ),
        )
        assertEquals("tel:+15550100", dial.uri)

        assertTrue(
            failure(
                IntentCapabilities.plan(
                    raw(
                        "action" to JsonPrimitive("android.intent.action.SEND"),
                        "uri" to JsonPrimitive("tel:+15550100"),
                    ),
                ),
            ).contains("require action DIAL"),
        )
    }

    @Test
    fun `raw mode accepts the short action names from the schema`() {
        val view = resolved(
            IntentCapabilities.plan(
                raw(
                    "action" to JsonPrimitive("VIEW"),
                    "uri" to JsonPrimitive("geo:0,0?q=Eiffel%20Tower"),
                ),
            ),
        )
        assertEquals("android.intent.action.VIEW", view.action)

        val dial = resolved(
            IntentCapabilities.plan(
                raw(
                    "action" to JsonPrimitive("DIAL"),
                    "uri" to JsonPrimitive("tel:+15550100"),
                ),
            ),
        )
        assertEquals("android.intent.action.DIAL", dial.action)
    }

    @Test
    fun `raw mode types extras and accepts string arrays`() {
        val intent = resolved(
            IntentCapabilities.plan(
                JsonObject(
                    mapOf(
                        "action" to JsonPrimitive("VIEW"),
                        "uri" to JsonPrimitive("https://example.com"),
                        "extras" to buildJsonObject {
                            put("score", 1.5)
                            put("flag", true)
                            put("count", 3)
                            putJsonArray("labels") {
                                add(JsonPrimitive("a"))
                                add(JsonPrimitive("b"))
                            }
                        },
                    ),
                ),
            ),
        )

        assertEquals(1.5, (intent.extras["score"] as ExtraValue.Decimal).value, 0.0)
        assertTrue((intent.extras["flag"] as ExtraValue.Flag).value)
        assertEquals(3L, (intent.extras["count"] as ExtraValue.Number).value)
        assertEquals(
            listOf("a", "b"),
            (intent.extras["labels"] as ExtraValue.TextList).values,
        )
    }

    @Test
    fun `media focus maps to a MediaStore content type`() {
        val intent = resolved(
            IntentCapabilities.plan(
                recipeArgs(
                    "media.play_search",
                    "query" to JsonPrimitive("jazz"),
                    "focus" to JsonPrimitive("artist"),
                ),
            ),
        )

        val focus = (intent.extras[MediaStore.EXTRA_MEDIA_FOCUS] as ExtraValue.Text).value
        assertEquals("vnd.android.cursor.dir/artist", focus)
    }

    @Test
    fun `raw mode never carries grant flags`() {
        // Flags are not part of the raw schema; ResolvedIntent has no flags field.
        val intent = resolved(
            IntentCapabilities.plan(
                raw(
                    "action" to JsonPrimitive("android.intent.action.VIEW"),
                    "uri" to JsonPrimitive("https://example.com"),
                ),
            ),
        )
        assertFalse(intent.uri!!.contains("FLAG_GRANT"))
    }

    // ── Search ────────────────────────────────────────────────────────────

    @Test
    fun `search returns ranked compact results with parameters`() {
        val outcome = IntentCapabilities.search(
            JsonObject(mapOf("query" to JsonPrimitive("navigate to a place"))),
        )
        assertTrue(outcome.success)
        assertTrue(outcome.output.contains("\"maps.navigate\""))
        assertTrue(outcome.output.contains("\"parameters\""))
        assertTrue(outcome.output.contains("\"example\""))
        assertTrue(outcome.output.contains("\"risk\":\"low\""))
    }

    @Test
    fun `search select returns one schema`() {
        val outcome = IntentCapabilities.search(
            JsonObject(mapOf("query" to JsonPrimitive("select:maps.navigate"))),
        )
        assertTrue(outcome.success)
        assertTrue(outcome.output.contains("\"id\":\"maps.navigate\""))
        assertTrue(outcome.output.contains("\"destination\""))
        assertFalse(outcome.output.contains("\"id\":\"phone.dial\""))
    }

    @Test
    fun `search reports unknown selects`() {
        val outcome = IntentCapabilities.search(
            JsonObject(mapOf("query" to JsonPrimitive("select:does.not.exist"))),
        )
        assertFalse(outcome.success)
        assertTrue(outcome.error!!.contains("Not found: does.not.exist"))
    }

    @Test
    fun `search requires a query`() {
        val outcome = IntentCapabilities.search(JsonObject(emptyMap()))
        assertFalse(outcome.success)
        assertEquals("query is required", outcome.error)
    }

    @Test
    fun `search probes a raw candidate`() {
        val outcome = IntentCapabilities.search(
            JsonObject(
                mapOf(
                    "query" to JsonPrimitive("probe"),
                    "action" to JsonPrimitive("android.intent.action.VIEW"),
                    "uri" to JsonPrimitive("geo:0,0?q=coffee"),
                ),
            ),
            IntentHandlerProbe { _, _, _, _ -> listOf("Google Maps (com.google.android.apps.maps)") },
        )
        assertTrue(outcome.success)
        assertTrue(outcome.output.contains("\"available\":true"))
        assertTrue(outcome.output.contains("Google Maps"))
    }

    @Test
    fun `every recipe builds a probe intent`() {
        assertEquals(12, IntentCapabilities.recipes.size)
        val ids = IntentCapabilities.recipes.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        IntentCapabilities.recipes.forEach { recipe ->
            if (recipe.plan is RecipePlan.LaunchApp) return@forEach
            requireNotNull(IntentCapabilities.probeIntent(recipe)) {
                "recipe ${recipe.id} does not build a probe intent"
            }
        }
    }

    @Test
    fun `substitution keeps url query parameters`() {
        val intent = resolved(
            IntentCapabilities.plan(
                recipeArgs(
                    "web.open",
                    "url" to JsonPrimitive("https://example.com/search?q=zero&lang=en"),
                ),
            ),
        )
        assertEquals("https://example.com/search?q=zero&lang=en", intent.uri)
    }

    @Test
    fun `optional query segments disappear when unset`() {
        val intent = resolved(
            IntentCapabilities.plan(
                recipeArgs("maps.navigate", "destination" to JsonPrimitive("Louvre")),
            ),
        )
        assertEquals("google.navigation:q=Louvre&mode=d", intent.uri)
    }

    @Test
    fun `encoding never emits a plus for spaces`() {        assertEquals("a%20b", IntentCapabilities.encodeComponent("a b"))
        assertEquals("caf%C3%A9", IntentCapabilities.encodeComponent("café"))
        assertEquals("a%26b%3Dc", IntentCapabilities.encodeComponent("a&b=c"))
    }
}
