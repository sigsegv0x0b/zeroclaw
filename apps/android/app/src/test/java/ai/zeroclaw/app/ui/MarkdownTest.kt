package ai.zeroclaw.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `splits paragraphs and headings`() {
        val blocks = parseMarkdown("# Title\n\nFirst line.\nSecond line.\n\n## Sub")
        assertEquals(MdBlock.Heading(1, "Title"), blocks[0])
        assertEquals(MdBlock.Paragraph("First line. Second line."), blocks[1])
        assertEquals(MdBlock.Heading(2, "Sub"), blocks[2])
    }

    @Test
    fun `parses fenced code with language`() {
        val blocks = parseMarkdown("Before\n\n```rust\nfn main() {}\n\n```\n\nAfter")
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("rust", code.language)
        assertTrue(code.code.contains("fn main()"))
        assertEquals(MdBlock.Paragraph("After"), blocks.last())
    }

    @Test
    fun `parses bullets and numbered lists separately`() {
        val blocks = parseMarkdown("- one\n- two\n\n1. first\n2. second")
        assertEquals(MdBlock.Bullets(listOf("one", "two")), blocks[0])
        assertEquals(MdBlock.Numbers(listOf("first", "second")), blocks[1])
    }

    @Test
    fun `unclosed fence keeps the rest as code while streaming`() {
        val blocks = parseMarkdown("Intro\n```python\nprint(1)")
        assertEquals(MdBlock.Paragraph("Intro"), blocks[0])
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("python", code.language)
        assertEquals("print(1)", code.code)
    }

    @Test
    fun `quotes collapse consecutive lines`() {
        val blocks = parseMarkdown("> quoted\n> more")
        assertEquals(MdBlock.Quote("quoted\nmore"), blocks.single())
    }

    @Test
    fun `inline removes markdown markers`() {
        val rendered = inlineMarkdown("a **bold** and `code` and *it*").text
        assertEquals("a bold and code and it", rendered)
    }

    @Test
    fun `inline keeps link labels`() {
        val rendered = inlineMarkdown("see [docs](https://example.com)").text
        assertEquals("see docs", rendered)
    }

    @Test
    fun `strikethrough markers are removed`() {
        assertEquals("old", inlineMarkdown("~~old~~").text)
    }

    @Test
    fun `parses a gfm table with alignment and inline cells`() {
        val blocks = parseMarkdown(
            """
            | Spec | Value |
            | :--- | ---: |
            | Host | `slowmini` |
            | OS | macOS |
            """.trimIndent(),
        )
        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf("Spec", "Value"), table.headers)
        assertEquals(listOf(MdTableAlign.Left, MdTableAlign.Right), table.alignments)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Host", "`slowmini`"), table.rows[0])
        assertEquals(listOf("OS", "macOS"), table.rows[1])
    }

    @Test
    fun `parses a table without outer pipes and escaped separators`() {
        val blocks = parseMarkdown(
            """
            Name | Detail
            --- | ---
            A \| B | backslash pipe
            """.trimIndent(),
        )
        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf("Name", "Detail"), table.headers)
        assertEquals(listOf("A | B", "backslash pipe"), table.rows.single())
    }

    @Test
    fun `pipe paragraph without a separator stays a paragraph`() {
        val blocks = parseMarkdown("a | b\nnot a separator")
        assertEquals(MdBlock.Paragraph("a | b not a separator"), blocks.single())
    }

    @Test
    fun `underscore bold markers are removed`() {
        assertEquals("bold", inlineMarkdown("__bold__").text)
    }

    @Test
    fun `triple emphasis markers are removed`() {
        assertEquals("both", inlineMarkdown("***both***").text)
        assertEquals("both", inlineMarkdown("___both___").text)
    }

    @Test
    fun `underscores inside identifiers are not italic markers`() {
        assertEquals("snake_case_name", inlineMarkdown("snake_case_name").text)
    }

    @Test
    fun `speech text flattens markup and tables`() {
        val speech = plainTextForSpeech(
            """
            **Host:** `slowmini`

            | Spec | Value |
            | --- | --- |
            | OS | macOS |
            """.trimIndent(),
        )
        assertEquals("Host: slowmini. Spec: OS, Value: macOS", speech)
    }

    @Test
    fun `clipboard text keeps readable block layout`() {
        val text = plainTextForClipboard(
            """
            **Host:** `slowmini`

            | Spec | Value |
            | --- | --- |
            | OS | macOS |
            """.trimIndent(),
        )
        assertEquals("Host: slowmini\n\nSpec | Value\nOS | macOS", text)
    }
}
