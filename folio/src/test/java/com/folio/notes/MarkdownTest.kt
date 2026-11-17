package com.folio.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
import com.folio.notes.data.Exporter
import com.folio.notes.markdown.Blocks
import com.folio.notes.markdown.ImageBlock
import com.folio.notes.markdown.Inline
import com.folio.notes.markdown.LineKind
import com.folio.notes.markdown.Markdown
import com.folio.notes.markdown.MarkdownColors
import com.folio.notes.markdown.MarkdownEditing
import com.folio.notes.markdown.MarkdownHtml
import com.folio.notes.markdown.MarkdownStyler
import com.folio.notes.markdown.SyntaxMode
import com.folio.notes.markdown.TableBlock
import com.folio.notes.markdown.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private fun kinds(s: String) = Markdown.parseLines(s).map { it.kind }
    private fun v(s: String): TextFieldValue {
        // "|" marks the caret, "[" … "]" a selection
        val a = s.indexOf('⟦'); val b = s.indexOf('⟧')
        if (a >= 0) return TextFieldValue(s.replace("⟦", "").replace("⟧", ""), TextRange(a, b - 1))
        val c = s.indexOf('¦')
        return TextFieldValue(s.replace("¦", ""), TextRange(c))
    }
    private fun show(v: TextFieldValue) =
        if (v.selection.collapsed) v.text.substring(0, v.selection.start) + "¦" + v.text.substring(v.selection.start)
        else v.text.substring(0, v.selection.min) + "⟦" + v.text.substring(v.selection.min, v.selection.max) + "⟧" + v.text.substring(v.selection.max)
    private fun type(s: String, ch: String, hidden: Boolean = true): String {
        val old = v(s)
        val c = old.selection.start
        return show(MarkdownEditing.onEdit(old, TextFieldValue(old.text.substring(0, c) + ch + old.text.substring(c), TextRange(c + ch.length)), hidden))
    }
    private fun backspace(s: String, hidden: Boolean = true): String {
        val old = v(s)
        val c = old.selection.start
        return show(MarkdownEditing.onEdit(old, TextFieldValue(old.text.removeRange(c - 1, c), TextRange(c - 1)), hidden))
    }

    @Test fun classifiesLines() {
        assertEquals(
            listOf(LineKind.H1, LineKind.H2, LineKind.H3, LineKind.BODY, LineKind.BULLET, LineKind.TODO, LineKind.DONE,
                LineKind.NUMBERED, LineKind.QUOTE, LineKind.RULE, LineKind.FENCE, LineKind.CODE, LineKind.FENCE, LineKind.BODY),
            kinds("# a\n## b\n### c\n#nope\n- x\n- [ ] t\n- [x] d\n12. n\n> q\n---\n```\n# code\n```\n-not"),
        )
        val todo = Markdown.parseLines("  - [ ] task")[0]
        assertEquals(2, todo.indent); assertEquals(8, todo.contentStart)
    }

    @Test fun parsesInline() {
        fun types(s: String) = Markdown.parseInline(s, 0, s.length).map { it.type to s.substring(it.innerStart, it.innerEnd) }
        assertEquals(listOf(Inline.BOLD to "b", Inline.ITALIC to "i"), types("a **b** and *i*"))
        assertEquals(listOf(Inline.BOLD to "*x*", Inline.ITALIC to "x"), types("***x***"))
        assertEquals(listOf(Inline.CODE to "a*b*"), types("`a*b*`"))
        assertEquals(listOf(Inline.LINK to "site"), types("[site](https://x.y/a_b_c)"))
        assertEquals(listOf(Inline.URL to "https://x.y/a_b"), types("see https://x.y/a_b."))
        assertEquals(listOf(Inline.TAG to "work", Inline.TAG to "idea-2"), types("#work and #idea-2 not#this #123"))
        assertEquals(listOf(Inline.STRIKE to "s", Inline.UNDERLINE to "u", Inline.HIGHLIGHT to "h"), types("~~s~~ <u>u</u> ==h=="))
        assertEquals(emptyList<Any>(), types("2 * 3 * 4 and snake_case_name"))
    }

    @Test fun derivesTitlePreviewTagsAndPlainText() {
        val md = "# Trip **plan**\n\n- [ ] Book [flights](https://air.example)\n- [x] Hotel #travel\n\n| A | B |\n| --- | --- |\n| 1 | 2 |"
        assertEquals("Trip plan" to "☐ Book flights\n☑ Hotel #travel\nA\tB\n1\t2", Markdown.titleAndPreview(md))
        assertEquals(setOf("travel"), Markdown.tags(md))
        assertTrue(Markdown.toPlainText(md).contains("Book flights (https://air.example)"))
        assertEquals(10, Markdown.wordCount(md))
    }

    @Test fun enterContinuesListsAndLeavesThem() {
        assertEquals("- [x] a\n- [ ] ¦", type("- [x] a¦", "\n"))
        assertEquals("- a\n- ¦b", type("- a¦b", "\n"))
        assertEquals("1. a\n2. ¦\n3. b", type("1. a¦\n2. b", "\n"))
        assertEquals("  - a\n  - ¦", type("  - a¦", "\n"))
        assertEquals("- a\n¦", type("- a\n- ¦", "\n"))
        assertEquals("- a\n- ¦", type("- a\n  - ¦", "\n"))
        assertEquals("> q\n> ¦", type("> q¦", "\n"))
        assertEquals("# T\n¦", type("# T¦", "\n"))
    }

    @Test fun backspaceRemovesMarkersAndSeesThroughHiddenSyntax() {
        assertEquals("¦task", backspace("- [ ] ¦task"))
        assertEquals("- ¦a", backspace("  - ¦a"))
        assertEquals("¦Title", backspace("# ¦Title"))
        assertEquals("**bol**¦ x", backspace("**bold**¦ x"))
        assertEquals("a¦", backspace("a**b**¦"))
        assertEquals("**bold*¦ x", backspace("**bold**¦ x", hidden = false))
        assertEquals("a**¦b**", backspace("a\n**¦b**"))
        assertEquals("**¦b**", backspace("**¦b**"))
        assertEquals("ab¦", backspace("abc¦"))
    }

    @Test fun toolbarTogglesInlineFormatting() {
        fun bold(s: String) = show(MarkdownEditing.toggleInline(v(s), Inline.BOLD))
        assertEquals("a **⟦word⟧** b", bold("a ⟦word⟧ b"))
        assertEquals("a ⟦word⟧ b", bold("a **⟦word⟧** b"))
        assertEquals("a **¦** b", bold("a ¦ b"))
        assertEquals("a ¦ b", bold("a **¦** b"))
        assertEquals("a **word**¦ b", bold("a **word¦** b"))
        assertEquals("a wo¦rd b", bold("a **wo¦rd** b"))
        assertEquals("**⟦a⟧** \nb", bold("⟦a \nb⟧"))
        assertEquals("<u>⟦x⟧</u>", show(MarkdownEditing.toggleInline(v("⟦x⟧"), Inline.UNDERLINE)))
        assertTrue(MarkdownEditing.isActive(v("a **wo¦rd**"), Inline.BOLD))
        assertFalse(MarkdownEditing.isActive(v("a¦ **word**"), Inline.BOLD))
    }

    @Test fun toolbarChangesLineKinds() {
        fun kind(s: String, k: LineKind) = show(MarkdownEditing.setLineKind(v(s), k))
        assertEquals("- [ ] ¦", kind("¦", LineKind.TODO))
        assertEquals("- [ ] mi¦lk", kind("mi¦lk", LineKind.TODO))
        assertEquals("mi¦lk", kind("- [x] mi¦lk", LineKind.TODO))
        assertEquals("⟦1. a\n2. b⟧", kind("⟦- a\n- b⟧", LineKind.NUMBERED))
        assertEquals("## he¦ad", kind("  - he¦ad", LineKind.H2))
        assertEquals("he¦ad", kind("## he¦ad", LineKind.BODY))
        assertEquals("  - a¦", show(MarkdownEditing.indent(v("- a¦"), 1)))
        assertEquals("- a¦", show(MarkdownEditing.indent(v("  - a¦"), -1)))
        assertEquals("a\n```\nb¦\n```\nc", show(MarkdownEditing.toggleCodeBlock(v("a\nb¦\nc"))))
        assertEquals("a\nb¦\nc", show(MarkdownEditing.toggleCodeBlock(v("a\n```\nb¦\n```\nc"))))
        assertEquals("- [x] a\n- [ ] b", MarkdownEditing.toggleCheckbox("- [ ] a\n- [ ] b", 0))
        assertEquals("- [ ] a\n- [ ] b", MarkdownEditing.toggleCheckbox("- [ ] a\n- [x] b", 8))
        assertEquals("see [docs](https://a.b/c%20d)¦", show(MarkdownEditing.insertLink(v("see ¦"), "docs", "a.b/c d")))
    }

    @Test fun blocksRoundTrip() {
        val md = "Title\ntext\n![cat](attachment:a1.png)\n| A | B |\n| --- | --- |\n| 1 | x\\|y |\nend\n```\n| not | table |\n| --- | --- |\n```"
        val blocks = Blocks.parse(md)
        assertEquals(listOf("T", "I", "T", "B", "T"), blocks.map { when (it) { is TextBlock -> "T"; is ImageBlock -> "I"; is TableBlock -> "B" } })
        assertEquals(listOf(listOf("A", "B"), listOf("1", "x|y")), (blocks[3] as TableBlock).rows)
        val again = Blocks.serialize(blocks)
        assertEquals(again, Blocks.serialize(Blocks.parse(again)))
        assertEquals("Title\ntext\n![cat](attachment:a1.png)\n\n| A | B |\n| --- | --- |\n| 1 | x\\|y |\nend\n```\n| not | table |\n| --- | --- |\n```", again)
        assertEquals(1, Blocks.parse("").size)
        assertEquals(listOf(listOf("a", "", "c"), listOf("", "", "")), (Blocks.parse("| a |  | c |\n|---|---|---|\n| | | |")[1] as TableBlock).rows)
    }

    @Test fun stylerHidesSyntaxAndMapsOffsets() {
        val colors = MarkdownColors(Color.Black, Color.Gray, Color.Blue, Color.LightGray, Color.Yellow)
        val src = "# Title\n- [x] **done** item\n- bullet [link](http://x.y)\n1. one ==hi=="
        val hidden = MarkdownStyler.style(src, colors, 16.sp, true, SyntaxMode.HIDE)
        assertEquals("Title\n  done item\n•  bullet link\n1. one hi", hidden.text.text)
        assertEquals(1, hidden.checkboxes.size)
        assertTrue(hidden.checkboxes[0].checked)
        assertEquals(8, hidden.checkboxes[0].lineStart)
        assertEquals("link", hidden.text.text.substring(hidden.links[0].start, hidden.links[0].end))
        // Every offset maps inside the other text, in order, both ways.
        var last = 0
        for (i in 0..src.length) { val t = hidden.mapping.originalToTransformed(i); assertTrue(t >= last && t <= hidden.text.length); last = t }
        last = 0
        for (i in 0..hidden.text.length) { val o = hidden.mapping.transformedToOriginal(i); assertTrue(o >= last && o <= src.length); last = o }
        assertEquals(2, hidden.mapping.transformedToOriginal(0)) // caret at the start of the title sits after "# "
        assertEquals(src, MarkdownStyler.style(src, colors, 16.sp, true, SyntaxMode.SHOW).text.text)
        val current = MarkdownStyler.style(src, colors, 16.sp, true, SyntaxMode.CURRENT_LINE, 10..10)
        assertEquals("Title\n- [x] **done** item\n•  bullet link\n1. one hi", current.text.text)
    }

    @Test fun rendersHtml() {
        val html = MarkdownHtml.body("Title\nline **b** <tag>\n\n- a\n  - b\n- c\n1. x\n- [x] done\n> q\n\n| A |\n| --- |\n| *1* |\n```\n<x>\n```\n![p](attachment:f.png)") { "img/$it" }
        assertEquals(
            "<h1>Title</h1>\n<p>line <strong>b</strong> &lt;tag&gt;</p>\n" +
                "<ul>\n<li>a<ul>\n<li>b</li></ul></li>\n<li>c</li></ul><ol>\n<li>x</li></ol><ul class=\"todo\">\n<li class=\"task done\"><input type=\"checkbox\" disabled checked> done</li></ul>\n" +
                "<blockquote>q</blockquote>\n<table>\n<tr><th>A</th></tr>\n<tr><td><em>1</em></td></tr>\n</table>\n" +
                "<pre><code>&lt;x&gt;\n</code></pre>\n<p><img src=\"img/f.png\" alt=\"p\"></p>\n",
            html,
        )
    }

    @Test fun exportHelpers() {
        assertEquals("# Title\n![a](attachments/x.png)\n", Exporter.portableMarkdown("Title\n![a](attachment:x.png)"))
        assertEquals("# Already\n", Exporter.portableMarkdown("# Already"))
        assertEquals("a b c", Exporter.fileName("a/b:c"))
        assertEquals("Untitled", Exporter.fileName(" .. "))
        assertEquals("t\n![a](attachment:new.png)\n![b](http://x/y.png)", Exporter.localiseImages("t\n![a](attachments/x.png)\n![b](http://x/y.png)") { if (it == "attachments/x.png") "new.png" else null })
    }
}
