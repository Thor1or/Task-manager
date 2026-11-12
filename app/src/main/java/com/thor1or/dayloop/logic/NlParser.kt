package com.thor1or.dayloop.logic

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

enum class DateOrder { DMY, MDY }

/** What was understood from one line of free text. */
data class Parsed(
    val title: String,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val durationMin: Int? = null,
    val group: String? = null,
    val recurrence: Recurrence? = null,
    /** Character ranges of the input that were understood as something other than the title. */
    val spans: List<IntRange> = emptyList(),
)

/**
 * Reads lines such as "buy bread at 9am on 10/12", "call mom tomorrow 14:30 for 20m #family"
 * or "gym every mon, wed 7am". Works offline; English, with the common Portuguese words.
 */
object NlParser {
    // Word boundaries that also work next to accented letters.
    private const val L = "(?<![\\p{L}\\p{N}])"
    private const val R = "(?![\\p{L}\\p{N}])"

    private val DAYS: List<Pair<String, Int>> = listOf(
        "monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4, "friday" to 5,
        "saturday" to 6, "sunday" to 7,
        "segunda-feira" to 1, "terça-feira" to 2, "terca-feira" to 2, "quarta-feira" to 3,
        "quinta-feira" to 4, "sexta-feira" to 5,
        "segunda" to 1, "terça" to 2, "terca" to 2, "quarta" to 3, "quinta" to 4, "sexta" to 5,
        "sábado" to 6, "sabado" to 6, "domingo" to 7,
    )
    private val DAY_ABBR: List<Pair<String, Int>> = listOf(
        "mon" to 1, "tues" to 2, "tue" to 2, "wed" to 3, "thurs" to 4, "thur" to 4, "thu" to 4,
        "fri" to 5, "sat" to 6, "sun" to 7,
    )
    private val MONTHS: List<Pair<String, Int>> = listOf(
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6,
        "july" to 7, "august" to 8, "september" to 9, "october" to 10, "november" to 11,
        "december" to 12,
        "janeiro" to 1, "fevereiro" to 2, "março" to 3, "marco" to 3, "abril" to 4, "maio" to 5,
        "junho" to 6, "julho" to 7, "agosto" to 8, "setembro" to 9, "outubro" to 10,
        "novembro" to 11, "dezembro" to 12,
        "jan" to 1, "feb" to 2, "fev" to 2, "mar" to 3, "apr" to 4, "abr" to 4, "jun" to 6,
        "jul" to 7, "aug" to 8, "ago" to 8, "sept" to 9, "sep" to 9, "set" to 9, "oct" to 10,
        "out" to 10, "nov" to 11, "dec" to 12, "dez" to 12,
    )

    private fun alt(names: List<Pair<String, Int>>) =
        names.map { it.first }.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    private val dayFull = alt(DAYS)
    private val dayAny = alt(DAYS + DAY_ABBR)
    private val monthAny = alt(MONTHS)

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val groupRx = rx("(?<!\\S)#([\\p{L}\\p{N}_-]+)")
    private val dailyRx = rx("$L(?:every\\s?day|daily|todo dia|todos os dias)$R")
    private val weekdaysRx = rx("$L(?:every\\s+weekdays?|dias úteis|dias uteis)$R")
    private val everyDaysRx = rx("$L(?:every|toda|todo)\\s+((?:$dayAny)(?:\\s*(?:,|and|e|&)\\s*(?:$dayAny))*)$R")
    private val weeklyRx = rx("$L(?:weekly|every week|toda semana)$R")
    private val monthlyRx = rx("$L(?:monthly|every month|todo mês|todo mes)$R")

    private const val HOURS = "h|hr|hrs|hour|hours|hora|horas"
    private const val MINS = "m|min|mins|minute|minutes|minuto|minutos"
    private val durHmRx = rx("$L(?:(?:for|por)\\s+)?(\\d{1,2})\\s*h\\s*(\\d{1,2})\\s*(?:m|min)$R")
    private val durForRx = rx("$L(?:for|por)\\s+(\\d+(?:[.,]\\d+)?)\\s*($HOURS|$MINS)$R")
    private val durMinRx = rx("$L(\\d+)\\s*(?:$MINS)$R")
    private val durHourRx = rx("$L(\\d+(?:[.,]\\d+)?)\\s*(?:$HOURS)$R")

    private val noonRx = rx("$L(?:(?:at|ao)\\s+)?(noon|midday|meio-dia|midnight|meia-noite)$R")
    private val ampmRx = rx("$L(?:(?:at|às)\\s+)?(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)(?![\\p{L}\\p{N}])")
    private val atRx = rx("$L(?:at|às)\\s+(\\d{1,2})(?:(?::|h)(\\d{2}))?h?$R")
    private val clockRx = rx("$L(\\d{1,2})(?::|h)(\\d{2})$R")

