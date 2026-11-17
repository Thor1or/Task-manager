package com.folio.notes.data

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.Base64
import com.folio.notes.markdown.Blocks
import com.folio.notes.markdown.Inline
import com.folio.notes.markdown.LineKind
import com.folio.notes.markdown.Markdown
import com.folio.notes.markdown.MarkdownHtml
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    MARKDOWN("Markdown", "md", "text/markdown"),
    TEXT("Plain text", "txt", "text/plain"),
    HTML("Web page", "html", "text/html"),
    PDF("PDF", "pdf", "application/pdf"),
}

/** What a finished export is called and what it is, so it can be saved or shared. */
data class ExportTarget(val fileName: String, val mime: String)

data class ImportResult(val notes: Int, val folders: Int)

/** Turns notes into files other apps understand, and back. */
class Exporter(private val repository: NotesRepository) {
    private val attachments get() = repository.attachments

    /** A Markdown note with pictures travels as a zip, so the pictures come along. */
    fun target(title: String, content: String, format: ExportFormat): ExportTarget {
        val zipped = format == ExportFormat.MARKDOWN && Markdown.attachments(content).isNotEmpty()
        return if (zipped) ExportTarget(fileName(title) + ".zip", "application/zip")
        else ExportTarget(fileName(title) + "." + format.extension, format.mime)
    }

