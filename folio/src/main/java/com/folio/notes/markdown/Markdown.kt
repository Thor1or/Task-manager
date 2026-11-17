package com.folio.notes.markdown

/** What a single line of a note is, decided by its Markdown prefix. */
enum class LineKind { BODY, H1, H2, H3, QUOTE, BULLET, NUMBERED, TODO, DONE, CODE, FENCE, RULE }

/**
 * One line of a note. Offsets are absolute positions in the whole text; [end] excludes the newline.
 * The marker (`# `, `- [ ] `, `1. ` …) occupies [markerStart, contentStart).
 */
data class Line(
    val start: Int,
    val end: Int,
    val kind: LineKind,
    val indent: Int = 0,
    val contentStart: Int = start,
    val number: Int = 0,
) {
    val markerStart get() = start + indent
    val hasMarker get() = contentStart > markerStart
    val isList get() = kind == LineKind.BULLET || kind == LineKind.NUMBERED || kind == LineKind.TODO || kind == LineKind.DONE
}

enum class Inline { BOLD, ITALIC, STRIKE, UNDERLINE, HIGHLIGHT, CODE, LINK, URL, TAG }

/** An inline range: the whole of it is [start, end), the visible part [innerStart, innerEnd). */
data class Span(
    val type: Inline,
    val start: Int,
    val end: Int,
    val innerStart: Int = start,
    val innerEnd: Int = end,
    val url: String? = null,
)

object Markdown {
    const val ATTACHMENT_SCHEME = "attachment:"
    val IMAGE_LINE = Regex("""^!\[([^\]]*)]\(attachment:([\w.-]+)\)\s*$""")

    fun parseLines(text: CharSequence): List<Line> {
        val lines = ArrayList<Line>()
        var start = 0
        var inFence = false
        while (start <= text.length) {
            var end = start
            while (end < text.length && text[end] != '\n') end++
            lines += classify(text, start, end, inFence).also { if (it.kind == LineKind.FENCE) inFence = !inFence }
            start = end + 1
        }
        return lines
    }

    fun lineAt(lines: List<Line>, offset: Int): Line =
        lines.firstOrNull { offset >= it.start && offset <= it.end } ?: lines.last()

