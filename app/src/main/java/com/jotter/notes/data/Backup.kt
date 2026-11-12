package com.jotter.notes.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Export / import of every note as a single JSON document, so data can leave the device without any server. */
class Backup(private val repo: NotesRepository) {

    suspend fun export(): String {
        val notes = JSONArray()
        repo.getAll().forEach { n ->
            val o = JSONObject()
                .put("title", n.note.title)
                .put("content", n.note.content)
                .put("isChecklist", n.note.isChecklist)
                .put("color", n.note.color)
                .put("pinned", n.note.pinned)
                .put("archived", n.note.archived)
                .put("createdAt", n.note.createdAt)
                .put("updatedAt", n.note.updatedAt)
                .put("reminderRepeat", n.note.reminderRepeat)
            n.note.trashedAt?.let { o.put("trashedAt", it) }
            n.note.reminderAt?.let { o.put("reminderAt", it) }
            o.put("items", JSONArray().apply {
                n.sortedItems.forEach { put(JSONObject().put("text", it.text).put("checked", it.checked).put("indent", it.indent)) }
            })
            o.put("labels", JSONArray().apply { n.sortedLabels.forEach { put(it.name) } })
            o.put("images", JSONArray().apply {
                n.sortedImages.forEach { img ->
                    val f = repo.imageFile(img)
                    if (f.exists()) put(Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                }
            })
            notes.put(o)
        }
        return JSONObject().put("app", "jotter").put("version", 1).put("notes", notes).toString()
    }

    /** Adds every note from [json] next to the existing ones. Returns how many were imported. */
    suspend fun import(json: String): Int {
        val root = JSONObject(json)
        require(root.optString("app") == "jotter") { "Not a Jotter backup file" }
        val notes = root.getJSONArray("notes")
        for (i in 0 until notes.length()) {
            val o = notes.getJSONObject(i)
            val items = o.optJSONArray("items") ?: JSONArray()
            val id = repo.save(
                Note(
                    title = o.optString("title"),
                    content = o.optString("content"),
                    isChecklist = o.optBoolean("isChecklist"),
                    color = o.optInt("color"),
                    pinned = o.optBoolean("pinned"),
                    archived = o.optBoolean("archived"),
                    trashedAt = if (o.has("trashedAt")) o.getLong("trashedAt") else null,
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt"),
                ),
                (0 until items.length()).map {
                    val item = items.getJSONObject(it)
                    ChecklistItem(
                        noteId = 0, text = item.optString("text"), checked = item.optBoolean("checked"),
                        indent = item.optInt("indent").coerceIn(0, 1),
                    )
                },
            )
            if (o.has("reminderAt") && !o.has("trashedAt")) {
                repo.setReminder(id, o.getLong("reminderAt"), Repeat.of(o.optString("reminderRepeat")))
            }
            val labels = o.optJSONArray("labels") ?: JSONArray()
            for (j in 0 until labels.length()) {
                repo.createLabel(labels.getString(j))?.let { repo.setLabel(listOf(id), it, true) }
            }
            val images = o.optJSONArray("images") ?: JSONArray()
            for (j in 0 until images.length()) {
                repo.addImageBytes(id, Base64.decode(images.getString(j), Base64.NO_WRAP))
            }
        }
        return notes.length()
    }
}
