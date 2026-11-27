package ai.zeroclaw.app.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deferred-schema discovery. The `<functions>` envelope is the same contract
 * `crates/zeroclaw-tools/src/tool_search.rs` returns to the model.
 */
class PhoneToolSearchTest {

    private fun search(query: String): CapabilityOutcome =
        PhoneToolSearch.execute(JsonObject(mapOf("query" to JsonPrimitive(query))))

    @Test
    fun `select returns full parameter schemas`() {
        val outcome = search("select:alarm.set,timer.set")

        assertTrue(outcome.success)
        assertTrue(outcome.output.startsWith("<functions>\n"))
        assertTrue(outcome.output.trimEnd().endsWith("</functions>"))
        assertTrue(outcome.output.contains("\"name\": \"alarm.set\""))
        assertTrue(outcome.output.contains("\"name\": \"timer.set\""))
        // The full schema, not the compact open object.
        assertTrue(outcome.output.contains("\"hour\""))
        assertTrue(outcome.output.contains("\"required\":[\"hour\",\"minute\"]"))
        assertTrue(outcome.output.contains("\"required\":[\"seconds\"]"))
    }

    @Test
    fun `select resolves sanitized and gateway-prefixed names`() {
        val sanitized = search("select:alarm_set")
        assertTrue(sanitized.success)
        assertTrue(sanitized.output.contains("\"name\": \"alarm.set\""))

        val prefixed = search("select:node_android-1a2b3c4d__alarm_set")
        assertTrue(prefixed.success)
        assertTrue(prefixed.output.contains("\"name\": \"alarm.set\""))
    }

    @Test
    fun `select resolves a nested capability name`() {
        val outcome = search("select:mobile_ui_observe")
        assertTrue(outcome.success)
        assertTrue(outcome.output.contains("\"name\": \"mobile.ui.observe\""))
    }

    @Test
    fun `keyword search ranks phone tools`() {
        val outcome = search("alarm")

        assertTrue(outcome.success)
        assertTrue(outcome.output.contains("\"name\": \"alarm.set\""))
        assertTrue(outcome.output.contains("\"name\": \"alarm.list\""))
    }

    @Test
    fun `keyword search returns intent recipes and the launch schema`() {
        val outcome = search("navigate maps")

        assertTrue(outcome.success)
        assertTrue(outcome.output.contains("<intent-recipes>"))
        assertTrue(outcome.output.contains("\"id\":\"maps.navigate\""))
        assertTrue(outcome.output.contains("\"name\": \"intent.launch\""))
        assertTrue(outcome.output.contains("\"destination\""))
    }

    @Test
    fun `keyword search finds accessibility tools`() {
        val outcome = search("accessibility")
        assertTrue(outcome.success)
        assertTrue(outcome.output.contains("mobile.ui.observe"))
    }

    @Test
    fun `empty query fails`() {
        val outcome = PhoneToolSearch.execute(JsonObject(emptyMap()))
        assertFalse(outcome.success)
        assertEquals("query is required", outcome.error)
    }

    @Test
    fun `unknown select reports the missing name`() {
        val outcome = search("select:nonexistent")
        assertFalse(outcome.success)
        assertTrue(outcome.error!!.contains("Not found: nonexistent"))
    }

    @Test
    fun `select of an intent recipe returns the recipe block`() {
        val outcome = search("select:maps.navigate")

        assertTrue(outcome.success)
        assertTrue(outcome.output.contains("\"name\": \"intent.launch\""))
        assertTrue(outcome.output.contains("\"id\":\"maps.navigate\""))
    }

    @Test
    fun `parameterized capabilities advertise a compact schema`() {
        val compact = DeviceCapabilities.definitions.single {
            it.name == DeviceCapabilities.ALARM_SET
        }
        assertEquals(
            "object",
            (compact.parameters["type"] as JsonPrimitive).content,
        )
        assertFalse(compact.parameters.containsKey("properties"))
        assertTrue(compact.description.contains("tool.search(query=\"select:alarm.set\")"))

        val full = DeviceCapabilities.fullDefinitions.single {
            it.name == DeviceCapabilities.ALARM_SET
        }
        assertTrue(full.parameters.containsKey("properties"))
    }

    @Test
    fun `zero argument capabilities keep their schema`() {
        val info = DeviceCapabilities.definitions.single {
            it.name == DeviceCapabilities.DEVICE_INFO
        }
        assertEquals(info.name, DeviceCapabilities.fullDefinitions.single { it.name == info.name }.name)
        assertEquals(
            DeviceCapabilities.fullDefinitions.single { it.name == info.name }.description,
            info.description,
        )
    }

    @Test
    fun `registration payload shrinks for deferred capabilities`() {
        val serializer = ListSerializer(NodeCapabilityDef.serializer())
        val full = zcJson.encodeToString(serializer, DeviceCapabilities.fullDefinitions)
        val compact = zcJson.encodeToString(serializer, DeviceCapabilities.definitions)

        val eager = DeviceCapabilities.fullDefinitions
            .filter(PhoneToolSearch::isEager)
            .map { it.name }
            .toSet()
        val fullDeferred = zcJson.encodeToString(
            serializer,
            DeviceCapabilities.fullDefinitions.filter { it.name !in eager },
        )
        val compactDeferred = zcJson.encodeToString(
            serializer,
            DeviceCapabilities.definitions.filter { it.name !in eager },
        )

        println(
            "overall full=${full.length} compact=${compact.length} " +
                "(${100 - compact.length * 100 / full.length}% smaller); " +
                "deferred full=${fullDeferred.length} compact=${compactDeferred.length} " +
                "(${100 - compactDeferred.length * 100 / fullDeferred.length}% smaller)",
        )
        // The whole payload also carries the discovery tools and the cheap
        // zero-argument capabilities, so the overall win is smaller than the
        // deferred subset's.
        assertTrue(
            "expected a meaningful overall reduction, full=${full.length} compact=${compact.length}",
            compact.length < full.length * 3 / 4,
        )
        assertTrue(
            "expected deferred capabilities to roughly halve, " +
                "full=${fullDeferred.length} compact=${compactDeferred.length}",
            compactDeferred.length < fullDeferred.length * 11 / 20,
        )
    }

    @Test
    fun `capability count stays inside the gateway limit`() {
        assertTrue(DeviceCapabilities.definitions.size <= 64)
        assertEquals(
            DeviceCapabilities.fullDefinitions.size,
            DeviceCapabilities.definitions.size,
        )
    }
}
