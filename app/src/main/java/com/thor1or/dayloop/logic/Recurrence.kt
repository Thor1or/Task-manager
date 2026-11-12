package com.thor1or.dayloop.logic

import java.time.LocalDate

/**
 * How a task repeats. [mask] is only used by WEEKLY: bit 0 = Monday … bit 6 = Sunday.
 */
data class Recurrence(val kind: Kind, val mask: Int = 0) {
    enum class Kind { DAILY, WEEKDAYS, WEEKLY, MONTHLY }

    fun encode(): String = if (kind == Kind.WEEKLY) "WEEKLY:$mask" else kind.name

    /** True when the rule allows [day]. */
    fun matches(day: LocalDate): Boolean {
        val bit = 1 shl (day.dayOfWeek.value - 1)
        return when (kind) {
            Kind.DAILY, Kind.MONTHLY -> true
            Kind.WEEKDAYS -> day.dayOfWeek.value <= 5
            Kind.WEEKLY -> mask and bit != 0
        }
    }

    /** First occurrence strictly after [day]. */
    fun next(day: LocalDate): LocalDate {
        if (kind == Kind.MONTHLY) return day.plusMonths(1)
        var d = day.plusDays(1)
        repeat(7) {
            if (matches(d)) return d
            d = d.plusDays(1)
        }
        return day.plusDays(7)
    }

    /** [day] itself when it matches, otherwise the next occurrence. */
    fun onOrAfter(day: LocalDate): LocalDate = if (matches(day)) day else next(day)

    fun describe(): String = when (kind) {
        Kind.DAILY -> "every day"
        Kind.WEEKDAYS -> "every weekday"
        Kind.MONTHLY -> "monthly"
        Kind.WEEKLY -> "every " + DAY_ABBR.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(", ")
    }

    companion object {
        val DAY_ABBR = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

        fun decode(s: String?): Recurrence? {
            if (s.isNullOrBlank()) return null
            return if (s.startsWith("WEEKLY:")) {
                Recurrence(Kind.WEEKLY, s.substringAfter(':').toIntOrNull() ?: return null)
            } else {
                runCatching { Recurrence(Kind.valueOf(s)) }.getOrNull()
            }
        }
    }
}
