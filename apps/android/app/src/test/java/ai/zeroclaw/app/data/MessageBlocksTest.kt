package ai.zeroclaw.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBlocksTest {
    private fun card(name: String, id: String = name) = ToolCallCard(
        id = id,
        name = name,
        args = null,
    )

    @Test
    fun textThinkingAndToolsKeepStreamOrder() {
        var message = ChatMessage(id = "m1", role = MessageRole.Assistant, streaming = true)
        message = message.appendThinking("checking the machine")
        message = message.appendText("Here is the result.")
        message = message.appendTool(card("shell"))
        message = message.completeTool(null, "shell", "ok")
        message = message.appendText(" Done.")

        val blocks = message.contentBlocks
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is MessageBlock.Thinking)
        assertEquals("Here is the result.", (blocks[1] as MessageBlock.Text).text)
        val tool = blocks[2] as MessageBlock.Tool
        assertEquals("shell", tool.card.name)
        assertTrue(tool.card.finished)
        assertEquals(" Done.", (blocks[3] as MessageBlock.Text).text)
    }

    @Test
    fun toolResultUpdatesTheMatchingUnfinishedCard() {
        var message = ChatMessage(id = "m1", role = MessageRole.Assistant, streaming = true)
        message = message.appendTool(card("shell", "a"))
        message = message.appendTool(card("shell", "b"))
        message = message.completeTool(null, "shell", "first result")

        val tools = message.contentBlocks.filterIsInstance<MessageBlock.Tool>().map { it.card }
        assertEquals(2, tools.size)
        assertTrue(tools[0].finished)
        assertEquals("first result", tools[0].output)
        assertFalse(tools[1].finished)
    }

    @Test
    fun toolResultMatchesByIdWhenNamesRepeat() {
        var message = ChatMessage(id = "m1", role = MessageRole.Assistant, streaming = true)
        message = message.appendTool(card("shell", "a"))
        message = message.appendTool(card("shell", "b"))
        message = message.completeTool("b", "shell", "second")

        val tools = message.contentBlocks.filterIsInstance<MessageBlock.Tool>().map { it.card }
        assertFalse(tools[0].finished)
        assertTrue(tools[1].finished)
        assertEquals("second", tools[1].output)
    }

    @Test
    fun legacyPlainTextIsStillRenderedAsOneBlock() {
        val message = ChatMessage(
            id = "m2",
            role = MessageRole.Assistant,
            text = "history text",
        )
        assertEquals(
            listOf(MessageBlock.Text("history text")),
            message.contentBlocks,
        )
    }

    @Test
    fun finishWithAppendsTheFailureReasonAfterPartialBlocks() {
        var message = ChatMessage(id = "m3", role = MessageRole.Assistant, streaming = true)
        message = message.appendText("partial")
        message = message.finishWith("turn failed", isError = true)

        val blocks = message.contentBlocks
        assertEquals("partial", (blocks[0] as MessageBlock.Text).text)
        assertEquals("turn failed", (blocks[1] as MessageBlock.Text).text)
        assertTrue(message.isError)
        assertFalse(message.streaming)
    }
}
