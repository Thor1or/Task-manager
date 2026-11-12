package com.thor1or.dayloop.logic

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Day scheduling in the style of SuperMemo Plan.
 *
 * Every activity has a desired length. An activity may have a Fixed start time (F) and/or a
 * Rigid length (R). Activities that sit between two fixed points are shortened proportionally
 * when they do not fit; rigid ones keep their length. Beginning an activity pins it to the real
 * clock time, and everything after it is laid out again.
 *
 * All times are minutes counted from midnight of the plan's day.
 */
object PlanEngine {

    data class Block(
        val id: Long,
        val desired: Int,
        val fixedStart: Int? = null,
        val rigid: Boolean = false,
        val actualStart: Int? = null,
        val actualEnd: Int? = null,
    )

    enum class State { DONE, RUNNING, PENDING }

    data class Slot(
        val id: Long,
        val start: Int,
        val length: Int,
        val state: State,
        /** Free minutes between the previous activity and this one. */
        val gapBefore: Int,
        /** Shortened below its desired length. */
        val compressed: Boolean,
        /** A fixed activity that could not start at its fixed time. */
        val pushed: Boolean,
    ) {
        val end get() = start + length
    }

    data class Result(
        val slots: List<Slot>,
        /** When the last activity ends. */
        val end: Int,
        /** Minutes left between the end of the plan and the end of the day. */
        val free: Int,
        /** Some stretch of the day cannot hold its rigid activities. */
        val overflow: Boolean,
        /** Desired minutes of pending work divided into the minutes it received (1 = no squeeze). */
        val ratio: Double,
    )

    /**
     * @param now current minute of the day when the plan is for today, otherwise null. Pending
     *            activities are never scheduled before it.
     */
    fun compute(blocks: List<Block>, dayStart: Int, dayEnd: Int, now: Int? = null): Result {
        val floor = now ?: Int.MIN_VALUE
        val slots = ArrayList<Slot>(blocks.size)
        var cursor = dayStart
        var overflow = false
        var wanted = 0
        var given = 0
        var i = 0

        fun state(b: Block) = when {
            b.actualStart != null && b.actualEnd != null -> State.DONE
            b.actualStart != null -> State.RUNNING
            else -> State.PENDING
        }

        fun gap(start: Int) = if (slots.isEmpty()) 0 else max(0, start - slots.last().end)

        while (i < blocks.size) {
            val head = blocks[i]
            val headState = state(head)
            if (headState == State.DONE) {
                val s = head.actualStart!!
                slots += Slot(head.id, s, max(0, head.actualEnd!! - s), State.DONE, gap(s), false, false)
                cursor = max(cursor, head.actualEnd)
                i++
                continue
            }

            // A run: one anchor (running, fixed, or simply the next in line) followed by
            // every pending activity without a fixed start.
            val runStart = when {
                headState == State.RUNNING -> head.actualStart!!
                head.fixedStart != null -> max(head.fixedStart, max(cursor, floor))
                else -> max(cursor, floor)
            }
            var j = i + 1
            while (j < blocks.size && state(blocks[j]) == State.PENDING && blocks[j].fixedStart == null) j++
            val runEnd = if (j < blocks.size) {
                val n = blocks[j]
                n.actualStart ?: max(n.fixedStart!!, floor)
            } else dayEnd
            val available = max(0, runEnd - runStart)
            val run = blocks.subList(i, j)

            // Minimum lengths: rigid activities keep theirs; a running one cannot be shorter
            // than the time already spent on it.
            val elapsed = if (headState == State.RUNNING && now != null) max(0, now - head.actualStart!!) else 0
            val locked = BooleanArray(run.size) { run[it].rigid }
            val lockedLen = IntArray(run.size) { run[it].desired }
            var ratio = 1.0
            repeat(2) {
                val lockedSum = run.indices.filter { locked[it] }.sumOf { lockedLen[it] }
                val flexSum = run.indices.filter { !locked[it] }.sumOf { run[it].desired }
                ratio = when {
                    flexSum == 0 -> 1.0
                    lockedSum + flexSum <= available -> 1.0
                    else -> max(0.0, (available - lockedSum).toDouble() / flexSum)
                }
                if (lockedSum > available || (flexSum > 0 && ratio == 0.0)) overflow = true
                if (headState == State.RUNNING) {
                    val len: Double = if (locked[0]) lockedLen[0].toDouble() else head.desired * ratio
                    if (len < elapsed) {
                        locked[0] = true
                        lockedLen[0] = elapsed
                    }
                }
            }
            if (run.indices.filter { locked[it] }.sumOf { lockedLen[it] } > available) overflow = true

            // Lay the run out; rounding is done on the running total so nothing drifts.
            var exact = runStart.toDouble()
            var at = runStart
            for (k in run.indices) {
                val b = run[k]
                val len = if (locked[k]) lockedLen[k].toDouble() else b.desired * ratio
                exact += len
                val end = max(at, exact.roundToInt())
                val st = if (k == 0) headState else State.PENDING
                slots += Slot(
                    id = b.id,
                    start = at,
                    length = end - at,
                    state = st,
                    gapBefore = gap(at),
                    compressed = !locked[k] && ratio < 0.999,
                    pushed = st == State.PENDING && b.fixedStart != null && at > b.fixedStart,
                )
                if (!locked[k]) {
                    wanted += b.desired
                    given += end - at
                }
                at = end
            }
            cursor = max(cursor, at)
            i = j
        }

        val end = slots.maxOfOrNull { it.end } ?: dayStart
        return Result(
            slots = slots,
            end = end,
            free = max(0, dayEnd - max(end, max(dayStart, now ?: dayStart))),
            overflow = overflow,
            ratio = if (wanted == 0) 1.0 else given.toDouble() / wanted,
        )
    }

    /** The plan as it was meant to go: nothing started, no clock. Used to measure delays. */
    fun ideal(blocks: List<Block>, dayStart: Int, dayEnd: Int): Result =
        compute(blocks.map { it.copy(actualStart = null, actualEnd = null) }, dayStart, dayEnd, null)
}
