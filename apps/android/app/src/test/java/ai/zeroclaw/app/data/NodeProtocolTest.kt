package ai.zeroclaw.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The node frames are a wire contract with `crates/zeroclaw-gateway/src/nodes.rs`
 * (serde `tag = "type", rename_all = "snake_case"`). A rename here silently
 * breaks device capabilities, so pin the shapes.
 */
class NodeProtocolTest {

    @Test
    fun `register frame matches the gateway contract`() {
        val frame = RegisterFrame(
            nodeId = "android-1a2b3c4d",
            capabilities = DeviceCapabilities.definitions,
        )
        val json = zcJson.parseToJsonElement(
            zcJson.encodeToString(RegisterFrame.serializer(), frame),
        ) as JsonObject

        assertEquals("register", json["type"]?.jsonPrimitive?.content)
        assertEquals("android-1a2b3c4d", json["node_id"]?.jsonPrimitive?.content)

        val capabilities = json["capabilities"] as JsonArray
        assertEquals(DeviceCapabilities.definitions.size, capabilities.size)
        val first = capabilities[0] as JsonObject
        assertTrue(first["name"] != null)
        assertTrue(first["description"] != null)
        assertTrue(first["parameters"] is JsonObject)
        assertEquals("alarm.set", first["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `result frame matches the gateway contract`() {
        val frame = InvokeResultFrame(
            callId = "call-1",
            success = true,
            output = "Alarm set for 07:30 on this phone.",
        )
        val json = zcJson.parseToJsonElement(
            zcJson.encodeToString(InvokeResultFrame.serializer(), frame),
        ) as JsonObject

        assertEquals("result", json["type"]?.jsonPrimitive?.content)
        assertEquals("call-1", json["call_id"]?.jsonPrimitive?.content)
        assertEquals("true", json["success"]?.jsonPrimitive?.content)
        assertEquals("Alarm set for 07:30 on this phone.", json["output"]?.jsonPrimitive?.content)
        assertEquals(0, (json["attachments"] as JsonArray).size)
    }

    @Test
    fun `image attachments use the gateway field names`() {
        val frame = InvokeResultFrame(
            callId = "call-2",
            success = true,
            output = "Attached image 7.",
            attachments = listOf(
                NodeAttachment(
                    kind = "image",
                    mime = "image/jpeg",
                    dataB64 = "aGVsbG8=",
                ),
            ),
        )
        val json = zcJson.parseToJsonElement(
            zcJson.encodeToString(InvokeResultFrame.serializer(), frame),
        ) as JsonObject
        val attachment = (json["attachments"] as JsonArray)[0] as JsonObject

        assertEquals("image", attachment["kind"]?.jsonPrimitive?.content)
        assertEquals("image/jpeg", attachment["mime"]?.jsonPrimitive?.content)
        assertEquals("aGVsbG8=", attachment["data_b64"]?.jsonPrimitive?.content)
    }

    @Test
    fun `file paths cannot escape the granted folder`() {
        assertEquals(listOf("notes", "a.txt"), FileCapabilities.normalize("notes/a.txt"))
        assertEquals(listOf("notes", "a.txt"), FileCapabilities.normalize("/notes//a.txt/"))
        assertEquals(null, FileCapabilities.normalize("../secret"))
        assertEquals(null, FileCapabilities.normalize("notes/../../secret"))
        assertEquals(null, FileCapabilities.normalize("./notes"))
    }

    @Test
    fun `capability names are unique capability style names`() {
        val names = DeviceCapabilities.definitions.map { it.name }
        assertEquals(names.size, names.toSet().size)
        names.forEach { name ->
            assertTrue("capability name '$name'", Regex("^[a-z]+\\.[a-z]+$").matches(name))
        }
        assertTrue(names.contains(DeviceCapabilities.ALARM_SET))
        assertTrue(names.contains(DeviceCapabilities.TIMER_SET))
    }

    @Test
    fun `alarm schema requires hour and minute`() {
        val alarm = DeviceCapabilities.definitions.single { it.name == DeviceCapabilities.ALARM_SET }
        val required = alarm.parameters["required"] as JsonArray
        val fields = required.map { it.jsonPrimitive.content }
        assertTrue(fields.contains("hour"))
        assertTrue(fields.contains("minute"))

        val properties = alarm.parameters["properties"] as JsonObject
        assertEquals("integer", (properties["hour"] as JsonObject)["type"]?.jsonPrimitive?.content)
        assertEquals("integer", (properties["minute"] as JsonObject)["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `timer schema requires seconds`() {
        val timer = DeviceCapabilities.definitions.single { it.name == DeviceCapabilities.TIMER_SET }
        val required = timer.parameters["required"] as JsonArray
        assertEquals(listOf("seconds"), required.map { it.jsonPrimitive.content })
    }

    @Test
    fun `default node id is short and provider safe`() {
        // Mirrors SettingsStore.ensureDeviceId's shape: the gateway turns this
        // into a tool name, so it must survive [a-zA-Z0-9_-]{1,64}.
        val id = "android-" + "0123abcd"
        assertTrue(Regex("^[a-z0-9-]+$").matches(id))
        assertTrue(("node_${id}__alarm_set").length <= 64)
    }
}
