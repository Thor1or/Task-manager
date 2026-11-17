package com.folio.notes.markdown

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Pure text transformations behind the editor: smart Enter / Backspace and the formatting toolbar. */
object MarkdownEditing {
    const val INDENT = 2
    private const val MAX_INDENT = 8

    fun markers(type: Inline): Pair<String, String> = when (type) {
        Inline.BOLD -> "**" to "**"
        Inline.ITALIC -> "*" to "*"
        Inline.STRIKE -> "~~" to "~~"
        Inline.UNDERLINE -> "<u>" to "</u>"
        Inline.HIGHLIGHT -> "==" to "=="
        Inline.CODE -> "`" to "`"
        else -> "" to ""
    }

    private val PAIRED = listOf(Inline.BOLD, Inline.ITALIC, Inline.STRIKE, Inline.UNDERLINE, Inline.HIGHLIGHT, Inline.CODE)

    /** Which characters of a line are syntax that the editor hides. */
    fun hiddenMask(text: CharSequence, line: Line): BooleanArray {
        val mask = BooleanArray(line.end - line.start)
        if (line.kind == LineKind.CODE || line.kind == LineKind.FENCE) return mask
        for (s in Markdown.parseInline(text, line.contentStart, line.end)) {
            if (s.type == Inline.URL || s.type == Inline.TAG) continue
            for (i in s.start until s.innerStart) mask[i - line.start] = true
            for (i in s.innerEnd until s.end) mask[i - line.start] = true
        }
        return mask
    }

    /**
     * Post-processes a keystroke. [syntaxHidden] says the user cannot see inline markers, so Backspace must
     * act on what they do see.
     */
    fun onEdit(old: TextFieldValue, new: TextFieldValue, syntaxHidden: Boolean): TextFieldValue {
        if (old.text == new.text || !old.selection.collapsed || !new.selection.collapsed) return new
        val c = old.selection.start
        val o = old.text
        val n = new.text
        if (c > o.length) return new
        val grew = n.length - o.length
        if (grew == 1 && new.selection.start == c + 1 && n[c] == '\n' &&
            n.regionMatches(0, o, 0, c) && n.regionMatches(c + 1, o, c, o.length - c)
        ) return onEnter(o, c) ?: new
        if (grew == -1 && c > 0 && new.selection.start == c - 1 &&
            n.regionMatches(0, o, 0, c - 1) && n.regionMatches(c - 1, o, c, o.length - c)
        ) return onBackspace(o, c, syntaxHidden) ?: new
        return new
    }

    private fun at(text: String, cursor: Int) = TextFieldValue(text, TextRange(cursor.coerceIn(0, text.length)))

    private fun onEnter(text: String, c: Int): TextFieldValue? {
        val lines = Markdown.parseLines(text)
        val line = Markdown.lineAt(lines, c)
        if (!(line.isList || line.kind == LineKind.QUOTE) || c < line.contentStart) return null
        if (text.substring(line.contentStart, line.end).isBlank()) {
            // Enter on an empty item steps out of the list, one level at a time.
            return if (line.isList && line.indent >= INDENT) at(text.removeRange(line.start, line.start + INDENT), c - INDENT)
            else at(text.removeRange(line.start, line.end), line.start)
        }
        val marker = when (line.kind) {
            LineKind.TODO, LineKind.DONE -> "${text[line.markerStart]} [ ] "
            LineKind.NUMBERED -> "${line.number + 1}${text[line.contentStart - 2]} "
            else -> text.substring(line.markerStart, line.contentStart)
        }
        val insert = "\n" + text.substring(line.start, line.markerStart) + marker
        var out = text.substring(0, c) + insert + text.substring(c)
        if (line.kind == LineKind.NUMBERED) out = renumberAfter(out, c + insert.length)
        return at(out, c + insert.length)
    }

    /** Keeps a numbered list counting upwards below the line that contains [offset]. */
    private fun renumberAfter(text: String, offset: Int): String {
        val lines = Markdown.parseLines(text)
        val i = lines.indexOfFirst { offset >= it.start && offset <= it.end }
        if (i < 0) return text
        val edits = ArrayList<Rep>()
        var expected = lines[i].number
        for (j in i + 1 until lines.size) {
            val l = lines[j]
            if (l.kind != LineKind.NUMBERED || l.indent != lines[i].indent) break
            expected++
            if (l.number != expected) edits += Rep(l.markerStart, l.contentStart - 2, expected.toString())
        }
        return applyAll(text, edits)
    }

