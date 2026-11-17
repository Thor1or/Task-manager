package com.folio.notes.markdown

/** Renders a note's Markdown as HTML, for the HTML export and for printing. */
object MarkdownHtml {
    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun inline(content: String): String {
        val spans = Markdown.parseInline(content, 0, content.length)
        if (spans.isEmpty()) return escape(content)
        val hidden = BooleanArray(content.length)
        val opens = HashMap<Int, MutableList<Span>>()
        val closes = HashMap<Int, MutableList<Span>>()
        for (s in spans) {
            val a = if (s.type == Inline.TAG) s.start else s.innerStart
            val b = if (s.type == Inline.TAG) s.end else s.innerEnd
            if (s.type != Inline.URL && s.type != Inline.TAG) {
                for (i in s.start until s.innerStart) hidden[i] = true
                for (i in s.innerEnd until s.end) hidden[i] = true
            }
            opens.getOrPut(a) { ArrayList() } += s
            closes.getOrPut(b) { ArrayList() } += s
        }
        fun open(s: Span) = when (s.type) {
            Inline.BOLD -> "<strong>"
            Inline.ITALIC -> "<em>"
            Inline.STRIKE -> "<s>"
            Inline.UNDERLINE -> "<u>"
            Inline.HIGHLIGHT -> "<mark>"
            Inline.CODE -> "<code>"
            Inline.LINK, Inline.URL -> "<a href=\"${escape(s.url.orEmpty())}\">"
            Inline.TAG -> "<span class=\"tag\">"
        }
        fun close(s: Span) = when (s.type) {
            Inline.BOLD -> "</strong>"
            Inline.ITALIC -> "</em>"
            Inline.STRIKE -> "</s>"
            Inline.UNDERLINE -> "</u>"
            Inline.HIGHLIGHT -> "</mark>"
            Inline.CODE -> "</code>"
            Inline.LINK, Inline.URL -> "</a>"
            Inline.TAG -> "</span>"
        }
        return buildString {
            for (i in 0..content.length) {
                closes[i]?.sortedBy { it.end - it.start }?.forEach { append(close(it)) }
                opens[i]?.sortedByDescending { it.end - it.start }?.forEach { append(open(it)) }
                if (i < content.length && !hidden[i]) append(escape(content[i].toString()))
            }
        }
    }