    private val todayRx = rx("$L(?:today|hoje)$R")
    private val tomorrowRx = rx("$L(?:tomorrow|tmrw|tmr|amanhã|amanha)$R")
    private val inRx = rx("$L(?:in|em)\\s+(\\d+)\\s+(day|days|dia|dias|week|weeks|semana|semanas|month|months|mês|mes|meses)$R")
    private val nextWeekRx = rx("${L}next\\s+week$R")
    private val nextMonthRx = rx("${L}next\\s+month$R")
    private val isoRx = rx("$L(?:on\\s+)?(\\d{4})-(\\d{1,2})-(\\d{1,2})$R")
    private val numericRx = rx("$L(?:(?:on|em|dia)\\s+)?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?$R")
    private val dayMonthRx = rx("$L(?:(?:on|em|dia)\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+|de\\s+)?($monthAny)$R(?:,?\\s+(?:de\\s+)?(\\d{4})$R)?")
    private val monthDayRx = rx("$L(?:on\\s+)?($monthAny)\\s+(\\d{1,2})(?:st|nd|rd|th)?$R(?:,?\\s+(\\d{4})$R)?")
    private val weekdayPrefixedRx = rx("$L(?:on|next|na|no)\\s+($dayAny)$R")
    private val weekdayRx = rx("$L($dayFull)$R")

    private fun dayNumber(s: String): Int? {
        val k = s.lowercase()
        return (DAYS + DAY_ABBR).firstOrNull { it.first == k }?.second
    }

    private fun monthNumber(s: String): Int? {
        val k = s.lowercase()
        return MONTHS.firstOrNull { it.first == k }?.second
    }

    private fun num(s: String) = s.replace(',', '.').toDouble()

    fun parse(
        input: String,
        today: LocalDate,
        now: LocalTime,
        order: DateOrder = DateOrder.DMY,
    ): Parsed {
        val work = StringBuilder(input)
        val spans = ArrayList<IntRange>()

        /** Runs [use] on the first match; when it accepts, the match is blanked out. */
        fun take(regex: Regex, use: (MatchResult) -> Boolean): Boolean {
            for (m in regex.findAll(work)) {
                if (use(m)) {
                    spans += m.range
                    for (i in m.range) work.setCharAt(i, ' ')
                    return true
                }
            }
            return false
        }

        var group: String? = null
        take(groupRx) { group = it.groupValues[1]; true }

        // --- recurrence -------------------------------------------------------------------
        var recurrence: Recurrence? = null
        var weeklySameDay = false
        if (!take(dailyRx) { recurrence = Recurrence(Recurrence.Kind.DAILY); true } &&
            !take(weekdaysRx) { recurrence = Recurrence(Recurrence.Kind.WEEKDAYS); true } &&
            !take(everyDaysRx) { m ->
                var mask = 0
                Regex(dayAny, RegexOption.IGNORE_CASE).findAll(m.groupValues[1]).forEach { d ->
                    dayNumber(d.value)?.let { mask = mask or (1 shl (it - 1)) }
                }
                recurrence = Recurrence(Recurrence.Kind.WEEKLY, mask)
                mask != 0
            } &&
            !take(weeklyRx) { weeklySameDay = true; true }
        ) {
            take(monthlyRx) { recurrence = Recurrence(Recurrence.Kind.MONTHLY); true }
        }

        // --- explicit durations ("for 2h", "1h30m") before times, so "for 2h" is not a time --
        var duration: Int? = null
        if (!take(durHmRx) { duration = it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt(); true }) {
            take(durForRx) { m ->
                val n = num(m.groupValues[1])
                val hours = m.groupValues[2].lowercase().startsWith("h")
                duration = (if (hours) n * 60 else n).toInt()
                true
            }
        }

        // --- time -------------------------------------------------------------------------
        var time: LocalTime? = null
        fun setTime(h: Int, m: Int): Boolean {
            if (h !in 0..23 || m !in 0..59) return false
            time = LocalTime.of(h, m)
            return true
        }
        if (!take(noonRx) { m ->
                val w = m.groupValues[1].lowercase()
                setTime(if (w.startsWith("mid") && w != "midday" || w == "meia-noite") 0 else 12, 0)
            } &&
            !take(ampmRx) { m ->
                val h = m.groupValues[1].toInt()
                val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                val pm = m.groupValues[3].lowercase().startsWith("p")
                if (h !in 1..12) false else setTime(h % 12 + if (pm) 12 else 0, min)
            } &&
            !take(atRx) { m -> setTime(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt()) }
        ) {
            take(clockRx) { m -> setTime(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        }

        // --- bare durations ("30m", "2 hours") ----------------------------------------------
        if (duration == null) {
            if (!take(durMinRx) { duration = it.groupValues[1].toInt(); true }) {
                take(durHourRx) { duration = (num(it.groupValues[1]) * 60).toInt(); true }
            }
        }

        // --- date -------------------------------------------------------------------------
        var date: LocalDate? = null
        fun setDate(y: Int?, month: Int, day: Int): Boolean {
            val year = when {
                y == null -> today.year
                y < 100 -> 2000 + y
                else -> y
            }
            val d = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return false
            // No year given and already past: the next one is meant.
            date = if (y == null && d.isBefore(today)) d.plusYears(1) else d
            return true
        }
        fun upcoming(dow: Int): LocalDate = today.with(TemporalAdjusters.next(DayOfWeek.of(dow)))

        val found = take(todayRx) { date = today; true } ||
            take(tomorrowRx) { date = today.plusDays(1); true } ||
            take(inRx) { m ->
                val n = m.groupValues[1].toLong()
                val unit = m.groupValues[2].lowercase()
                date = when {
                    unit.startsWith("d") -> today.plusDays(n)
                    unit.startsWith("w") || unit.startsWith("s") -> today.plusWeeks(n)
                    else -> today.plusMonths(n)
                }
                true
            } ||
            take(nextWeekRx) { date = today.plusWeeks(1); true } ||
            take(nextMonthRx) { date = today.plusMonths(1); true } ||
            take(isoRx) { m -> setDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) } ||
            take(numericRx) { m ->
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].toInt()
                val y = m.groupValues[3].ifEmpty { null }?.toInt()
                if (order == DateOrder.DMY) setDate(y, b, a) else setDate(y, a, b)
            } ||
            take(dayMonthRx) { m ->
                setDate(m.groupValues[3].ifEmpty { null }?.toInt(), monthNumber(m.groupValues[2])!!, m.groupValues[1].toInt())
            } ||
            take(monthDayRx) { m ->
                setDate(m.groupValues[3].ifEmpty { null }?.toInt(), monthNumber(m.groupValues[1])!!, m.groupValues[2].toInt())
            } ||
            take(weekdayPrefixedRx) { m -> date = upcoming(dayNumber(m.groupValues[1])!!); true } ||
            take(weekdayRx) { m -> date = upcoming(dayNumber(m.groupValues[1])!!); true }

        // --- fill in what follows from the rest -----------------------------------------------
        if (!found) {
            val t = time
            val r = recurrence
            date = when {
                r != null -> r.onOrAfter(if (t != null && !t.isAfter(now)) today.plusDays(1) else today)
                weeklySameDay -> today
                t != null -> if (t.isAfter(now)) today else today.plusDays(1)
                else -> null
            }
        }
        if (weeklySameDay) {
            recurrence = Recurrence(Recurrence.Kind.WEEKLY, 1 shl ((date ?: today).dayOfWeek.value - 1))
        }

        val title = work.toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim(',', ';', '-', ' ')
        return Parsed(title, date, time, duration, group, recurrence, spans.sortedBy { it.first })
    }

