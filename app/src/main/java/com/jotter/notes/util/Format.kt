package com.jotter.notes.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object Format {
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dayMonth = DateTimeFormatter.ofPattern("MMM d")
    private val full = DateTimeFormatter.ofPattern("MMM d, yyyy")

    /** "Today, 8:00 AM", "Tomorrow, 8:00 AM", "Mar 4, 8:00 AM", "Mar 4, 2027, 8:00 AM". */
    fun reminder(at: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val dt = Instant.ofEpochMilli(at).atZone(zone)
        return "${day(dt.toLocalDate(), Instant.ofEpochMilli(now).atZone(zone).toLocalDate())}, ${time.format(dt)}"
    }

    /** "8:00 AM" for today, otherwise a date. */
    fun edited(at: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val dt = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (dt.toLocalDate() == today) time.format(dt) else day(dt.toLocalDate(), today)
    }

    fun day(date: LocalDate, today: LocalDate): String = when {
        date == today -> "Today"
        date == today.plusDays(1) -> "Tomorrow"
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> dayMonth.format(date)
        else -> full.format(date)
    }
}
