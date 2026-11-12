package com.thor1or.dayloop

import com.thor1or.dayloop.logic.PlanEngine
import com.thor1or.dayloop.logic.PlanEngine.Block
import com.thor1or.dayloop.logic.PlanEngine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanEngineTest {
    private val start = 8 * 60
    private val end = 22 * 60

    @Test fun fitsWithoutCompression() {
        val r = PlanEngine.compute(listOf(Block(1, 60), Block(2, 30)), start, end)
        assertEquals(listOf(480, 540), r.slots.map { it.start })
        assertEquals(listOf(60, 30), r.slots.map { it.length })
        assertEquals(570, r.end)
        assertEquals(end - 570, r.free)
        assertFalse(r.overflow)
        assertEquals(1.0, r.ratio, 1e-9)
    }

    @Test fun compressesProportionallyBeforeAFixedActivity() {
        // 08:00-10:00 holds 120 min; 180 are wanted, so each gets two thirds.
        val r = PlanEngine.compute(
            listOf(Block(1, 60), Block(2, 120), Block(3, 60, fixedStart = 600)), start, end,
        )
        assertEquals(listOf(40, 80, 60), r.slots.map { it.length })
        assertEquals(600, r.slots[2].start)
        assertTrue(r.slots[0].compressed && r.slots[1].compressed)
        assertFalse(r.slots[2].compressed)
        assertFalse(r.overflow)
    }

    @Test fun rigidActivitiesKeepTheirLength() {
        val r = PlanEngine.compute(
            listOf(Block(1, 60, rigid = true), Block(2, 120), Block(3, 60, fixedStart = 600)), start, end,
        )
        assertEquals(listOf(60, 60, 60), r.slots.map { it.length })
        assertFalse(r.slots[0].compressed)
    }

    @Test fun slackBeforeAFixedActivityIsLeftFree() {
        val r = PlanEngine.compute(listOf(Block(1, 30), Block(2, 60, fixedStart = 600)), start, end)
        assertEquals(30, r.slots[0].length)
        assertEquals(600, r.slots[1].start)
        assertEquals(90, r.slots[1].gapBefore)
    }

    @Test fun endOfDayCompressesTheLastStretch() {
        val r = PlanEngine.compute(listOf(Block(1, 600), Block(2, 600)), start, end)
        assertEquals(listOf(420, 420), r.slots.map { it.length })
        assertEquals(end, r.end)
        assertEquals(0.7, r.ratio, 1e-9)
    }

    @Test fun overflowWhenRigidActivitiesCannotFit() {
        val r = PlanEngine.compute(
            listOf(Block(1, 180, rigid = true), Block(2, 60, fixedStart = 600)), start, end,
        )
        assertTrue(r.overflow)
        // The fixed activity is pushed behind the rigid one.
        assertEquals(660, r.slots[1].start)
        assertTrue(r.slots[1].pushed)
    }

    @Test fun beginningLateReflowsTheRest() {
        // First activity began 30 minutes late; 90 minutes remain for 120 wanted.
        val blocks = listOf(
            Block(1, 60, actualStart = 510), Block(2, 60), Block(3, 60, fixedStart = 600),
        )
        val r = PlanEngine.compute(blocks, start, end, now = 510)
        assertEquals(State.RUNNING, r.slots[0].state)
        assertEquals(listOf(510, 555, 600), r.slots.map { it.start })
        assertEquals(listOf(45, 45, 60), r.slots.map { it.length })
        val ideal = PlanEngine.ideal(blocks, start, end)
        assertEquals(480, ideal.slots[0].start)
    }

    @Test fun anOverrunningActivityTakesTimeFromTheOthers() {
        // Running since 08:00, now 09:30: it has used 90 of the 120 minutes before 10:00.
        val blocks = listOf(Block(1, 60, actualStart = 480), Block(2, 60), Block(3, 60, fixedStart = 600))
        val r = PlanEngine.compute(blocks, start, end, now = 570)
        assertEquals(90, r.slots[0].length)
        assertEquals(570, r.slots[1].start)
        assertEquals(30, r.slots[1].length)
    }

    @Test fun finishedActivitiesKeepTheirRealTimes() {
        val blocks = listOf(Block(1, 60, actualStart = 485, actualEnd = 530), Block(2, 30))
        val r = PlanEngine.compute(blocks, start, end, now = 540)
        assertEquals(State.DONE, r.slots[0].state)
        assertEquals(45, r.slots[0].length)
        // Nothing pending is scheduled in the past.
        assertEquals(540, r.slots[1].start)
        assertEquals(10, r.slots[1].gapBefore)
    }

    @Test fun aMissedFixedStartMovesToNow() {
        val r = PlanEngine.compute(listOf(Block(1, 60, fixedStart = 540)), start, end, now = 600)
        assertEquals(600, r.slots[0].start)
        assertTrue(r.slots[0].pushed)
    }

    @Test fun roundingNeverDrifts() {
        val blocks = (1..7).map { Block(it.toLong(), 25) } + Block(9, 30, fixedStart = 600)
        val r = PlanEngine.compute(blocks, start, end)
        assertEquals(120, r.slots.take(7).sumOf { it.length })
        assertEquals(600, r.slots[6].end)
    }

    @Test fun emptyPlan() {
        val r = PlanEngine.compute(emptyList(), start, end)
        assertEquals(start, r.end)
        assertEquals(end - start, r.free)
    }
}
