package ai.zeroclaw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import ai.zeroclaw.app.ui.theme.MonoFamily
import ai.zeroclaw.app.ui.theme.Zc

/** Minimal block model: enough for chat replies without a markdown engine. */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullets(val items: List<String>) : MdBlock
    data class Numbers(val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data object Rule : MdBlock
}

fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = source.replace("\r\n", "\n").split('\n')
    val paragraph = StringBuilder()
    val bullets = mutableListOf<String>()
    val numbers = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }

    fun flushBullets() {
        if (bullets.isNotEmpty()) blocks += MdBlock.Bullets(bullets.toList())
        bullets.clear()
    }

    fun flushNumbers() {
        if (numbers.isNotEmpty()) blocks += MdBlock.Numbers(numbers.toList())
        numbers.clear()
    }

    fun flushAll() {
        flushParagraph()
        flushBullets()
        flushNumbers()
    }

    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        when {
            line.trimStart().startsWith("```") -> {
                flushAll()
                val language = line.trim().removePrefix("```").trim()
                val code = StringBuilder()
                index += 1
                while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                    code.appendLine(lines[index])
                    index += 1
                }
                blocks += MdBlock.Code(language, code.toString().trimEnd())
            }

            line.startsWith("#") -> {
                flushAll()
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                blocks += MdBlock.Heading(level, line.drop(level).trim())
            }

            line.trim() == "---" || line.trim() == "***" -> {
                flushAll()
                blocks += MdBlock.Rule
            }

            line.trimStart().startsWith("> ") -> {
                flushAll()
                val quote = StringBuilder(line.trimStart().removePrefix("> ").trim())
                var next = index + 1
                while (next < lines.size && lines[next].trimStart().startsWith("> ")) {
                    quote.append('\n').append(lines[next].trimStart().removePrefix("> ").trim())
                    next += 1
                }
                blocks += MdBlock.Quote(quote.toString())
                index = next - 1
            }

            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                flushParagraph()
                flushNumbers()
                bullets += line.trimStart().substring(2).trim()
            }

            Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(line) -> {
                flushParagraph()
                flushBullets()
                numbers += line.trimStart().replaceFirst(Regex("^\\d+[.)]\\s+"), "")
            }

            line.isBlank() -> flushAll()

            else -> {
                flushBullets()
                flushNumbers()
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
        index += 1
    }
    flushAll()
    return blocks
}

@Composable
fun MarkdownBlocks(blocks: List<MdBlock>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        blocks.forEachIndexed { position, block ->
            if (position > 0) Spacer(Modifier.height(if (block is MdBlock.Code) 10.dp else 6.dp))
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Zc.Text,
                )

                is MdBlock.Heading -> Text(
                    text = inlineMarkdown(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineMedium
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    color = Zc.Text,
                )

                is MdBlock.Bullets -> Column {
                    block.items.forEach { item ->
                        Row(item = "•", text = item)
                    }
                }

                is MdBlock.Numbers -> Column {
                    block.items.forEachIndexed { number, item ->
                        Row(item = "${number + 1}.", text = item)
                    }
                }

                is MdBlock.Quote -> Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Zc.SurfaceRaised, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = inlineMarkdown(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Zc.TextMuted,
                    )
                }

                is MdBlock.Code -> CodeBlock(block.language, block.code)

                MdBlock.Rule -> Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Zc.Border),
                )
            }
        }
    }
}

@Composable
private fun Row(item: String, text: String) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
        Text(
            text = item,
            style = MaterialTheme.typography.bodyLarge,
            color = Zc.TextSubtle,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = inlineMarkdown(text),
            style = MaterialTheme.typography.bodyLarge,
            color = Zc.Text,
        )
    }
}

@Composable
fun CodeBlock(language: String, code: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Zc.CodeBg, RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp),
    ) {
        if (language.isNotBlank()) {
            Text(
                text = language.lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Zc.TextSubtle,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        Text(
            text = code,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
            color = Zc.Text,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        )
    }
}

/**
 * Inline formatting: `code`, **bold**, *italic*, ~~strike~~ and [links](url),
 * where links keep their label and are tinted rather than opened.
 */
fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            char == '`' -> {
                val end = text.indexOf('`', index + 1)
                if (end > index) {
                    pushStyle(
                        SpanStyle(
                            fontFamily = MonoFamily,
                            color = Zc.Accent,
                            background = Zc.CodeBg,
                        ),
                    )
                    append(text.substring(index + 1, end))
                    pop()
                    index = end + 1
                } else {
                    append(char)
                    index += 1
                }
            }

            text.startsWith("**", index) -> {
                val end = text.indexOf("**", index + 2)
                if (end > index) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                    append(text.substring(index + 2, end))
                    pop()
                    index = end + 2
                } else {
                    append(char)
                    index += 1
                }
            }

            text.startsWith("~~", index) -> {
                val end = text.indexOf("~~", index + 2)
                if (end > index) {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    append(text.substring(index + 2, end))
                    pop()
                    index = end + 2
                } else {
                    append(char)
                    index += 1
                }
            }

            char == '*' || char == '_' -> {
                val end = text.indexOf(char, index + 1)
                if (end > index + 1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(index + 1, end))
                    pop()
                    index = end + 1
                } else {
                    append(char)
                    index += 1
                }
            }

            text.startsWith("[", index) -> {
                val labelEnd = text.indexOf(']', index)
                val urlStart = if (labelEnd > index) text.indexOf('(', labelEnd) else -1
                val urlEnd = if (urlStart > 0) text.indexOf(')', urlStart) else -1
                if (labelEnd > index && urlStart == labelEnd + 1 && urlEnd > urlStart) {
                    pushStyle(SpanStyle(color = Zc.Accent, textDecoration = TextDecoration.Underline))
                    append(text.substring(index + 1, labelEnd))
                    pop()
                    index = urlEnd + 1
                } else {
                    append(char)
                    index += 1
                }
            }

            else -> {
                append(char)
                index += 1
            }
        }
    }
}
