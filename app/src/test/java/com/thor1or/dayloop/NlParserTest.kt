package com.thor1or.dayloop

import com.thor1or.dayloop.logic.DateOrder
import com.thor1or.dayloop.logic.NlParser
import com.thor1or.dayloop.logic.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class NlParserTest {
    // A Thursday.
    private val today = LocalDate.of(2026, 11, 12)
    private val now = LocalTime.of(10, 0)
    private fun p(s: String, order: DateOrder = DateOrder.DMY) = NlParser.parse(s, today, now, order)

    @Test fun theExampleFromTheBrief() {
        val r = p("buy bread at 9am on 10/12")
        assertEquals("buy bread", r.title)
        assertEquals(LocalDate.of(2026, 12, 10), r.date)
        assertEquals(LocalTime.of(9, 0), r.time)
    }

    @Test fun monthFirstOrder() {
        val r = p("buy bread at 9am on 10/12", DateOrder.MDY)
        // 12 October has passed, so next year's is meant.
        assertEquals(LocalDate.of(2027, 10, 12), r.date)
    }

    @Test fun plainTitleHasNoSchedule() {
        val r = p("think about the sun and the 3 body problem")
        assertEquals("think about the sun and the 3 body problem", r.title)
        assertNull(r.date); assertNull(r.time); assertNull(r.durationMin)
    }

    @Test fun timeOnlyPicksTodayOrTomorrow() {
        assertEquals(today, p("call bank 14:30").date)
        assertEquals(today.plusDays(1), p("call bank 9am").date)
        assertEquals(LocalTime.of(21, 15), p("movie 9:15pm").time)
        assertEquals(LocalTime.of(12, 0), p("lunch at noon").time)
        assertEquals(LocalTime.of(0, 30), p("x 12:30am").time)
    }

    @Test fun relativeDates() {
        assertEquals(today.plusDays(1), p("dentist tomorrow").date)
        assertEquals(today, p("dentist today").date)
        assertEquals(today.plusDays(3), p("dentist in 3 days").date)
        assertEquals(today.plusWeeks(2), p("dentist in 2 weeks").date)
        assertEquals(LocalDate.of(2026, 11, 16), p("report monday").date)
        assertEquals(LocalDate.of(2026, 11, 19), p("report next thu").date)
        assertEquals(LocalDate.of(2026, 11, 13), p("report on fri").date)
    }

    @Test fun namedMonths() {
        assertEquals(LocalDate.of(2026, 12, 25), p("gifts dec 25").date)
        assertEquals(LocalDate.of(2027, 3, 3), p("gifts 3rd of march").date)
        assertEquals(LocalDate.of(2028, 1, 5), p("trip 5 jan 2028").date)
        assertEquals(LocalDate.of(2027, 2, 1), p("trip 2027-02-01").date)
        assertEquals("may call later", p("may call later").title)
    }

    @Test fun durationsAndGroups() {
        val r = p("deep work tomorrow 9am for 90m #Work")
        assertEquals("deep work", r.title)
        assertEquals(90, r.durationMin)
        assertEquals("Work", r.group)
        assertEquals(45, p("read 45min").durationMin)
        assertEquals(90, p("read 1h30m").durationMin)
        assertEquals(120, p("read for 2h").durationMin)
        assertEquals(150, p("read 2.5 hours").durationMin)
    }

    @Test fun durationAndTimeTogether() {
        val r = p("lunch at 12:30 for 45m")
        assertEquals(LocalTime.of(12, 30), r.time)
        assertEquals(45, r.durationMin)
        assertEquals("lunch", r.title)
    }

    @Test fun recurrence() {
        assertEquals(Recurrence(Recurrence.Kind.DAILY), p("vitamins every day 8am").recurrence)
        assertEquals(today.plusDays(1), p("vitamins every day 8am").date)
        val g = p("gym every mon, wed 7am")
        assertEquals(Recurrence(Recurrence.Kind.WEEKLY, 0b101), g.recurrence)
        assertEquals(LocalDate.of(2026, 11, 16), g.date)
        assertEquals("gym", g.title)
        assertEquals(Recurrence(Recurrence.Kind.WEEKDAYS), p("standup every weekday").recurrence)
        assertEquals(Recurrence(Recurrence.Kind.WEEKLY, 1 shl 3), p("review weekly").recurrence)
        assertEquals(Recurrence(Recurrence.Kind.MONTHLY), p("rent monthly on 5/12").recurrence)
    }

    @Test fun portuguese() {
        val r = p("comprar pão amanhã às 9h")
        assertEquals("comprar pão", r.title)
        assertEquals(today.plusDays(1), r.date)
        assertEquals(LocalTime.of(9, 0), r.time)
        assertEquals(LocalTime.of(14, 30), p("reunião 14h30").time)
        assertEquals(LocalDate.of(2026, 11, 13), p("mercado sexta").date)
    }

    @Test fun spansCoverWhatWasUnderstood() {
        val s = "buy bread at 9am on 10/12"
        val r = p(s)
        assertEquals(listOf("at 9am", "on 10/12"), r.spans.map { s.substring(it) })
    }

    @Test fun formatRoundTrips() {
        for (order in DateOrder.values()) {
            val rec = Recurrence(Recurrence.Kind.WEEKLY, 0b1010)
            val text = NlParser.format(LocalDate.of(2027, 3, 4), 9 * 60 + 5, 75, rec, order)
            val r = p(text, order)
            assertEquals("", r.title)
            assertEquals(LocalDate.of(2027, 3, 4), r.date)
            assertEquals(LocalTime.of(9, 5), r.time)
            assertEquals(75, r.durationMin)
            assertEquals(rec, r.recurrence)
        }
    }

    @Test fun clockAndDurationFields() {
        assertEquals(9 * 60, NlParser.parseClock("9am"))
        assertEquals(14 * 60 + 30, NlParser.parseClock("14:30"))
        assertEquals(7 * 60, NlParser.parseClock("7"))
        assertNull(NlParser.parseClock("soon"))
        assertEquals(30, NlParser.parseDuration("30"))
        assertEquals(90, NlParser.parseDuration("1h30m"))
        assertEquals(120, NlParser.parseDuration("2h"))
        assertEquals("1h30m", NlParser.formatDuration(90))
    }

    @Test fun militaryTime() {
        assertEquals(LocalTime.of(8, 0), p("standup 0800").time)
        assertEquals(LocalTime.of(20, 0), p("dinner 2000").time)
        assertEquals(LocalTime.of(20, 0), p("dinner at 2000 tomorrow").time)
        assertEquals(LocalTime.of(8, 30), p("gym at 830").time)
        assertEquals("standup", p("standup 0800").title)
        // Not a time: out of range, or a year.
        assertNull(p("order 2575 screws").time)
        assertNull(p("plan 2027 budget").time)
        val r = p("trip 5 jan 2028")
        assertNull(r.time)
        assertEquals(LocalDate.of(2028, 1, 5), r.date)
        assertEquals(LocalTime.of(7, 0), p("flight 3 dec 0700").time)
        assertEquals(8 * 60, NlParser.parseClock("0800"))
        assertEquals(20 * 60, NlParser.parseClock("2000"))
        assertEquals(8 * 60 + 30, NlParser.parseClock("830"))
        assertNull(NlParser.parseClock("2460"))
    }

    @Test fun priorities() {
        val r = p("pay rent tomorrow p1")
        assertEquals(1, r.priority)
        assertEquals("pay rent", r.title)
        assertEquals(3, p("P3 tidy desk").priority)
        assertNull(p("buy p5 paper").priority)
        assertNull(p("stop the app").priority)
    }

    @Test fun groupsWithHashOrAt() {
        assertEquals("work", p("report @work").group)
        assertEquals("home", p("#home water plants").group)
        assertEquals("mail a@b.com", p("mail a@b.com").title)
    }

    @Test fun shortDayNames() {
        assertEquals(today.plusDays(1), p("buy bread tom").date)
        assertEquals("buy bread", p("buy bread Tom").title)
        assertEquals(today.plusDays(1), p("buy bread tom 9am").date)
        assertEquals(today.plusDays(1), p("buy bread tom 0900 p2 #home").date)
        assertEquals(LocalDate.of(2026, 11, 18), p("gym wed").date)
        assertEquals(LocalDate.of(2026, 11, 14), p("market sat 10am for 1h").date)
        assertEquals(today, p("call bank tod").date)
        // Mid-sentence they stay ordinary words.
        assertNull(p("buy sun cream").date)
        assertNull(p("ask Tom about the report").date)
        assertEquals("ask Tom about the report", p("ask Tom about the report").title)
    }
}