    /** "9am", "14:30", "7" → minutes after midnight, or null. */
    fun parseClock(text: String): Int? {
        val s = text.trim()
        if (s.isEmpty()) return null
        Regex("^(\\d{1,2})$").find(s)?.let { m ->
            val h = m.groupValues[1].toInt()
            return if (h in 0..23) h * 60 else null
        }
        val p = parse(s, LocalDate.of(2000, 1, 1), LocalTime.MIDNIGHT)
        val t = p.time ?: return null
        return if (p.title.isEmpty()) t.hour * 60 + t.minute else null
    }

    /** "30", "45m", "1h30m", "2h" → minutes, or null. */
    fun parseDuration(text: String): Int? {
        val s = text.trim()
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return if (it > 0) it else null }
        val p = parse("for $s", LocalDate.of(2000, 1, 1), LocalTime.MIDNIGHT)
        val d = p.durationMin ?: parse(s, LocalDate.of(2000, 1, 1), LocalTime.MIDNIGHT).durationMin
        return d?.takeIf { it > 0 }
    }

    /** Writes a schedule back as text that [parse] reads the same way. */
    fun format(
        date: LocalDate?,
        minute: Int?,
        durationMin: Int?,
        recurrence: Recurrence?,
        order: DateOrder,
    ): String {
        val parts = ArrayList<String>()
        if (date != null) {
            parts += if (order == DateOrder.DMY) "${date.dayOfMonth}/${date.monthValue}/${date.year}"
            else "${date.monthValue}/${date.dayOfMonth}/${date.year}"
        }
        if (minute != null) parts += formatClock(minute)
        if (durationMin != null) parts += "for ${durationMin}m"
        if (recurrence != null) parts += recurrence.describe()
        return parts.joinToString(" ")
    }

    fun formatClock(minute: Int): String {
        val m = ((minute % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    fun formatDuration(min: Int): String = when {
        min < 60 -> "${min}m"
        min % 60 == 0 -> "${min / 60}h"
        else -> "${min / 60}h${min % 60}m"
    }
}
