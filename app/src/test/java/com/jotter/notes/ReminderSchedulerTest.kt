package com.jotter.notes

import com.jotter.notes.data.Repeat
import com.jotter.notes.reminders.ReminderScheduler.nextOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderSchedulerTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(y: Int, m: Int, d: Int, h: Int = 8) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun oneOffReminderHasNoNextOccurrence() {
        assertNull(nextOccurrence(at(2026, 1, 1), Repeat.NONE, at(2026, 1, 2), zone))
    }

    @Test
    fun dailySkipsMissedDays() {
        assertEquals(at(2026, 1, 6), nextOccurrence(at(2026, 1, 1), Repeat.DAILY, at(2026, 1, 5, 9), zone))
    }

    @Test
    fun dailyKeepsWallClockTimeAcrossDst() {
        // Clocks go forward on 29 March 2026 in Berlin; the reminder must stay at 08:00 local.
        assertEquals(at(2026, 3, 29), nextOccurrence(at(2026, 3, 28), Repeat.DAILY, at(2026, 3, 28), zone))
    }

    @Test
    fun weekly() {
        assertEquals(at(2026, 1, 8), nextOccurrence(at(2026, 1, 1), Repeat.WEEKLY, at(2026, 1, 1), zone))
    }

    @Test
    fun monthlyOnThe31stDoesNotDriftAfterFebruary() {
        val start = at(2026, 1, 31)
        assertEquals(at(2026, 2, 28), nextOccurrence(start, Repeat.MONTHLY, start, zone))
        assertEquals(at(2026, 3, 31), nextOccurrence(start, Repeat.MONTHLY, at(2026, 2, 28), zone))
    }

    @Test
    fun yearly() {
        assertEquals(at(2027, 5, 4), nextOccurrence(at(2026, 5, 4), Repeat.YEARLY, at(2026, 6, 1), zone))
    }
}