    suspend fun export(content: String, format: ExportFormat, key: SecretKey?, out: OutputStream) = withContext(Dispatchers.IO) {
        val title = Markdown.titleAndPreview(content).first
        when (format) {
            ExportFormat.MARKDOWN -> {
                val files = Markdown.attachments(content)
                if (files.isEmpty()) out.write(portableMarkdown(content).toByteArray())
                else ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry(fileName(title) + ".md"))
                    zip.write(portableMarkdown(content).toByteArray())
                    zip.closeEntry()
                    writeAttachments(zip, "", files, key, HashSet())
                }
            }
            ExportFormat.TEXT -> out.write(Markdown.toPlainText(content).toByteArray())
            ExportFormat.HTML -> out.write(html(title, content, key).toByteArray())
            ExportFormat.PDF -> writePdf(content, key, out)
        }
        out.flush()
    }

    /** A self-contained page: pictures are embedded. */
    fun html(title: String, content: String, key: SecretKey?): String =
        MarkdownHtml.document(title.ifBlank { "Note" }, content) { name ->
            attachments.read(name, key)?.let { "data:${Attachments.mimeType(name)};base64," + Base64.encodeToString(it, Base64.NO_WRAP) }
        }

    private fun writeAttachments(zip: ZipOutputStream, dir: String, files: List<String>, key: SecretKey?, written: MutableSet<String>) {
        for (name in files) {
            val path = "${dir}attachments/$name"
            if (!written.add(path)) continue
            val bytes = attachments.read(name, key) ?: continue
            zip.putNextEntry(ZipEntry(path))
            zip.write(bytes)
            zip.closeEntry()
        }
    }

    // ---- Everything, as a zip of Markdown files -------------------------------------------------------------

    /**
     * Writes every note as `Folder/Sub-folder/Title.md`, pictures beside them, plus `folio.json` holding what
     * Markdown cannot (dates, pins, locks). Locked notes are included only when [key] opens them.
     * Returns how many notes were written and how many locked ones were left out.
     */
    suspend fun exportAll(out: OutputStream, key: SecretKey?): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val folders = repository.folders()
        val paths = folders.associate { it.id to folderPath(folders, it.id) }
        val used = HashSet<String>()
        val written = HashSet<String>()
        val meta = JSONArray()
        var skipped = 0
        ZipOutputStream(out).use { zip ->
            for (note in repository.allNotes().filter { it.deletedAt == null }.sortedBy { it.createdAt }) {
                val content = repository.content(note, key)
                if (content == null) { skipped++; continue }
                val dir = paths[note.folderId].orEmpty().joinToString("") { fileName(it) + "/" }
                var path = dir + fileName(note.title) + ".md"
                var n = 2
                while (!used.add(path.lowercase())) path = dir + fileName(note.title) + " ${n++}.md"
                zip.putNextEntry(ZipEntry(path))
                zip.write(portableMarkdown(content).toByteArray())
                zip.closeEntry()
                writeAttachments(zip, dir, Markdown.attachments(content), key, written)
                meta.put(
                    JSONObject().put("path", path).put("folder", JSONArray(paths[note.folderId].orEmpty())).put("created", note.createdAt).put("modified", note.modifiedAt)
                        .put("pinned", note.pinned).put("locked", note.locked)
                )
            }
            val folderList = JSONArray()
            paths.values.filter { it.isNotEmpty() }.forEach { folderList.put(JSONArray(it)) }
            zip.putNextEntry(ZipEntry(METADATA))
            zip.write(JSONObject().put("app", "Folio").put("version", 1).put("notes", meta).put("folders", folderList).toString(2).toByteArray())
            zip.closeEntry()
        }
        meta.length() to skipped
    }

    /**
     * Reads a zip made by [exportAll] – or any zip of Markdown / text files, whose directories become folders.
     * Notes are added next to what is already there. Notes that were locked are locked again if [key] is given.
     */
    suspend fun importZip(input: InputStream, key: SecretKey?): ImportResult = withContext(Dispatchers.IO) {
        val texts = LinkedHashMap<String, String>()
        val pictures = HashMap<String, String>() // path in the zip -> stored attachment name
        var meta: JSONObject? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.replace('\\', '/').trimStart('/')
                if (entry.isDirectory || path.split('/').any { it == ".." }) continue
                val ext = path.substringAfterLast('.', "").lowercase()
                when {
                    path == METADATA -> meta = runCatching { JSONObject(String(zip.readBytes())) }.getOrNull()
                    ext == "md" || ext == "markdown" || ext == "txt" -> texts[path] = String(zip.readBytes())
                    ext in IMAGE_EXTENSIONS -> pictures[path] = attachments.add(zip.readBytes(), ext)
                }
            }
        }
        val info = HashMap<String, JSONObject>()
        meta?.optJSONArray("notes")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { info[it.getString("path")] = it } }

        val folderIds = HashMap<List<String>, Long>()
        var createdFolders = 0
        suspend fun folderFor(path: List<String>): Long {
            if (path.isEmpty() || path == listOf(Folder.DEFAULT_NAME)) return Folder.DEFAULT_ID
            folderIds[path]?.let { return it }
            val parent = if (path.size > 1) folderFor(path.dropLast(1)) else null
            val existing = repository.folders().firstOrNull { it.parentId == parent && it.name.equals(path.last(), ignoreCase = true) }
            val id = existing?.id ?: repository.createFolder(path.last(), parent).also { createdFolders++ }
            folderIds[path] = id
            return id
        }
        meta?.optJSONArray("folders")?.let { a ->
            for (i in 0 until a.length()) a.getJSONArray(i).let { p -> folderFor(List(p.length()) { p.getString(it) }) }
        }
        var count = 0
        for ((path, text) in texts) {
            val dir = path.substringBeforeLast('/', "")
            val content = localiseImages(text.replace("\r\n", "\n").trimEnd('\n')) { link ->
                pictures[normalise(if (dir.isEmpty()) link else "$dir/$link")]
            }
            if (content.isBlank()) continue
            val m = info[path]
            // File names lose characters a folder name may have; the metadata keeps the real ones.
            val folder = m?.optJSONArray("folder")?.let { a -> List(a.length()) { a.getString(it) } } ?: dir.split('/').filter { it.isNotEmpty() }
            val id = repository.save(
                0, folderFor(folder), content, null,
                createdAt = m?.optLong("created")?.takeIf { it > 0 },
                modifiedAt = m?.optLong("modified")?.takeIf { it > 0 },
                pinned = m?.optBoolean("pinned") == true,
            )
            if (m?.optBoolean("locked") == true && key != null) repository.lock(id, key)
            count++
        }
        ImportResult(count, createdFolders)
    }

    /** Imports one Markdown or text file as a note in [folderId]. */
    suspend fun importText(text: String, folderId: Long): Long? {
        val content = text.replace("\r\n", "\n").trimEnd('\n')
        if (content.isBlank()) return null
        // Pictures cannot come along with a lone file, so their lines stay as ordinary Markdown.
        return repository.save(0, folderId, content, null)
    }

    private fun normalise(path: String): String {
        val out = ArrayList<String>()
        for (part in path.split('/')) when (part) {
            "", "." -> {}
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
            else -> out += part
        }
        return out.joinToString("/")
    }

    // ---- PDF --------------------------------------------------------------------------------------------

    /** The note laid out as one tall column of text, [PDF_WIDTH] points wide. */
    internal fun pdfLayout(content: String, key: SecretKey?): StaticLayout {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK }
        val text = spanned(content, key, PDF_WIDTH, PDF_HEIGHT)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, PDF_WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1.1f).setIncludePad(false).build()
    }

    private fun writePdf(content: String, key: SecretKey?, out: OutputStream) {
        val layout = pdfLayout(content, key)
        val doc = PdfDocument()
        pages(layout, PDF_HEIGHT).forEachIndexed { index, lines ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PDF_WIDTH + 2 * PDF_MARGIN, PDF_HEIGHT + 2 * PDF_MARGIN, index + 1).create())
            val top = layout.getLineTop(lines.first)
            page.canvas.apply {
                save()
                translate(PDF_MARGIN.toFloat(), PDF_MARGIN.toFloat())
                clipRect(0, 0, PDF_WIDTH, layout.getLineBottom(lines.last) - top)
                translate(0f, -top.toFloat())
                layout.draw(this)
                restore()
            }
            doc.finishPage(page)
        }
        doc.writeTo(out)
        doc.close()
    }

    /** The note as styled text for the PDF page: the same reading of the Markdown the editor shows. */
    private fun spanned(content: String, key: SecretKey?, width: Int, maxImageHeight: Int): CharSequence {
        val sb = SpannableStringBuilder()
        val lines = Markdown.parseLines(content)
        fun span(what: Any, from: Int) { if (sb.length > from) sb.setSpan(what, from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        var titled = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val raw = content.substring(line.start, line.end)
            val from = sb.length
            val code = line.kind == LineKind.CODE || line.kind == LineKind.FENCE
            val image = if (code) null else Markdown.IMAGE_LINE.find(raw)
            when {
                image != null -> {
                    val bytes = attachments.read(image.groupValues[2], key)
                    val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    if (bitmap != null) {
                        val scale = minOf(1f, width.toFloat() / bitmap.width, maxImageHeight * 0.8f / bitmap.height)
                        val drawable = BitmapDrawable(null, bitmap).apply { setBounds(0, 0, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()) }
                        sb.append('￼')
                        span(ImageSpan(drawable), from)
                    } else sb.append(image.groupValues[1].ifBlank { "[Image]" })
                }
                !code && Blocks.isTableStart(content, lines, i) -> {
                    var header = true
                    while (i < lines.size && content.substring(lines[i].start, lines[i].end).trimStart().startsWith("|")) {
                        val row = content.substring(lines[i].start, lines[i].end)
                        if (!Blocks.isSeparatorRow(row)) {
                            val rowStart = sb.length
                            Blocks.splitRow(row).forEachIndexed { c, cell -> if (c > 0) sb.append("   |   "); appendInline(sb, cell) }
                            if (header) span(StyleSpan(Typeface.BOLD), rowStart)
                            header = false
                            sb.append('\n')
                        }
                        i++
                    }
                    continue
                }
                line.kind == LineKind.FENCE -> { i++; continue }
                line.kind == LineKind.CODE -> { sb.append(raw); span(TypefaceSpan("monospace"), from) }
                line.kind == LineKind.RULE -> { sb.append("────────────────────"); span(ForegroundColorSpan(Color.GRAY), from) }
                else -> {
                    sb.append(raw, 0, line.indent)
                    when (line.kind) {
                        LineKind.TODO -> sb.append("☐  ")
                        LineKind.DONE -> sb.append("☑  ")
                        LineKind.BULLET -> sb.append("•  ")
                        LineKind.NUMBERED -> sb.append("${line.number}. ")
                        else -> {}
                    }
                    appendInline(sb, content.substring(line.contentStart, line.end))
                    val isTitle = !titled && raw.isNotBlank() && (line.kind == LineKind.BODY || line.kind == LineKind.H1)
                    if (raw.isNotBlank()) titled = true
                    when {
                        isTitle -> { span(RelativeSizeSpan(1.8f), from); span(StyleSpan(Typeface.BOLD), from) }
                        line.kind == LineKind.H1 -> { span(RelativeSizeSpan(1.5f), from); span(StyleSpan(Typeface.BOLD), from) }
                        line.kind == LineKind.H2 -> { span(RelativeSizeSpan(1.35f), from); span(StyleSpan(Typeface.BOLD), from) }
                        line.kind == LineKind.H3 -> { span(RelativeSizeSpan(1.12f), from); span(StyleSpan(Typeface.BOLD), from) }
                        line.kind == LineKind.QUOTE -> { span(StyleSpan(Typeface.ITALIC), from); span(ForegroundColorSpan(Color.DKGRAY), from) }
                        line.kind == LineKind.DONE -> span(ForegroundColorSpan(Color.GRAY), from)
                        else -> {}
                    }
                }
            }
            if (i < lines.size - 1) sb.append('\n')
            i++
        }
        return sb
    }

    private fun appendInline(sb: SpannableStringBuilder, content: String) {
        val spans = Markdown.parseInline(content, 0, content.length)
        val hidden = BooleanArray(content.length)
        for (s in spans) if (s.type != Inline.URL && s.type != Inline.TAG) {
            for (i in s.start until s.innerStart) hidden[i] = true
            for (i in s.innerEnd until s.end) hidden[i] = true
        }
        val base = sb.length
        val map = IntArray(content.length + 1)
        for (i in content.indices) { map[i] = sb.length - base; if (!hidden[i]) sb.append(content[i]) }
        map[content.length] = sb.length - base
        for (s in spans) {
            val a = base + map[if (s.type == Inline.TAG) s.start else s.innerStart]
            val b = base + map[if (s.type == Inline.TAG) s.end else s.innerEnd]
            if (b <= a) continue
            val what: Any = when (s.type) {
                Inline.BOLD -> StyleSpan(Typeface.BOLD)
                Inline.ITALIC -> StyleSpan(Typeface.ITALIC)
                Inline.STRIKE -> StrikethroughSpan()
                Inline.UNDERLINE -> UnderlineSpan()
                Inline.HIGHLIGHT -> BackgroundColorSpan(0xFFFFE58F.toInt())
                Inline.CODE -> TypefaceSpan("monospace")
                Inline.LINK, Inline.URL, Inline.TAG -> ForegroundColorSpan(0xFF3F51B5.toInt())
            }
            sb.setSpan(what, a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    companion object {
        const val METADATA = "folio.json"
        // A4 in PostScript points, with 54pt margins.
        internal const val PDF_MARGIN = 54
        internal const val PDF_WIDTH = 595 - 2 * PDF_MARGIN
        internal const val PDF_HEIGHT = 842 - 2 * PDF_MARGIN

        /** Splits a layout into pages of whole lines, each at most [height] tall (a taller single line gets its own). */
        internal fun pages(layout: Layout, height: Int): List<IntRange> {
            val out = ArrayList<IntRange>()
            var line = 0
            while (line < layout.lineCount) {
                val top = layout.getLineTop(line)
                var last = line
                while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= height) last++
                out += line..last
                line = last + 1
            }
            return out
        }
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
        private val IMAGE_LINK = Regex("""!\[([^\]]*)]\(([^)\s]+)\)""")

        /** A file-system-safe name for a note or folder. */
        fun fileName(title: String): String =
            title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ").replace(Regex("\\s+"), " ").trim().trim('.').take(60).trim().ifEmpty { "Untitled" }

        fun folderPath(folders: List<Folder>, id: Long): List<String> {
            val byId = folders.associateBy { it.id }
            val path = ArrayList<String>()
            var current = byId[id]
            while (current != null && path.size < 32) { path.add(0, current.name); current = current.parentId?.let { byId[it] } }
            return path
        }

        /**
         * Markdown that reads well anywhere: the title becomes a heading and pictures point at an
         * `attachments/` directory beside the file.
         */
        fun portableMarkdown(content: String): String {
            val lines = Markdown.parseLines(content)
            val first = lines.firstOrNull { it.end > it.start && content.substring(it.start, it.end).isNotBlank() }
            val titled = if (first != null && first.kind == LineKind.BODY && Markdown.IMAGE_LINE.find(content.substring(first.start, first.end)) == null)
                content.substring(0, first.start) + "# " + content.substring(first.start + first.indent) else content
            return titled.replace("](${Markdown.ATTACHMENT_SCHEME}", "](attachments/") + "\n"
        }

        /** The reverse for pictures: links that [resolve] to an imported file become attachments on their own line. */
        fun localiseImages(markdown: String, resolve: (String) -> String?): String =
            markdown.lineSequence().joinToString("\n") { line ->
                val m = IMAGE_LINK.find(line.trim())
                val name = m?.takeIf { it.range.first == 0 && it.range.last == line.trim().length - 1 }
                    ?.let { resolve(java.net.URLDecoder.decode(it.groupValues[2], "UTF-8")) }
                if (name != null) "![${m.groupValues[1]}](${Markdown.ATTACHMENT_SCHEME}$name)" else line
            }
    }
}
