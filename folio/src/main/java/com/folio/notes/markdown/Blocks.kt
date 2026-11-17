package com.folio.notes.markdown

import androidx.compose.ui.text.input.TextFieldValue
import java.util.concurrent.atomic.AtomicLong

/**
 * A note on screen is a column of blocks: runs of Markdown text, with pictures and tables in between.
 * On disk it is one Markdown string; a picture is a line `![alt](attachment:file)` and a table a GFM table.
 */
sealed interface Block { val id: Long }

data class TextBlock(override val id: Long, val value: TextFieldValue) : Block {
    val text get() = value.text
}

data class ImageBlock(override val id: Long, val file: String, val alt: String = "") : Block

/** [rows] always has at least one row and one column; the first row is the header. */
data class TableBlock(override val id: Long, val rows: List<List<String>>) : Block

object Blocks {
    private val ids = AtomicLong(1)
    fun newId() = ids.getAndIncrement()
    fun text(text: String = "") = TextBlock(newId(), TextFieldValue(text))

    private val SEPARATOR = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")

    fun isSeparatorRow(row: String) = row.contains('|') && SEPARATOR.matches(row)

    fun isTableStart(text: String, lines: List<Line>, i: Int): Boolean {
        if (i + 1 >= lines.size) return false
        val a = text.substring(lines[i].start, lines[i].end)
        val b = text.substring(lines[i + 1].start, lines[i + 1].end)
        return a.trimStart().startsWith("|") && isSeparatorRow(b)
    }

    fun splitRow(row: String): List<String> {
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        val s = row.trim().removePrefix("|")
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] == '|' -> { cell.append('|'); i++ }
                c == '|' -> { cells += cell.toString().trim(); cell.setLength(0) }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotBlank()) cells += cell.toString().trim()
        return cells
    }

    fun parse(markdown: String): List<Block> {
        val lines = Markdown.parseLines(markdown)
        val blocks = ArrayList<Block>()
        val pending = ArrayList<String>()
        var hasPending = false
        fun flush() {
            if (hasPending) blocks += text(pending.joinToString("\n"))
            pending.clear()
            hasPending = false
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val raw = markdown.substring(line.start, line.end)
            val inCode = line.kind == LineKind.CODE || line.kind == LineKind.FENCE
            val image = if (inCode) null else Markdown.IMAGE_LINE.find(raw)
            when {
                image != null -> {
                    flush()
                    blocks += ImageBlock(newId(), image.groupValues[2], image.groupValues[1])
                    i++
                }
                !inCode && isTableStart(markdown, lines, i) -> {
                    flush()
                    val rows = ArrayList<List<String>>()
                    rows += splitRow(raw)
                    i += 2
                    while (i < lines.size) {
                        val row = markdown.substring(lines[i].start, lines[i].end)
                        if (!row.trimStart().startsWith("|")) break
                        rows += splitRow(row)
                        i++
                    }
                    blocks += TableBlock(newId(), squareUp(rows))
                }
                else -> { pending += raw; hasPending = true; i++ }
            }
        }
        flush()
        return normalize(blocks)
    }

    private fun squareUp(rows: List<List<String>>): List<List<String>> {
        val width = maxOf(1, rows.maxOf { it.size })
        return rows.map { r -> List(width) { r.getOrElse(it) { "" } } }
    }

    /** Guarantees there is somewhere to type before, after and between pictures and tables. */
    fun normalize(blocks: List<Block>): List<Block> {
        val out = ArrayList<Block>()
        for (b in blocks) {
            val last = out.lastOrNull()
            if (b is TextBlock && last is TextBlock) {
                out[out.size - 1] = last.copy(value = TextFieldValue(last.text + "\n" + b.text))
                continue
            }
            if (b !is TextBlock && last !is TextBlock) out += text()
            out += b
        }
        if (out.lastOrNull() !is TextBlock) out += text()
        return out
    }

    fun serialize(blocks: List<Block>): String = blocks.joinToString("\n") { b ->
        when (b) {
            is TextBlock -> b.text
            is ImageBlock -> "![${b.alt.replace("]", "")}](${Markdown.ATTACHMENT_SCHEME}${b.file})"
            is TableBlock -> tableMarkdown(b.rows)
        }
    }

    fun tableMarkdown(rows: List<List<String>>): String {
        fun row(cells: List<String>) = cells.joinToString(" | ", "| ", " |") { it.replace("|", "\\|").replace("\n", " ") }
        return buildString {
            append(row(rows.first()))
            append('\n')
            append(rows.first().joinToString(" | ", "| ", " |") { "---" })
            rows.drop(1).forEach { append('\n'); append(row(it)) }
        }
    }
}
