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
}