    /** [image] turns an attachment's file name into the `src` to use, or null to leave the picture out. */
    fun body(markdown: String, image: (String) -> String?): String {
        val lines = Markdown.parseLines(markdown)
        val out = StringBuilder()
        fun raw(l: Line) = markdown.substring(l.start, l.end)
        fun content(l: Line) = markdown.substring(l.contentStart, l.end)
        var i = 0
        var titled = false
        while (i < lines.size) {
            val line = lines[i]
            val text = raw(line)
            val picture = if (line.kind == LineKind.CODE || line.kind == LineKind.FENCE) null else Markdown.IMAGE_LINE.find(text)
            when {
                picture != null -> {
                    image(picture.groupValues[2])?.let { out.append("<p><img src=\"${escape(it)}\" alt=\"${escape(picture.groupValues[1])}\"></p>\n") }
                    i++
                }
                line.kind == LineKind.FENCE -> {
                    i++
                    out.append("<pre><code>")
                    while (i < lines.size && lines[i].kind == LineKind.CODE) { out.append(escape(raw(lines[i]))).append('\n'); i++ }
                    out.append("</code></pre>\n")
                    if (i < lines.size) i++ // closing fence
                }
                Blocks.isTableStart(markdown, lines, i) -> {
                    out.append("<table>\n<tr>")
                    Blocks.splitRow(text).forEach { out.append("<th>${inline(it)}</th>") }
                    out.append("</tr>\n")
                    i += 2
                    while (i < lines.size && raw(lines[i]).trimStart().startsWith("|")) {
                        out.append("<tr>")
                        Blocks.splitRow(raw(lines[i])).forEach { out.append("<td>${inline(it)}</td>") }
                        out.append("</tr>\n")
                        i++
                    }
                    out.append("</table>\n")
                }
                line.kind == LineKind.H1 || line.kind == LineKind.H2 || line.kind == LineKind.H3 -> {
                    val level = when (line.kind) { LineKind.H1 -> 1; LineKind.H2 -> 2; else -> 3 }
                    out.append("<h$level>${inline(content(line))}</h$level>\n")
                    titled = true
                    i++
                }
                line.kind == LineKind.RULE -> { out.append("<hr>\n"); i++ }
                line.kind == LineKind.QUOTE -> {
                    val parts = ArrayList<String>()
                    while (i < lines.size && lines[i].kind == LineKind.QUOTE) { parts += inline(content(lines[i])); i++ }
                    out.append("<blockquote>${parts.joinToString("<br>\n")}</blockquote>\n")
                }
                line.isList -> {
                    val stack = ArrayDeque<Pair<Int, String>>()
                    fun tag(l: Line) = if (l.kind == LineKind.NUMBERED) "ol" else "ul"
                    fun push(l: Line) {
                        stack.addLast(l.indent to tag(l))
                        out.append(
                            when {
                                l.kind == LineKind.NUMBERED && l.number != 1 -> "<ol start=\"${l.number}\">"
                                l.kind == LineKind.NUMBERED -> "<ol>"
                                l.kind == LineKind.BULLET -> "<ul>"
                                else -> "<ul class=\"todo\">"
                            }
                        )
                    }
                    while (i < lines.size && lines[i].isList) {
                        val l = lines[i]
                        while (stack.isNotEmpty() && stack.last().first > l.indent) out.append("</li></${stack.removeLast().second}>")
                        if (stack.isNotEmpty() && stack.last().first == l.indent) {
                            if (stack.last().second == tag(l)) out.append("</li>")
                            else { out.append("</li></${stack.removeLast().second}>"); push(l) }
                        } else push(l)
                        out.append('\n')
                        when (l.kind) {
                            LineKind.TODO -> out.append("<li class=\"task\"><input type=\"checkbox\" disabled> ")
                            LineKind.DONE -> out.append("<li class=\"task done\"><input type=\"checkbox\" disabled checked> ")
                            else -> out.append("<li>")
                        }
                        out.append(inline(content(l)))
                        i++
                    }
                    while (stack.isNotEmpty()) out.append("</li></${stack.removeLast().second}>")
                    out.append('\n')
                }
                text.isBlank() -> i++
                else -> {
                    val parts = ArrayList<String>()
                    while (i < lines.size && lines[i].kind == LineKind.BODY && raw(lines[i]).isNotBlank() &&
                        Markdown.IMAGE_LINE.find(raw(lines[i])) == null && !Blocks.isTableStart(markdown, lines, i)
                    ) { parts += inline(raw(lines[i]).trim()); i++ }
                    if (!titled) {
                        // A note's first line is its title even when it carries no heading marker.
                        out.append("<h1>${parts.first()}</h1>\n")
                        if (parts.size > 1) out.append("<p>${parts.drop(1).joinToString("<br>\n")}</p>\n")
                    } else out.append("<p>${parts.joinToString("<br>\n")}</p>\n")
                    titled = true
                }
            }
        }
        return out.toString()
    }

    fun document(title: String, markdown: String, image: (String) -> String?): String = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(title)}</title>
<style>
body { font: 16px/1.5 -apple-system, "Segoe UI", Roboto, sans-serif; color: #1c1c1e; max-width: 720px; margin: 2em auto; padding: 0 1em; }
h1 { font-size: 1.7em; margin: 0 0 .4em; } h2 { font-size: 1.35em; margin: 1em 0 .3em; } h3 { font-size: 1.12em; margin: 1em 0 .2em; }
p { margin: .5em 0; } img { max-width: 100%; border-radius: 8px; }
ul, ol { padding-left: 1.4em; margin: .4em 0; } ul.todo { list-style: none; padding-left: .2em; }
li.done { color: #8e8e93; }
blockquote { margin: .6em 0; padding-left: .8em; border-left: 3px solid #5c6bc0; color: #555; font-style: italic; }
code { font-family: ui-monospace, Menlo, Consolas, monospace; background: #f0f0f3; padding: .1em .3em; border-radius: 4px; font-size: .9em; }
pre { background: #f0f0f3; padding: .8em; border-radius: 8px; overflow-x: auto; } pre code { background: none; padding: 0; }
table { border-collapse: collapse; margin: .6em 0; } th, td { border: 1px solid #c7c7cc; padding: .35em .7em; text-align: left; } th { background: #f2f2f7; }
mark { background: #ffe58f; } a, .tag { color: #3f51b5; } hr { border: none; border-top: 1px solid #c7c7cc; margin: 1em 0; }
</style>
</head>
<body>
${body(markdown, image)}</body>
</html>
"""
}
