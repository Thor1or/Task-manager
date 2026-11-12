package com.thor1or.dayloop.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Everything in one JSON file, for export, import and the automatic daily copy. */
object Backup {
    private fun <T> arr(items: List<T>, f: (T) -> JSONObject) = JSONArray().apply { items.forEach { put(f(it)) } }
    private fun JSONObject.longOrNull(k: String) = if (isNull(k)) null else getLong(k)
    private fun JSONObject.intOrNull(k: String) = if (isNull(k)) null else getInt(k)
    private fun JSONObject.strOrNull(k: String) = if (isNull(k)) null else getString(k)
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    suspend fun toJson(dao: AppDao): String = JSONObject().apply {
        put("version", 1)
        put("groups", arr(dao.groupsNow()) { JSONObject().put("id", it.id).put("name", it.name).put("color", it.color) })
        put("tasks", arr(dao.tasksNow()) {
            JSONObject().put("id", it.id).put("title", it.title).put("notes", it.notes)
                .put("groupId", it.groupId ?: JSONObject.NULL).put("dueDay", it.dueDay ?: JSONObject.NULL)
                .put("dueMinute", it.dueMinute ?: JSONObject.NULL).put("durationMin", it.durationMin ?: JSONObject.NULL)
                .put("recurrence", it.recurrence ?: JSONObject.NULL).put("someday", it.someday)
                .put("done", it.done).put("doneAt", it.doneAt ?: JSONObject.NULL).put("createdAt", it.createdAt)
        })
        put("habits", arr(dao.habitsNow()) {
            JSONObject().put("id", it.id).put("name", it.name).put("groupId", it.groupId ?: JSONObject.NULL)
                .put("daysMask", it.daysMask).put("reminderMinute", it.reminderMinute ?: JSONObject.NULL)
                .put("targetMin", it.targetMin).put("planMin", it.planMin).put("archived", it.archived)
                .put("createdDay", it.createdDay)
        })
        put("checks", arr(dao.checksNow()) { JSONObject().put("habitId", it.habitId).put("day", it.day) })
        put("entries", arr(dao.entriesNow()) {
            JSONObject().put("id", it.id).put("title", it.title).put("taskId", it.taskId ?: JSONObject.NULL)
                .put("habitId", it.habitId ?: JSONObject.NULL).put("groupId", it.groupId ?: JSONObject.NULL)
                .put("blockId", it.blockId ?: JSONObject.NULL).put("startMs", it.startMs)
                .put("endMs", it.endMs ?: JSONObject.NULL)
        })
        put("blocks", arr(dao.allBlocksNow()) {
            JSONObject().put("id", it.id).put("day", it.day).put("position", it.position).put("title", it.title)
                .put("taskId", it.taskId ?: JSONObject.NULL).put("habitId", it.habitId ?: JSONObject.NULL)
                .put("groupId", it.groupId ?: JSONObject.NULL).put("desiredMin", it.desiredMin)
                .put("fixedStart", it.fixedStart ?: JSONObject.NULL).put("rigid", it.rigid)
                .put("actualStart", it.actualStart ?: JSONObject.NULL).put("actualEnd", it.actualEnd ?: JSONObject.NULL)
        })
    }.toString(1)

    /** Replaces all data with the contents of [json]. Throws when the file is not a backup. */
    suspend fun restore(dao: AppDao, json: String) {
        val root = JSONObject(json)
        require(root.has("tasks") && root.has("habits")) { "Not a Dayloop backup" }
        // Read everything before touching the database, so a broken file changes nothing.
        val groups = root.getJSONArray("groups").objects().map { GroupEntity(it.getLong("id"), it.getString("name"), it.getInt("color")) }
        val tasks = root.getJSONArray("tasks").objects().map {
            Task(
                it.getLong("id"), it.getString("title"), it.optString("notes"), it.longOrNull("groupId"),
                it.longOrNull("dueDay"), it.intOrNull("dueMinute"), it.intOrNull("durationMin"),
                it.strOrNull("recurrence"), it.optBoolean("someday"), it.optBoolean("done"),
                it.longOrNull("doneAt"), it.getLong("createdAt"),
            )
        }
        val habits = root.getJSONArray("habits").objects().map {
            Habit(
                it.getLong("id"), it.getString("name"), it.longOrNull("groupId"), it.getInt("daysMask"),
                it.intOrNull("reminderMinute"), it.optInt("targetMin"), it.optInt("planMin", 30),
                it.optBoolean("archived"), it.getLong("createdDay"),
            )
        }
        val checks = root.getJSONArray("checks").objects().map { HabitCheck(it.getLong("habitId"), it.getLong("day")) }
        val entries = root.getJSONArray("entries").objects().map {
            TimeEntry(
                it.getLong("id"), it.getString("title"), it.longOrNull("taskId"), it.longOrNull("habitId"),
                it.longOrNull("groupId"), it.longOrNull("blockId"), it.getLong("startMs"), it.longOrNull("endMs"),
            )
        }
        val blocks = root.getJSONArray("blocks").objects().map {
            PlanBlock(
                it.getLong("id"), it.getLong("day"), it.getInt("position"), it.getString("title"),
                it.longOrNull("taskId"), it.longOrNull("habitId"), it.longOrNull("groupId"),
                it.getInt("desiredMin"), it.intOrNull("fixedStart"), it.optBoolean("rigid"),
                it.intOrNull("actualStart"), it.intOrNull("actualEnd"),
            )
        }
        dao.wipeGroups(); dao.wipeTasks(); dao.wipeHabits(); dao.wipeChecks(); dao.wipeEntries(); dao.wipeBlocks()
        groups.forEach { dao.putGroup(it) }
        tasks.forEach { dao.putTask(it) }
        habits.forEach { dao.putHabit(it) }
        checks.forEach { dao.check(it) }
        entries.forEach { dao.putEntry(it) }
        blocks.forEach { dao.putBlock(it) }
    }

    suspend fun write(context: Context, dao: AppDao, uri: Uri) {
        val json = toJson(dao)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
    }

    fun read(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }

    /** Once a day, writes a dated copy into the folder chosen in Settings. */
    suspend fun autoBackup(context: Context, dao: AppDao, prefs: Prefs) {
        val s = prefs.settings.value
        val folder = s.backupFolder ?: return
        val today = LocalDate.now()
        if (s.lastBackupDay == today.toEpochDay()) return
        runCatching {
            val tree = Uri.parse(folder)
            val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val file = DocumentsContract.createDocument(
                context.contentResolver, dir, "application/json", "dayloop-$today.json",
            ) ?: return
            write(context, dao, file)
            prefs.update { it.copy(lastBackupDay = today.toEpochDay()) }
        }
    }
}
