package com.folio.notes.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** How much Markdown syntax the editor shows. */
enum class SyntaxMode { HIDE, CURRENT_LINE, SHOW }

data class MarkdownColors(
    val text: Color,
    val muted: Color,
    val accent: Color,
    val codeBackground: Color,
    val highlight: Color,
)

/** A tick box drawn over the text: [offset] is where its placeholder sits in the displayed text. */
data class CheckboxMark(val lineStart: Int, val offset: Int, val checked: Boolean)

data class LinkMark(val start: Int, val end: Int, val url: String)

class StyledMarkdown(
    val text: AnnotatedString,
    val mapping: OffsetMapping,
    val checkboxes: List<CheckboxMark>,
    val links: List<LinkMark>,
)

object MarkdownStyler {
    /** Em space: a blank as wide as the font is tall, which the editor paints a tick box into. */
    const val BOX = ' '

    fun titleSize(base: TextUnit) = (base.value * 1.7f).sp
    private fun headingSize(base: TextUnit) = (base.value * 1.35f).sp
    private fun subheadingSize(base: TextUnit) = (base.value * 1.12f).sp

    /**
     * Builds what the editor displays for [source]. [revealed] is the caret's range in [source]; in
     * [SyntaxMode.CURRENT_LINE] the lines it touches show their raw Markdown.
     */
    fun style(
        source: String,
        colors: MarkdownColors,
        base: TextUnit,
        titleFirstLine: Boolean,
        mode: SyntaxMode,
        revealed: IntRange? = null,
    ): StyledMarkdown {
        val n = source.length
        val out = StringBuilder(n)
        val o2t = IntArray(n + 1)
        val t2o = ArrayList<Int>(n + 1)
        val checkboxes = ArrayList<CheckboxMark>()
        val links = ArrayList<LinkMark>()
        // Styles are collected against source offsets and converted once the mapping is complete.
        class Styled(val start: Int, val end: Int, val style: SpanStyle)
        val styles = ArrayList<Styled>()
        fun add(a: Int, b: Int, s: SpanStyle) { if (b > a) styles += Styled(a, b, s) }

        val muted = SpanStyle(color = colors.muted)
        val lines = Markdown.parseLines(source)
        var firstText = true
        for (line in lines) {
            val hide = when (mode) {
                SyntaxMode.HIDE -> true
                SyntaxMode.SHOW -> false
                SyntaxMode.CURRENT_LINE -> revealed == null || revealed.last < line.start || revealed.first > line.end
            }
            fun emit(i: Int, c: Char = source[i]) { o2t[i] = out.length; out.append(c); t2o += i }
            fun skip(i: Int) { o2t[i] = out.length }
            /** Shows [replacement] in place of the marker; touching it puts the caret at the start of the content. */
            fun replaceMarker(replacement: String) {
                for (i in line.markerStart until line.contentStart) skip(i)
                for (c in replacement) { out.append(c); t2o += line.contentStart }
            }

            val isTitle = titleFirstLine && firstText && (line.kind == LineKind.BODY || line.kind == LineKind.H1)
            if (line.end > line.start) firstText = false
            for (i in line.start until line.markerStart) emit(i)

            when (line.kind) {
                LineKind.FENCE, LineKind.CODE -> {
                    for (i in line.markerStart until line.end) emit(i)
                    add(line.start, line.end, SpanStyle(fontFamily = FontFamily.Monospace, fontSize = (base.value * 0.9f).sp, background = colors.codeBackground))
                    if (line.kind == LineKind.FENCE) add(line.start, line.end, muted)
                }
                LineKind.RULE -> {
                    for (i in line.markerStart until line.end) emit(i)
                    add(line.start, line.end, muted)
                }
                else -> {
                    when {
                        !line.hasMarker -> {}
                        !hide -> {
                            for (i in line.markerStart until line.contentStart) emit(i)
                            add(line.markerStart, line.contentStart, muted)
                        }
                        line.kind == LineKind.TODO || line.kind == LineKind.DONE -> {
                            checkboxes += CheckboxMark(line.start, out.length, line.kind == LineKind.DONE)
                            replaceMarker("$BOX ")
                        }
                        line.kind == LineKind.BULLET -> replaceMarker("•  ")
                        line.kind == LineKind.QUOTE -> replaceMarker("▎ ")
                        line.kind == LineKind.NUMBERED -> {
                            for (i in line.markerStart until line.contentStart) emit(i)
                        }
                        else -> for (i in line.markerStart until line.contentStart) skip(i) // headings
                    }
                    if (hide && (line.kind == LineKind.QUOTE || line.kind == LineKind.BULLET || line.kind == LineKind.NUMBERED)) {
                        add(line.markerStart, line.contentStart, SpanStyle(color = colors.accent, fontWeight = FontWeight.SemiBold))
                    }
                    val mask = if (hide) MarkdownEditing.hiddenMask(source, line) else null
                    for (i in line.contentStart until line.end) {
                        if (mask != null && mask[i - line.start]) skip(i) else emit(i)
                    }

                    when {
                        isTitle -> add(line.start, line.end, SpanStyle(fontSize = titleSize(base), fontWeight = FontWeight.Bold))
                        line.kind == LineKind.H1 -> add(line.start, line.end, SpanStyle(fontSize = headingSize(base), fontWeight = FontWeight.Bold))
                        line.kind == LineKind.H2 -> add(line.start, line.end, SpanStyle(fontSize = headingSize(base), fontWeight = FontWeight.Bold))
                        line.kind == LineKind.H3 -> add(line.start, line.end, SpanStyle(fontSize = subheadingSize(base), fontWeight = FontWeight.Bold))
                        line.kind == LineKind.QUOTE -> add(line.contentStart, line.end, SpanStyle(fontStyle = FontStyle.Italic, color = colors.muted))
                        line.kind == LineKind.DONE -> add(line.contentStart, line.end, muted)
                        else -> {}
                    }
                    if (line.kind == LineKind.H1 && !isTitle) add(line.start, line.end, SpanStyle(fontSize = (base.value * 1.5f).sp))

                    val spans = Markdown.parseInline(source, line.contentStart, line.end)
                    for (s in spans) {
                        if (!hide && s.type != Inline.URL && s.type != Inline.TAG) {
                            add(s.start, s.innerStart, muted)
                            add(s.innerEnd, s.end, muted)
                        }
                        val style = when (s.type) {
                            Inline.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                            Inline.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                            Inline.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                            Inline.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                            Inline.HIGHLIGHT -> SpanStyle(background = colors.highlight)
                            Inline.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground)
                            Inline.LINK, Inline.URL -> SpanStyle(color = colors.accent)
                            Inline.TAG -> SpanStyle(color = colors.accent, fontWeight = FontWeight.Medium)
                        }
                        if (s.type == Inline.TAG) add(s.start, s.end, style) else add(s.innerStart, s.innerEnd, style)
                    }
                    // Two decorations on the same characters would otherwise replace one another.
                    for (u in spans.filter { it.type == Inline.UNDERLINE }) for (k in spans.filter { it.type == Inline.STRIKE }) {
                        add(maxOf(u.innerStart, k.innerStart), minOf(u.innerEnd, k.innerEnd),
                            SpanStyle(textDecoration = TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))))
                    }
                    if (hide) for (s in spans) if (s.type == Inline.LINK || s.type == Inline.URL) {
                        links += LinkMark(s.innerStart, s.innerEnd, s.url!!) // source offsets, converted below
                    }
                }
            }
            if (line.end < n) emit(line.end)
        }
        o2t[n] = out.length
        t2o += n

        val text = AnnotatedString.Builder(out.toString()).apply {
            for (s in styles) {
                val a = o2t[s.start]
                val b = o2t[s.end]
                if (b > a) addStyle(s.style, a, b)
            }
        }.toAnnotatedString()
        val toOriginal = t2o.toIntArray()
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = o2t[offset.coerceIn(0, n)]
            override fun transformedToOriginal(offset: Int) = toOriginal[offset.coerceIn(0, toOriginal.size - 1)]
        }
        return StyledMarkdown(text, mapping, checkboxes, links.map { LinkMark(o2t[it.start], o2t[it.end], it.url) })
    }
}
