package com.meridian.tracker.tracking

/**
 * Counts steps during a recording from the phone's step sensor and works out live cadence.
 *
 * Android's step counter reports a running total since the phone last restarted, so only the
 * difference between readings matters. Steps taken while paused aren't counted.
 * Pure Kotlin, unit-tested.
 */
class StepCounter {

    var steps: Int = 0
        private set

    private var lastTotal: Float? = null
    /** Recent (monotonic ms, steps so far) pairs for cadence. */
    private val window = ArrayDeque<Pair<Long, Int>>()

    /** A reading from TYPE_STEP_COUNTER (total steps since boot). */
    fun onCounter(total: Float, monoMs: Long, counting: Boolean) {
        val previous = lastTotal
        lastTotal = total
        if (previous == null) {
            // First reading is only the starting point.
            if (counting) remember(monoMs)
            return
        }
        val delta = (total - previous).toInt()
        // A negative jump means the phone restarted; huge jumps are batched history we shouldn't trust.
        if (counting && delta in 1..MAX_JUMP) steps += delta
        if (counting) remember(monoMs)
    }

    /** One event from TYPE_STEP_DETECTOR (fallback on phones without a step counter). */
    fun onStep(monoMs: Long, counting: Boolean) {
        if (!counting) return
        steps++
        remember(monoMs)
    }

    /** Call when recording resumes so the pause doesn't drag cadence down. */
    fun resetCadence() {
        window.clear()
    }

    /** Steps per minute over the last ~20 seconds, 0 when standing still, null before any data. */
    fun cadence(nowMono: Long): Int? {
        prune(nowMono)
        val last = window.lastOrNull() ?: return null
        if (nowMono - last.first > STILL_AFTER_MS) return 0
        val first = window.first()
        val span = last.first - first.first
        if (span < MIN_SPAN_MS) return null
        val perMin = (last.second - first.second) * 60_000.0 / span
        return perMin.toInt().coerceIn(0, 260)
    }

    private fun remember(monoMs: Long) {
        window.addLast(monoMs to steps)
        prune(monoMs)
    }

    private fun prune(nowMono: Long) {
        while (window.size > 2 && nowMono - window.first().first > WINDOW_MS) window.removeFirst()
    }

    companion object {
        private const val WINDOW_MS = 20_000L
        private const val MIN_SPAN_MS = 6_000L
        private const val STILL_AFTER_MS = 8_000L
        private const val MAX_JUMP = 5_000
    }
}