    private fun onBackspace(text: String, c: Int, syntaxHidden: Boolean): TextFieldValue? {
        val lines = Markdown.parseLines(text)
        val line = Markdown.lineAt(lines, c)
        if (line.kind == LineKind.CODE || line.kind == LineKind.FENCE) return null
        var target = c
        if (syntaxHidden && c > line.contentStart) {
            // Step back over invisible markers so the key removes the character the user sees before the caret.
            val mask = hiddenMask(text, line)
            while (target > line.contentStart && mask[target - 1 - line.start]) target--
        }
        if (target == line.contentStart && line.hasMarker) {
            return if (line.isList && line.indent >= INDENT) at(text.removeRange(line.start, line.start + INDENT), c - INDENT)
            else at(text.removeRange(line.markerStart, line.contentStart), c - (line.contentStart - line.markerStart))
        }
        if (target == c) return null
        if (target == 0) return at(text, c)
        var out = text.removeRange(target - 1, target)
        var cursor = c - 1
        var p = target - 1
        // Formatting left with nothing inside it goes away too.
        var again = true
        while (again) {
            again = false
            for (type in PAIRED) {
                val (open, close) = markers(type)
                if (p >= open.length && out.startsWith(close, p) && out.startsWith(open, p - open.length)) {
                    out = out.removeRange(p - open.length, p + close.length)
                    cursor -= open.length + close.length
                    p -= open.length
                    again = true
                    break
                }
            }
        }
        return at(out, cursor)
    }

    // ---- Toolbar --------------------------------------------------------------------------------------------

    fun toggleInline(v: TextFieldValue, type: Inline): TextFieldValue {
        val (open, close) = markers(type)
        val text = v.text
        var a = v.selection.min
        var b = v.selection.max
        val lines = Markdown.parseLines(text)
        val line = Markdown.lineAt(lines, a)
        if (line.kind == LineKind.CODE || line.kind == LineKind.FENCE) return v
        b = minOf(b, line.end) // formatting never spans lines
        val spans = Markdown.parseInline(text, line.contentStart, line.end).filter { it.type == type }
        fun unwrap(s: Span, cursorA: Int, cursorB: Int): TextFieldValue {
            val out = text.removeRange(s.innerEnd, s.end).removeRange(s.start, s.innerStart)
            fun map(p: Int) = when {
                p >= s.end -> p - (s.end - s.innerEnd) - (s.innerStart - s.start)
                p >= s.innerEnd -> s.innerEnd - (s.innerStart - s.start)
                p >= s.innerStart -> p - (s.innerStart - s.start)
                else -> minOf(p, s.start)
            }
            return TextFieldValue(out, TextRange(map(cursorA), map(cursorB)))
        }
        if (a == b) {
            if (text.startsWith(close, a) && a >= open.length && text.startsWith(open, a - open.length) &&
                spans.none { a > it.start && a < it.end }
            ) return at(text.removeRange(a - open.length, a + close.length), a - open.length) // empty pair: undo it
            val inside = spans.firstOrNull { a >= it.innerStart && a <= it.innerEnd }
            if (inside != null) return if (a == inside.innerEnd) at(text, inside.end) else unwrap(inside, a, a)
            return at(text.substring(0, a) + open + close + text.substring(a), a + open.length)
        }
        while (a < b && text[a].isWhitespace()) a++
        while (b > a && text[b - 1].isWhitespace()) b--
        if (a == b) return v
        val enclosing = spans.firstOrNull { (it.innerStart <= a && b <= it.innerEnd) || (it.start == a && it.end == b) }
        if (enclosing != null) return unwrap(enclosing, a, b)
        val out = text.substring(0, a) + open + text.substring(a, b) + close + text.substring(b)
        return TextFieldValue(out, TextRange(a + open.length, b + open.length))
    }

    /** Whether the caret / selection currently sits in formatting of [type] – drives the toolbar's highlights. */
    fun isActive(v: TextFieldValue, type: Inline): Boolean {
        val line = Markdown.lineAt(Markdown.parseLines(v.text), v.selection.min)
        if (line.kind == LineKind.CODE || line.kind == LineKind.FENCE) return false
        return Markdown.parseInline(v.text, line.contentStart, line.end)
            .any { it.type == type && v.selection.min >= it.innerStart && minOf(v.selection.max, line.end) <= it.innerEnd }
    }

    fun currentKind(v: TextFieldValue): LineKind = Markdown.lineAt(Markdown.parseLines(v.text), v.selection.min).kind

    private fun selectedLines(v: TextFieldValue): List<Line> =
        Markdown.parseLines(v.text).filter { it.start <= v.selection.max && it.end >= v.selection.min }

