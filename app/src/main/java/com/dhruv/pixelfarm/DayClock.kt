package com.dhruv.pixelfarm

/**
 * An accelerated day/night clock shared by the farm logic (what the farmer is
 * doing) and the renderer (sky colors, sun/moon position). One full cycle is
 * [DAY_LENGTH_MS] of real time, so time is tangible -- you watch the sun move
 * and the sky shift within a couple of minutes instead of waiting for the real
 * clock.
 *
 * progress() returns 0f..1f across a day, laid out (no midnight wrap-around in
 * the phase table) as:
 *   0.00 deep night -> 0.20 sunrise -> 0.30 morning -> 0.50 afternoon
 *   -> 0.70 evening/sunset -> 0.82 back to night -> 1.00
 */
object DayClock {

    // One full day-night cycle. Tune to taste (shorter = faster days).
    const val DAY_LENGTH_MS = 4 * 60 * 1000L

    enum class Phase { NIGHT, EARLY_MORNING, MORNING, AFTERNOON, EVENING }

    // phase boundaries as fractions of the day
    private const val EARLY_MORNING_START = 0.20f
    private const val MORNING_START = 0.30f
    private const val AFTERNOON_START = 0.50f
    private const val EVENING_START = 0.70f
    private const val NIGHT_START = 0.82f

    fun progress(nowMs: Long = System.currentTimeMillis()): Float =
        (nowMs % DAY_LENGTH_MS).toFloat() / DAY_LENGTH_MS

    fun phase(p: Float): Phase = when {
        p < EARLY_MORNING_START -> Phase.NIGHT
        p < MORNING_START -> Phase.EARLY_MORNING
        p < AFTERNOON_START -> Phase.MORNING
        p < EVENING_START -> Phase.AFTERNOON
        p < NIGHT_START -> Phase.EVENING
        else -> Phase.NIGHT
    }

    /** 0f..1f progress within the current phase (night handled loosely). */
    fun phaseFraction(p: Float): Float {
        val (start, end) = when (phase(p)) {
            Phase.EARLY_MORNING -> EARLY_MORNING_START to MORNING_START
            Phase.MORNING -> MORNING_START to AFTERNOON_START
            Phase.AFTERNOON -> AFTERNOON_START to EVENING_START
            Phase.EVENING -> EVENING_START to NIGHT_START
            Phase.NIGHT -> if (p >= NIGHT_START) NIGHT_START to 1f else 0f to EARLY_MORNING_START
        }
        return ((p - start) / (end - start)).coerceIn(0f, 1f)
    }

    fun isNight(p: Float): Boolean = phase(p) == Phase.NIGHT

    /** Where the sun sits along its arc, 0f (dawn) .. 1f (dusk), for the daylit span. */
    fun sunArc(p: Float): Float =
        ((p - EARLY_MORNING_START) / (NIGHT_START - EARLY_MORNING_START)).coerceIn(0f, 1f)
}
