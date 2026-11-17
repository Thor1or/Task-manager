package com.folio.notes

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.notes.data.AppDatabase
import com.folio.notes.data.Attachments
import com.folio.notes.data.Crypto
import com.folio.notes.data.ExportFormat
import com.folio.notes.data.Exporter
import com.folio.notes.data.Folder
import com.folio.notes.data.NotesRepository
import com.folio.notes.markdown.Markdown
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Export, import, locking and housekeeping against a real (in-memory) database and real files. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportTest {
    private val context = ApplicationProvider.getApplicationContext<FolioApp>()
    private lateinit var repo: NotesRepository
    private lateinit var exporter: Exporter
    private val key = Crypto.deriveKey("pw", Crypto.newSalt())

    @Before fun setUp() {
        repo = NotesRepository(AppDatabase.inMemory(context), Attachments(context))
        exporter = Exporter(repo)
    }

    private fun picture(): String = repo.attachments.add(Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) })
    private fun entries(zip: ByteArray): Map<String, ByteArray> = ZipInputStream(ByteArrayInputStream(zip)).use { z ->
        generateSequence { z.nextEntry }.associate { it.name to z.readBytes() }
    }

    @Test fun backupRoundTrip() = runBlocking {
        val work = repo.createFolder("Work")
        val sub = repo.createFolder("Q4: Plans", work)
        repo.createFolder("Empty")
        val pic = picture()
        val original = repo.attachments.read(pic, null)!!
        repo.save(0, Folder.DEFAULT_ID, "Shopping\n- [ ] Milk #home", null, createdAt = 1_000, modifiedAt = 2_000, pinned = true)
        repo.save(0, sub, "Roadmap\ntext\n![chart](attachment:$pic)\nafter", null)
        repo.save(0, sub, "Roadmap\nsame title, different note", null)
        val secret = repo.save(0, work, "Secret\nhidden body", null)
        repo.lock(secret, key)
        val gone = repo.save(0, work, "Deleted\nnot exported", null)
        repo.trash(listOf(gone))

        // Without the key the locked note is left out – and said to be.
        assertEquals(3 to 1, exporter.exportAll(ByteArrayOutputStream(), null))
        val out = ByteArrayOutputStream()
        assertEquals(4 to 0, exporter.exportAll(out, key))
        val files = entries(out.toByteArray())
        assertEquals(
            setOf("Notes/Shopping.md", "Work/Q4 Plans/Roadmap.md", "Work/Q4 Plans/Roadmap 2.md", "Work/Q4 Plans/attachments/$pic", "Work/Secret.md", "folio.json"),
            files.keys,
        )
        assertEquals("# Shopping\n- [ ] Milk #home\n", String(files["Notes/Shopping.md"]!!))
        assertTrue(String(files["Work/Q4 Plans/Roadmap.md"]!!).contains("![chart](attachments/$pic)"))
        assertArrayEquals(original, files["Work/Q4 Plans/attachments/$pic"])

        // Into an empty app.
        val fresh = NotesRepository(AppDatabase.inMemory(context), Attachments(context))
        val result = Exporter(fresh).importZip(ByteArrayInputStream(out.toByteArray()), key)
        assertEquals(4, result.notes)
        val folders = fresh.folders()
        assertEquals(setOf("Notes", "Work", "Q4: Plans", "Empty"), folders.map { it.name }.toSet())
        assertEquals("Work", folders.first { f -> f.id == folders.first { it.name == "Q4: Plans" }.parentId }.name)
        val notes = fresh.allNotes()
        val shopping = notes.first { it.title == "Shopping" }
        assertEquals(listOf(Folder.DEFAULT_ID, 1_000L, 2_000L, true), listOf(shopping.folderId, shopping.createdAt, shopping.modifiedAt, shopping.pinned))
        val roadmap = notes.first { it.title == "Roadmap" && it.attachments.isNotEmpty() }
        assertTrue(roadmap.attachmentList.single() != pic)
        assertArrayEquals(original, fresh.attachments.read(roadmap.attachmentList.single(), null))
        val imported = notes.first { it.title == "Secret" }
        assertTrue(imported.locked)
        assertFalse(imported.content.contains("hidden"))
        assertEquals("# Secret\nhidden body", fresh.content(imported, key))
    }

    @Test fun singleNoteFormats() = runBlocking {
        val pic = picture()
        val md = "Trip\n## Plan\n- [x] **Flights**\n![map](attachment:$pic)\n| A | B |\n| --- | --- |\n| 1 | 2 |\n" + "Long paragraph of text. ".repeat(400)
        fun export(format: ExportFormat, content: String = md) = ByteArrayOutputStream().also { runBlocking { exporter.export(content, format, null, it) } }.toByteArray()

        assertEquals("Trip.zip", exporter.target("Trip", md, ExportFormat.MARKDOWN).fileName)
        assertEquals(setOf("Trip.md", "attachments/$pic"), entries(export(ExportFormat.MARKDOWN)).keys)
        assertEquals("Plain.md", exporter.target("Plain", "Plain\ntext", ExportFormat.MARKDOWN).fileName)
        assertEquals("# Plain\ntext\n", String(export(ExportFormat.MARKDOWN, "Plain\ntext")))
        assertTrue(String(export(ExportFormat.TEXT)).startsWith("Trip\nPlan\n☑ Flights\nmap\nA\tB\n1\t2\nLong paragraph"))
        val html = String(export(ExportFormat.HTML))
        assertTrue(html.contains("<h1>Trip</h1>") && html.contains("<h2>Plan</h2>") && html.contains("src=\"data:image/png;base64,") && html.contains("<th>A</th>"))
        // Robolectric has no PDF writer, so the page layout is checked rather than the bytes.
        val layout = exporter.pdfLayout(md, null)
        val pages = Exporter.pages(layout, Exporter.PDF_HEIGHT)
        assertTrue(pages.size > 1)
        assertEquals((0 until layout.lineCount).toList(), pages.flatMap { it.toList() })
        assertTrue(pages.all { layout.getLineBottom(it.last) - layout.getLineTop(it.first) <= Exporter.PDF_HEIGHT })
        assertTrue(layout.text.startsWith("Trip\nPlan\n☑  Flights\n\uFFFC\nA   |   B\n1   |   2\nLong paragraph"))
    }

    @Test fun importsLooseMarkdownAndForeignZips() = runBlocking {
        assertNull(exporter.importText("  \n", Folder.DEFAULT_ID))
        val id = exporter.importText("# From elsewhere\r\n\r\n* item\r\n", Folder.DEFAULT_ID)!!
        assertEquals("From elsewhere", repo.note(id)!!.title)
        assertEquals("# From elsewhere\n\n* item", repo.note(id)!!.content)

        val zip = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zip).use { z ->
            fun put(name: String, data: ByteArray) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(data); z.closeEntry() }
            put("Journal/2026/day.md", "Day one\n![](../img/a%20b.png)\n".toByteArray())
            put("Journal/img/a b.png", byteArrayOf(1, 2, 3))
            put("../evil.md", "nope".toByteArray())
            put("readme.txt", "Read me".toByteArray())
        }
        assertEquals(2, exporter.importZip(ByteArrayInputStream(zip.toByteArray()), null).notes)
        val day = repo.allNotes().first { it.title == "Day one" }
        assertEquals(listOf("Journal", "2026"), Exporter.folderPath(repo.folders(), day.folderId))
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.attachments.read(day.attachmentList.single(), null))
    }

    @Test fun lockingEncryptsTextAndPictures_rekey_unlock() = runBlocking {
        val pic = picture()
        val plain = repo.attachments.read(pic, null)!!
        val id = repo.save(0, Folder.DEFAULT_ID, "Diary\nprivate words #tag\n![](attachment:$pic)", null)
        repo.lock(id, key)
        val locked = repo.note(id)!!
        assertEquals("Diary", locked.title)
        assertFalse(locked.content.contains("private"))
        assertTrue(repo.attachments.isEncrypted(pic))
        assertNull(repo.attachments.read(pic, null))
        assertNull(repo.content(locked, null))
        assertNull(repo.content(locked, Crypto.deriveKey("other", Crypto.newSalt())))
        assertArrayEquals(plain, repo.attachments.read(pic, key))

        // Saving without the key must not clobber the note; with it, the new text is stored encrypted.
        repo.save(id, Folder.DEFAULT_ID, "overwritten", null)
        assertEquals("Diary\nprivate words #tag\n![](attachment:$pic)", repo.content(repo.note(id)!!, key))
        repo.save(id, Folder.DEFAULT_ID, "Diary\nmore private words\n![](attachment:$pic)", key)
        assertFalse(repo.note(id)!!.content.contains("private"))

        val newKey = Crypto.deriveKey("new", Crypto.newSalt())
        repo.rekey(key, newKey)
        assertNull(repo.content(repo.note(id)!!, key))
        assertArrayEquals(plain, repo.attachments.read(pic, newKey))
        repo.rekey(newKey, null)
        assertFalse(repo.note(id)!!.locked)
        assertEquals("Diary\nmore private words\n![](attachment:$pic)", repo.note(id)!!.content)
        assertArrayEquals(plain, repo.attachments.read(pic, null))
        assertFalse(repo.hasLockedNotes())
    }

    @Test fun purgeDropsExpiredTrashAndOrphanedPictures() = runBlocking {
        val kept = picture()
        val orphan = picture()
        val old = picture()
        listOf(kept, orphan, old).forEach { repo.attachments.file(it).setLastModified(System.currentTimeMillis() - 3_600_000) }
        repo.save(0, Folder.DEFAULT_ID, "Keep\n![](attachment:$kept)", null)
        val expired = repo.save(0, Folder.DEFAULT_ID, "Old\n![](attachment:$old)", null)
        val recent = repo.save(0, Folder.DEFAULT_ID, "Recent", null)
        repo.trash(listOf(expired, recent))
        repo.purge(now = System.currentTimeMillis() + NotesRepository.TRASH_RETENTION_MS - 60_000)
        assertNotNull(repo.note(expired))
        repo.purge(now = System.currentTimeMillis() + NotesRepository.TRASH_RETENTION_MS + 60_000)
        assertNull(repo.note(expired))
        assertNull(repo.note(recent))
        assertEquals(listOf(true, false, false), listOf(kept, orphan, old).map { repo.attachments.file(it).exists() })
        assertEquals(listOf("Keep"), repo.allNotes().map { it.title })
        assertEquals(setOf<String>(), Markdown.tags("no tags here"))
    }
}
