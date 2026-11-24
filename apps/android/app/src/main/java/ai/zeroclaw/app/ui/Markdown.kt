package ai.zeroclaw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
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
    data class Table(
        val headers: List<String>,
        val alignments: List<MdTableAlign>,
        val rows: List<List<String>>,
    ) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data object Rule : MdBlock
}

enum class MdTableAlign { Left, Center, Right }

private val TableSeparator = Regex(
    """^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$""",
)

private fun splitTableRow(line: String): List<String> {
    var text = line.trim()
    if (text.startsWith("|")) text = text.substring(1)
    if (text.endsWith("|") && !text.endsWith("\\|")) text = text.dropLast(1)
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            char == '\\' && index + 1 < text.length && text[index + 1] == '|' -> {
                cell.append('|')
                index += 2
            }
            char == '|' -> {
                cells += cell.toString().trim()
                cell.clear()
                index += 1
            }
            else -> {
                cell.append(char)
                index += 1
            }
        }
    }
    cells += cell.toString().trim()
    return cells
}

private fun tableAlignment(cell: String): MdTableAlign {
    val value = cell.trim()
    return when {
        value.startsWith(":") && value.endsWith(":") -> MdTableAlign.Center
        value.endsWith(":") -> MdTableAlign.Right
        else -> MdTableAlign.Left
    }
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

            line.contains('|') &&
                index + 1 < lines.size &&
                TableSeparator.matches(lines[index + 1].trim()) -> {
                flushAll()
                val headers = splitTableRow(line)
                val separator = splitTableRow(lines[index + 1])
                val alignments = headers.indices.map { column ->
                    tableAlignment(separator.getOrNull(column).orEmpty())
                }
                val rows = mutableListOf<List<String>>()
                var next = index + 2
                while (next < lines.size) {
                    val row = lines[next]
                    if (row.isBlank() || !row.contains('|')) break
                    val cells = splitTableRow(row)
                    rows += headers.indices.map { column -> cells.getOrNull(column).orEmpty() }
                    next += 1
                }
                blocks += MdBlock.Table(headers, alignments, rows)
                index = next - 1
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

/** Flatten parsed Markdown into readable clipboard/share text. */
fun plainTextForClipboard(source: String): String {
    val parts = mutableListOf<String>()
    parseMarkdown(source).forEach { block ->
        when (block) {
            is MdBlock.Paragraph -> parts += inlineMarkdown(block.text).text
            is MdBlock.Heading -> parts += inlineMarkdown(block.text).text
            is MdBlock.Bullets -> parts += block.items.joinToString("\n") {
                "• ${inlineMarkdown(it).text}"
            }
            is MdBlock.Numbers -> parts += block.items.mapIndexed { index, item ->
                "${index + 1}. ${inlineMarkdown(item).text}"
            }.joinToString("\n")
            is MdBlock.Quote -> parts += inlineMarkdown(block.text).text
            is MdBlock.Code -> parts += block.code
            is MdBlock.Table -> {
                val lines = mutableListOf(
                    block.headers.joinToString(" | ") { inlineMarkdown(it).text },
                )
                block.rows.forEach { row ->
                    lines += row.joinToString(" | ") { inlineMarkdown(it).text }
                }
                parts += lines.joinToString("\n")
            }
            MdBlock.Rule -> Unit
        }
    }
    return parts.filter { it.isNotBlank() }.joinToString("\n\n").trim()
}

/** Flatten parsed Markdown into readable speech without syntax markers. */
fun plainTextForSpeech(source: String): String {
    val parts = mutableListOf<String>()
    parseMarkdown(source).forEach { block ->
        when (block) {
            is MdBlock.Paragraph -> parts += inlineMarkdown(block.text).text
            is MdBlock.Heading -> parts += inlineMarkdown(block.text).text
            is MdBlock.Bullets -> block.items.forEach { parts += inlineMarkdown(it).text }
            is MdBlock.Numbers -> block.items.forEach { parts += inlineMarkdown(it).text }
            is MdBlock.Quote -> parts += inlineMarkdown(block.text).text
            is MdBlock.Code -> parts += block.code
            is MdBlock.Table -> block.rows.forEach { row ->
                val pairs = row.mapIndexedNotNull { index, cell ->
                    val value = inlineMarkdown(cell).text
                    if (value.isBlank()) {
                        null
                    } else {
                        val header = block.headers.getOrNull(index).orEmpty()
                        if (header.isBlank()) value else "$header: $value"
                    }
                }
                if (pairs.isNotEmpty()) parts += pairs.joinToString(", ")
            }
            MdBlock.Rule -> Unit
        }
    }
    return parts
        .filter { it.isNotBlank() }
        .joinToString(". ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

@Composable
fun MarkdownBlocks(blocks: List<MdBlock>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        blocks.forEachIndexed { position, block ->
            if (position > 0) {
                Spacer(
                    Modifier.height(
                        if (block is MdBlock.Code || block is MdBlock.Table) 10.dp else 6.dp,
                    ),
                )
            }
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
                        ListRow(item = "•", text = item)
                    }
                }

                is MdBlock.Numbers -> Column {
                    block.items.forEachIndexed { number, item ->
                        ListRow(item = "${number + 1}.", text = item)
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

                is MdBlock.Table -> TableBlock(block)

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
private fun ListRow(item: String, text: String) {
    Row(Modifier.fillMaxWidth()) {
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
private fun TableBlock(table: MdBlock.Table) {
    if (table.headers.isEmpty()) return
    val minCellWidth = 72.dp
    val maxCellWidth = 220.dp
    val widths = table.headers.indices.map { column ->
        val longest = (sequenceOf(table.headers.getOrNull(column).orEmpty()) +
            table.rows.asSequence().map { it.getOrNull(column).orEmpty() })
            .maxOfOrNull { it.length } ?: 0
        (longest * 7).dp.coerceIn(minCellWidth, maxCellWidth)
    }
    val totalWidth = widths.fold(0.dp) { total, width -> total + width }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scroll = totalWidth > maxWidth
        val fill = !scroll
        Column(
            Modifier
                .then(
                    if (scroll) {
                        Modifier.horizontalScroll(rememberScrollState())
                    } else {
                        Modifier.fillMaxWidth()
                    },
                )
                .clip(RoundedCornerShape(10.dp))
                .background(Zc.SurfaceRaised)
                .border(1.dp, Zc.Border, RoundedCornerShape(10.dp)),
        ) {
            TableRow(
                cells = table.headers,
                widths = widths,
                header = true,
                alignments = table.alignments,
                fill = fill,
                totalWidth = totalWidth,
            )
            table.rows.forEachIndexed { index, row ->
                if (index > 0) TableDivider(fill, totalWidth)
                TableRow(
                    cells = row,
                    widths = widths,
                    header = false,
                    alignments = table.alignments,
                    fill = fill,
                    totalWidth = totalWidth,
                )
            }
        }
    }
}

@Composable
private fun TableDivider(fill: Boolean, totalWidth: Dp) {
    Box(
        Modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier.width(totalWidth))
            .height(1.dp)
            .background(Zc.Border),
    )
}

@Composable
private fun TableRow(
    cells: List<String>,
    widths: List<Dp>,
    header: Boolean,
    alignments: List<MdTableAlign>,
    fill: Boolean,
    totalWidth: Dp,
) {
    Row(
        Modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier.width(totalWidth))
            .then(if (header) Modifier.background(Zc.SurfacePressed) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        widths.forEachIndexed { column, width ->
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(width.value) else Modifier.width(width))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = when (alignments.getOrNull(column) ?: MdTableAlign.Left) {
                    MdTableAlign.Left -> Alignment.CenterStart
                    MdTableAlign.Center -> Alignment.Center
                    MdTableAlign.Right -> Alignment.CenterEnd
                },
            ) {
                Text(
                    text = inlineMarkdown(cells.getOrNull(column).orEmpty()),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (header) Zc.Text else Zc.TextMuted,
                )
            }
        }
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

            text.startsWith("***", index) -> {
                val end = text.indexOf("***", index + 3)
                if (end > index) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic))
                    append(text.substring(index + 3, end))
                    pop()
                    index = end + 3
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

            text.startsWith("___", index) -> {
                val end = text.indexOf("___", index + 3)
                if (end > index) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic))
                    append(text.substring(index + 3, end))
                    pop()
                    index = end + 3
                } else {
                    append(char)
                    index += 1
                }
            }

            text.startsWith("__", index) -> {
                val end = text.indexOf("__", index + 2)
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
                val before = text.getOrNull(index - 1)
                val boundaryBefore = before == null || !before.isLetterOrDigit()
                val end = text.indexOf(char, index + 1)
                val after = end.takeIf { it >= 0 }?.let { text.getOrNull(it + 1) }
                val boundaryAfter = after == null || !after.isLetterOrDigit()
                if (boundaryBefore && end > index + 1 && boundaryAfter) {
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