    /** Turns the selected lines into [kind]; if they all are already, back into plain body text. */
    fun setLineKind(v: TextFieldValue, kind: LineKind): TextFieldValue {
        val lines = selectedLines(v).filter { it.kind != LineKind.CODE && it.kind != LineKind.FENCE }
        if (lines.isEmpty()) return v
        fun same(l: Line) = l.kind == kind || (kind == LineKind.TODO && l.kind == LineKind.DONE)
        val target = if (kind != LineKind.BODY && lines.all(::same)) LineKind.BODY else kind
        var number = 0
        val reps = lines.map { l ->
            val marker = when (target) {
                LineKind.H1 -> "# "
                LineKind.H2 -> "## "
                LineKind.H3 -> "### "
                LineKind.QUOTE -> "> "
                LineKind.BULLET -> "- "
                LineKind.NUMBERED -> "${++number}. "
                LineKind.TODO -> if (l.kind == LineKind.DONE) "- [x] " else "- [ ] "
                else -> ""
            }
            val heading = target == LineKind.H1 || target == LineKind.H2 || target == LineKind.H3
            Rep(if (heading) l.start else l.markerStart, l.contentStart, marker)
        }
        return replaceAll(v, reps)
    }

    fun indent(v: TextFieldValue, delta: Int): TextFieldValue {
        val reps = selectedLines(v).mapNotNull { l ->
            when {
                l.kind == LineKind.CODE || l.kind == LineKind.FENCE -> null
                delta > 0 && l.indent + INDENT <= MAX_INDENT -> Rep(l.start, l.start, " ".repeat(INDENT))
                delta < 0 && l.indent > 0 -> Rep(l.start, l.start + minOf(INDENT, l.indent), "")
                else -> null
            }
        }
        return replaceAll(v, reps)
    }

    /** Wraps the selected lines in a fenced code block, or removes the fence that is already around them. */
    fun toggleCodeBlock(v: TextFieldValue): TextFieldValue {
        val all = Markdown.parseLines(v.text)
        val first = all.indexOfFirst { it.end >= v.selection.min }
        val last = all.indexOfLast { it.start <= v.selection.max }
        if (first < 0 || last < first) return v
        val text = v.text
        if (all[first].kind == LineKind.CODE || all[first].kind == LineKind.FENCE) {
            var open = first
            while (open >= 0 && all[open].kind != LineKind.FENCE) open--
            if (all[first].kind == LineKind.FENCE && first > 0 && all[first - 1].kind == LineKind.CODE) {
                open = first - 1
                while (open >= 0 && all[open].kind != LineKind.FENCE) open--
            }
            var close = maxOf(open + 1, last)
            while (close < all.size && all[close].kind != LineKind.FENCE) close++
            if (open < 0) return v
            val reps = ArrayList<Rep>()
            reps += Rep(all[open].start, minOf(all[open].end + 1, text.length), "")
            if (close < all.size) reps += Rep(maxOf(all[close].start - 1, all[open].end + 1).coerceAtMost(all[close].start), all[close].end, "")
            return replaceAll(v, reps)
        }
        val a = all[first].start
        val b = all[last].end
        val out = text.substring(0, a) + "```\n" + text.substring(a, b) + "\n```" + text.substring(b)
        return TextFieldValue(out, TextRange(v.selection.start + 4, v.selection.end + 4))
    }

    fun insert(v: TextFieldValue, s: String): TextFieldValue {
        val out = v.text.replaceRange(v.selection.min, v.selection.max, s)
        return at(out, v.selection.min + s.length)
    }

    fun insertLink(v: TextFieldValue, label: String, url: String): TextFieldValue {
        val target = url.trim().let { if (it.contains("://") || it.startsWith("mailto:") || it.startsWith("tel:")) it else "https://$it" }
        return insert(v, "[${label.ifBlank { target }.replace("]", "")}](${target.replace(" ", "%20").replace(")", "%29")})")
    }

    fun toggleCheckbox(text: String, lineStart: Int): String {
        val line = Markdown.parseLines(text).firstOrNull { it.start == lineStart } ?: return text
        val box = line.markerStart + 3
        return when (line.kind) {
            LineKind.TODO -> text.replaceRange(box, box + 1, "x")
            LineKind.DONE -> text.replaceRange(box, box + 1, " ")
            else -> text
        }
    }

    // ---- Replacement plumbing -------------------------------------------------------------------------------

    private class Rep(val start: Int, val end: Int, val text: String)

    private fun applyAll(text: String, reps: List<Rep>): String {
        val sb = StringBuilder(text)
        for (r in reps.sortedByDescending { it.start }) sb.replace(r.start, r.end, r.text)
        return sb.toString()
    }

    private fun replaceAll(v: TextFieldValue, reps: List<Rep>): TextFieldValue {
        if (reps.isEmpty()) return v
        val sorted = reps.sortedBy { it.start }
        fun map(p: Int): Int {
            var shift = 0
            for (r in sorted) {
                if (p >= r.end) shift += r.text.length - (r.end - r.start)
                else if (p > r.start) return r.start + shift + r.text.length
                else break
            }
            return p + shift
        }
        return TextFieldValue(applyAll(v.text, sorted), TextRange(map(v.selection.start), map(v.selection.end)))
    }
}