    private fun classify(text: CharSequence, start: Int, end: Int, inFence: Boolean): Line {
        var indent = 0
        while (start + indent < end && (text[start + indent] == ' ' || text[start + indent] == '\t')) indent++
        val m = start + indent
        fun at(i: Int) = if (i < end) text[i] else '\u0000'
        fun has(s: String) = text.startsWith(s, m) && m + s.length <= end

        if (has("```")) return Line(start, end, LineKind.FENCE)
        if (inFence) return Line(start, end, LineKind.CODE)
        if (indent == 0) {
            if (has("### ")) return Line(start, end, LineKind.H3, 0, m + 4)
            if (has("## ")) return Line(start, end, LineKind.H2, 0, m + 3)
            if (has("# ")) return Line(start, end, LineKind.H1, 0, m + 2)
        }
        if (at(m) == '>') {
            if (at(m + 1) == ' ') return Line(start, end, LineKind.QUOTE, indent, m + 2)
            if (m + 1 == end) return Line(start, end, LineKind.QUOTE, indent, m + 1)
        }
        if ((at(m) == '-' || at(m) == '*' || at(m) == '+') && at(m + 1) == ' ') {
            if (at(m + 2) == '[' && at(m + 4) == ']' && (at(m + 5) == ' ' || m + 5 == end)) {
                val box = at(m + 3)
                val contentStart = minOf(m + 6, end)
                if (box == ' ') return Line(start, end, LineKind.TODO, indent, contentStart)
                if (box == 'x' || box == 'X') return Line(start, end, LineKind.DONE, indent, contentStart)
            }
            return Line(start, end, LineKind.BULLET, indent, m + 2)
        }
        if (at(m).isAsciiDigit()) {
            var i = m
            while (at(i).isAsciiDigit() && i - m < 9) i++
            if ((at(i) == '.' || at(i) == ')') && at(i + 1) == ' ') {
                return Line(start, end, LineKind.NUMBERED, indent, i + 2, text.substring(m, i).toInt())
            }
        }
        if (end - m >= 3 && (at(m) == '-' || at(m) == '*' || at(m) == '_')) {
            val c = at(m)
            if ((m until end).all { text[it] == c }) return Line(start, end, LineKind.RULE, indent, m)
        }
        return Line(start, end, LineKind.BODY, indent, m)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private val CODE = Regex("`([^`\\n]+)`")
    private val LINK = Regex("""\[([^\]\n]+)]\(([^)\s]+)\)""")
    private val URL = Regex("""https?://[^\s<>\u0000]+[^\s<>\u0000.,;:!?)\]'"]""")
    private val BOLD = Regex("""\*\*(?=\S)(.+?)(?<=\S)\*\*(?!\*)""")
    private val ITALIC_STAR = Regex("""(?<![*\w])\*(?=[^\s*])(.+?)(?<=[^\s*])\*(?![*\w])""")
    private val ITALIC_UNDER = Regex("""(?<![\w_])_(?=[^\s_])(.+?)(?<=[^\s_])_(?![\w_])""")
    private val STRIKE = Regex("""~~(?=\S)(.+?)(?<=\S)~~""")
    private val UNDERLINE = Regex("""<u>(.+?)</u>""")
    private val HIGHLIGHT = Regex("""==(?=\S)(.+?)(?<=\S)==""")
    private val TAG = Regex("""(?<![\w#/])#(?=[\p{L}\p{N}_-]*\p{L})([\p{L}\p{N}_-]+)""")
    private const val MASK = '\u0000'

    /** Inline formatting inside [from, to) – one line's content. Spans may nest (bold inside a link, etc.). */
    fun parseInline(text: CharSequence, from: Int, to: Int): List<Span> {
        if (to <= from) return emptyList()
        val work = StringBuilder(text.subSequence(from, to))
        val spans = ArrayList<Span>()
        fun mask(a: Int, b: Int) { for (i in a until b) work.setCharAt(i, MASK) }

        CODE.findAll(work.toString()).toList().forEach { m ->
            spans += Span(Inline.CODE, from + m.range.first, from + m.range.last + 1, from + m.range.first + 1, from + m.range.last)
            mask(m.range.first, m.range.last + 1)
        }
        LINK.findAll(work.toString()).toList().forEach { m ->
            val label = m.groups[1]!!.range
            spans += Span(Inline.LINK, from + m.range.first, from + m.range.last + 1, from + label.first, from + label.last + 1, m.groupValues[2])
            mask(m.range.first, label.first)
            mask(label.last + 1, m.range.last + 1)
        }
        URL.findAll(work.toString()).toList().forEach { m ->
            spans += Span(Inline.URL, from + m.range.first, from + m.range.last + 1, url = m.value)
            mask(m.range.first, m.range.last + 1)
        }
        fun paired(regex: Regex, type: Inline, open: Int, close: Int = open) {
            regex.findAll(work.toString()).toList().forEach { m ->
                val s = m.range.first
                val e = m.range.last + 1
                spans += Span(type, from + s, from + e, from + s + open, from + e - close)
                mask(s, s + open)
                mask(e - close, e)
            }
        }
        paired(BOLD, Inline.BOLD, 2)
        paired(ITALIC_STAR, Inline.ITALIC, 1)
        paired(ITALIC_UNDER, Inline.ITALIC, 1)
        paired(STRIKE, Inline.STRIKE, 2)
        paired(UNDERLINE, Inline.UNDERLINE, 3, 4)
        paired(HIGHLIGHT, Inline.HIGHLIGHT, 2)
        TAG.findAll(work.toString()).forEach { m ->
            spans += Span(Inline.TAG, from + m.range.first, from + m.range.last + 1, from + m.range.first + 1, from + m.range.last + 1)
        }
        return spans
    }

    /** Drops inline syntax from one line's content, keeping what a reader would see. */
    fun stripInline(content: String, linkTargets: Boolean = false): String {
        val spans = parseInline(content, 0, content.length)
        if (spans.isEmpty()) return content
        val hidden = BooleanArray(content.length)
        val suffix = HashMap<Int, String>()
        for (s in spans) {
            if (s.type == Inline.URL || s.type == Inline.TAG) continue
            for (i in s.start until s.innerStart) hidden[i] = true
            for (i in s.innerEnd until s.end) hidden[i] = true
            if (linkTargets && s.type == Inline.LINK) suffix[s.end - 1] = " (${s.url})"
        }
        return buildString {
            for (i in content.indices) {
                if (!hidden[i]) append(content[i])
                suffix[i]?.let { append(it) }
            }
        }
    }

    /** The note as a reader would see it, without any Markdown syntax. */
    fun toPlainText(markdown: String, linkTargets: Boolean = true): String {
        val lines = parseLines(markdown)
        return buildString {
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val raw = markdown.substring(line.start, line.end)
                if (i > 0) append('\n')
                val image = IMAGE_LINE.find(raw)
                if (line.kind != LineKind.CODE && line.kind != LineKind.FENCE && Blocks.isTableStart(markdown, lines, i)) {
                    var first = true
                    while (i < lines.size && markdown.substring(lines[i].start, lines[i].end).trimStart().startsWith("|")) {
                        val row = markdown.substring(lines[i].start, lines[i].end)
                        if (!Blocks.isSeparatorRow(row)) {
                            if (!first) append('\n')
                            append(Blocks.splitRow(row).joinToString("\t") { stripInline(it, linkTargets) })
                            first = false
                        }
                        i++
                    }
                    continue
                }
                val content = markdown.substring(line.contentStart, line.end)
                when {
                    image != null -> append(image.groupValues[1].ifBlank { "[Image]" })
                    line.kind == LineKind.FENCE -> {}
                    line.kind == LineKind.CODE -> append(raw)
                    line.kind == LineKind.RULE -> append("————")
                    else -> {
                        append(raw, 0, line.indent)
                        when (line.kind) {
                            LineKind.TODO -> append("☐ ")
                            LineKind.DONE -> append("☑ ")
                            LineKind.BULLET -> append("• ")
                            LineKind.NUMBERED -> append("${line.number}. ")
                            else -> {}
                        }
                        append(stripInline(content, linkTargets))
                    }
                }
                i++
            }
        }
    }

    /** First non-blank line as the title, and the text after it as a preview. */
    fun titleAndPreview(markdown: String, previewLength: Int = 280): Pair<String, String> {
        val plain = toPlainText(markdown, linkTargets = false).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        val title = plain.firstOrNull().orEmpty().take(120)
        val preview = plain.drop(1).joinToString("\n").take(previewLength)
        return title to preview
    }

    fun tags(markdown: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (line in parseLines(markdown)) {
            if (line.kind == LineKind.CODE || line.kind == LineKind.FENCE) continue
            parseInline(markdown, line.contentStart, line.end)
                .filter { it.type == Inline.TAG }
                .forEach { out += markdown.substring(it.innerStart, it.innerEnd).lowercase() }
        }
        return out
    }

    fun attachments(markdown: String): List<String> =
        markdown.lineSequence().mapNotNull { IMAGE_LINE.find(it)?.groupValues?.get(2) }.toList()

    fun wordCount(markdown: String): Int =
        toPlainText(markdown, linkTargets = false).split(Regex("\\s+")).count { w -> w.any { it.isLetterOrDigit() } }
}
