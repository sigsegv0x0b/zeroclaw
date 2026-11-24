package ai.zeroclaw.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElicitationTest {
    private fun schema(raw: String): JsonObject =
        Json.parseToJsonElement(raw) as JsonObject

    @Test
    fun parsesSingleChoiceWithTypedAnswer() {
        val prompt = parseElicitationPrompt(
            requestId = "req-1",
            message = "Which color?",
            schema = schema(
                """
                {
                  "type": "object",
                  "properties": {
                    "choice": {
                      "type": "string",
                      "oneOf": [
                        {"const": "choice-0", "title": "Red"},
                        {"const": "choice-1", "title": "Green"}
                      ]
                    },
                    "custom": {"type": "string", "title": "Type your own"}
                  }
                }
                """.trimIndent(),
            ),
            timeoutSecs = 60,
        )

        assertNotNull(prompt)
        requireNotNull(prompt)
        assertEquals("req-1", prompt.requestId)
        assertEquals("Which color?", prompt.message)
        assertEquals("choice", prompt.choiceProperty)
        assertEquals(listOf("choice-0", "choice-1"), prompt.options.map { it.value })
        assertEquals(listOf("Red", "Green"), prompt.options.map { it.label })
        assertFalse(prompt.multiSelect)
        assertEquals("custom", prompt.freeFormProperty)
        assertFalse(prompt.freeFormRequired)
        assertEquals(60, prompt.timeoutSecs)
    }

    @Test
    fun parsesMultiChoiceWithTypedAnswer() {
        val prompt = parseElicitationPrompt(
            requestId = "req-2",
            message = "Which services?",
            schema = schema(
                """
                {
                  "type": "object",
                  "properties": {
                    "choices": {
                      "type": "array",
                      "minItems": 0,
                      "maxItems": 3,
                      "items": {
                        "anyOf": [
                          {"const": "choice-0", "title": "Camera"},
                          {"const": "choice-1", "title": "SMS"}
                        ]
                      }
                    },
                    "custom": {"type": "string", "title": "Type your own"}
                  }
                }
                """.trimIndent(),
            ),
            timeoutSecs = null,
        )

        requireNotNull(prompt)
        assertEquals("choices", prompt.choiceProperty)
        assertTrue(prompt.multiSelect)
        assertEquals(listOf("Camera", "SMS"), prompt.options.map { it.label })
        assertEquals("custom", prompt.freeFormProperty)
    }

    @Test
    fun parsesFreeFormOnlySchema() {
        val prompt = parseElicitationPrompt(
            requestId = "req-3",
            message = "What name?",
            schema = schema(
                """
                {
                  "type": "object",
                  "properties": {
                    "answer": {"type": "string", "title": "Your answer", "minLength": 1}
                  },
                  "required": ["answer"]
                }
                """.trimIndent(),
            ),
            timeoutSecs = 300,
        )

        requireNotNull(prompt)
        assertNull(prompt.choiceProperty)
        assertTrue(prompt.options.isEmpty())
        assertEquals("answer", prompt.freeFormProperty)
        assertEquals("Your answer", prompt.freeFormLabel)
        assertTrue(prompt.freeFormRequired)
    }

    @Test
    fun parsesChoiceUnderArbitrarySchemaPropertyName() {
        val prompt = parseElicitationPrompt(
            requestId = "req-5",
            message = "Which strategy?",
            schema = schema(
                """
                {
                  "type": "object",
                  "properties": {
                    "strategy": {
                      "type": "string",
                      "oneOf": [
                        {"const": "choice-0", "title": "Conservative"},
                        {"const": "choice-1", "title": "Balanced"}
                      ]
                    }
                  }
                }
                """.trimIndent(),
            ),
            timeoutSecs = null,
        )

        requireNotNull(prompt)
        assertEquals("strategy", prompt.choiceProperty)
        assertEquals(listOf("Conservative", "Balanced"), prompt.options.map { it.label })
    }

    @Test
    fun returnsNullForUnsupportedSchema() {
        val prompt = parseElicitationPrompt(
            requestId = "req-4",
            message = "How many?",
            schema = schema(
                """
                {"type": "object", "properties": {"count": {"type": "integer"}}}
                """.trimIndent(),
            ),
            timeoutSecs = 5,
        )
        assertNull(prompt)
    }
}
