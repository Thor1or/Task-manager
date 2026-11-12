package com.thor1or.dayloop.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // Groups
    @Query("SELECT * FROM groups ORDER BY name") fun groups(): Flow<List<GroupEntity>>
    @Query("SELECT * FROM groups") suspend fun groupsNow(): List<GroupEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putGroup(g: GroupEntity): Long
    @Delete suspend fun deleteGroup(g: GroupEntity)
    @Query("UPDATE tasks SET groupId = NULL WHERE groupId = :id") suspend fun clearGroupFromTasks(id: Long)
    @Query("UPDATE habits SET groupId = NULL WHERE groupId = :id") suspend fun clearGroupFromHabits(id: Long)
    @Query("UPDATE time_entries SET groupId = NULL WHERE groupId = :id") suspend fun clearGroupFromEntries(id: Long)
    @Query("UPDATE plan_blocks SET groupId = NULL WHERE groupId = :id") suspend fun clearGroupFromBlocks(id: Long)

    // Tasks
    @Query("SELECT * FROM tasks ORDER BY done, dueDay IS NULL, dueDay, dueMinute IS NULL, dueMinute, id")
    fun tasks(): Flow<List<Task>>
    @Query("SELECT * FROM tasks") suspend fun tasksNow(): List<Task>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun task(id: Long): Task?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTask(t: Task): Long
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun deleteTask(id: Long)
    @Query("DELETE FROM tasks WHERE done = 1") suspend fun deleteDoneTasks()

    // Habits
    @Query("SELECT * FROM habits WHERE archived = 0 ORDER BY reminderMinute IS NULL, reminderMinute, id")
    fun habits(): Flow<List<Habit>>
    @Query("SELECT * FROM habits") suspend fun habitsNow(): List<Habit>
    @Query("SELECT * FROM habits WHERE id = :id") suspend fun habit(id: Long): Habit?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putHabit(h: Habit): Long
    @Query("DELETE FROM habits WHERE id = :id") suspend fun deleteHabit(id: Long)
    @Query("SELECT * FROM habit_checks") fun checks(): Flow<List<HabitCheck>>
    @Query("SELECT * FROM habit_checks") suspend fun checksNow(): List<HabitCheck>
    @Query("SELECT COUNT(*) FROM habit_checks WHERE habitId = :habitId AND day = :day")
    suspend fun isChecked(habitId: Long, day: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun check(c: HabitCheck)
    @Query("DELETE FROM habit_checks WHERE habitId = :habitId AND day = :day")
    suspend fun uncheck(habitId: Long, day: Long)
    @Query("DELETE FROM habit_checks WHERE habitId = :habitId") suspend fun deleteChecks(habitId: Long)

    // Time entries
    @Query("SELECT * FROM time_entries WHERE endMs IS NULL ORDER BY startMs DESC LIMIT 1")
    fun running(): Flow<TimeEntry?>
    @Query("SELECT * FROM time_entries WHERE endMs IS NULL ORDER BY startMs DESC LIMIT 1")
    suspend fun runningNow(): TimeEntry?
    @Query("SELECT * FROM time_entries WHERE startMs >= :sinceMs ORDER BY startMs DESC")
    fun entriesSince(sinceMs: Long): Flow<List<TimeEntry>>
    @Query("SELECT * FROM time_entries") suspend fun entriesNow(): List<TimeEntry>
    @Query("SELECT * FROM time_entries WHERE habitId = :habitId AND startMs >= :fromMs AND startMs < :toMs")
    suspend fun habitEntries(habitId: Long, fromMs: Long, toMs: Long): List<TimeEntry>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEntry(e: TimeEntry): Long
    @Query("DELETE FROM time_entries WHERE id = :id") suspend fun deleteEntry(id: Long)

    // Plan
    @Query("SELECT * FROM plan_blocks WHERE day = :day ORDER BY position, id")
    fun blocks(day: Long): Flow<List<PlanBlock>>
    @Query("SELECT * FROM plan_blocks WHERE day = :day ORDER BY position, id")
    suspend fun blocksNow(day: Long): List<PlanBlock>
    @Query("SELECT * FROM plan_blocks WHERE day >= :fromDay AND actualStart IS NOT NULL AND actualEnd IS NOT NULL")
    fun finishedBlocksSince(fromDay: Long): Flow<List<PlanBlock>>
    @Query("SELECT * FROM plan_blocks") suspend fun allBlocksNow(): List<PlanBlock>
    @Query("SELECT * FROM plan_blocks WHERE id = :id") suspend fun block(id: Long): PlanBlock?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBlock(b: PlanBlock): Long
    @Update suspend fun updateBlocks(b: List<PlanBlock>)
    @Query("DELETE FROM plan_blocks WHERE id = :id") suspend fun deleteBlock(id: Long)
    @Query("DELETE FROM plan_blocks WHERE day = :day") suspend fun deleteBlocks(day: Long)

    // Restore
    @Query("DELETE FROM groups") suspend fun wipeGroups()
    @Query("DELETE FROM tasks") suspend fun wipeTasks()
    @Query("DELETE FROM habits") suspend fun wipeHabits()
    @Query("DELETE FROM habit_checks") suspend fun wipeChecks()
    @Query("DELETE FROM time_entries") suspend fun wipeEntries()
    @Query("DELETE FROM plan_blocks") suspend fun wipeBlocks()
}

@Database(
    entities = [GroupEntity::class, Task::class, Habit::class, HabitCheck::class, TimeEntry::class, PlanBlock::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        fun open(context: Context): AppDb =
            Room.databaseBuilder(context.applicationContext, AppDb::class.java, "dayloop.db").build()
    }
}
