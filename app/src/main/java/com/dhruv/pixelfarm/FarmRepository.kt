package com.dhruv.pixelfarm

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * Single source of truth for farm state.
 *
 * isFarming = screenOff OR (screenOn AND launcherForegrounded)
 *
 * Event-driven, not poll-driven: every caller below funnels through
 * [transition], which settles produce points for the time elapsed under the
 * *previous* state before applying the new one.
 */
object FarmRepository {

    private const val PREFS_NAME = "pixelfarm_prefs"
    private const val KEY_PRODUCE_POINTS = "produce_points"
    private const val KEY_LAST_TIMESTAMP = "last_timestamp"
    private const val KEY_SCREEN_ON = "screen_on"
    private const val KEY_LAUNCHER_FOREGROUND = "launcher_foreground"

    // Economy constants (tune-able, not magic numbers).
    const val SECONDS_PER_GROWTH_STAGE = 60L * 15 // 15 qualifying minutes per stage
    const val STAGES = 5 // seed -> sprout -> ... -> harvest-ready
    const val PLOTS = 5 // plots in the field
    const val EMPTY_PLOT = -1 // getPlotStage() sentinel: soil prepared but nothing planted yet

    // A new plot is planted this often (in qualifying time), so the field
    // fills in left-to-right as restraint accumulates instead of all at once.
    const val PLANT_INTERVAL_SECONDS = 60L * 5 // one new plot every 5 qualifying minutes

    // Farmer's daily routine, in wall-clock seconds. Realistic dwell times so
    // he settles into an activity instead of flickering between them.
    private const val WATER_SECONDS_PER_PLOT = 10L
    private const val PLOUGH_SECONDS = 60L * 20
    private const val IDLE_SECONDS = 60L
    private const val NAP_SECONDS = 60L * 15
    private val DAY_CYCLE_SECONDS =
        PLOTS * WATER_SECONDS_PER_PLOT + PLOUGH_SECONDS + IDLE_SECONDS + NAP_SECONDS

    enum class FarmerAction { PLOUGH, WATER, NAP, IDLE }

    /**
     * Everything the renderer needs to draw the farmer this instant.
     * [wateringPlot] is the plot index currently being watered (or -1), and
     * [wateredPlots] are the plots whose soil should currently look wet.
     */
    data class FarmerState(
        val action: FarmerAction,
        val wateringPlot: Int,
        val wateredPlots: Set<Int>
    )

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    var producePoints: Long = 0L
        private set
    private var lastTimestamp: Long = System.currentTimeMillis()
    private var screenOn: Boolean = true
    private var launcherForegrounded: Boolean = false

    var isFarming: Boolean = false
        private set

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        producePoints = prefs.getLong(KEY_PRODUCE_POINTS, 0L)
        lastTimestamp = prefs.getLong(KEY_LAST_TIMESTAMP, System.currentTimeMillis())
        screenOn = prefs.getBoolean(KEY_SCREEN_ON, true)
        launcherForegrounded = prefs.getBoolean(KEY_LAUNCHER_FOREGROUND, false)
        recomputeIsFarming()
        initialized = true
    }

    @Synchronized
    fun onLauncherResumed() = transition { launcherForegrounded = true }

    @Synchronized
    fun onLauncherPaused() = transition { launcherForegrounded = false }

    @Synchronized
    fun onScreenOn() = transition { screenOn = true }

    @Synchronized
    fun onScreenOff() = transition { screenOn = false }

    /**
     * Periodic no-op transition (called every ~60s from the tracker service)
     * so a long uninterrupted farming session persists incrementally instead
     * of one giant catch-up calculation on the next real event.
     */
    @Synchronized
    fun heartbeat() = transition { }

    private fun transition(mutate: () -> Unit) {
        val now = System.currentTimeMillis()
        val elapsedSec = (now - lastTimestamp) / 1000L
        if (isFarming && elapsedSec > 0) {
            producePoints += elapsedSec
        }
        lastTimestamp = now
        mutate()
        recomputeIsFarming()
        persist()
    }

    private fun recomputeIsFarming() {
        isFarming = !screenOn || (screenOn && launcherForegrounded)
    }

    private fun persist() {
        prefs.edit()
            .putLong(KEY_PRODUCE_POINTS, producePoints)
            .putLong(KEY_LAST_TIMESTAMP, lastTimestamp)
            .putBoolean(KEY_SCREEN_ON, screenOn)
            .putBoolean(KEY_LAUNCHER_FOREGROUND, launcherForegrounded)
            .apply()
    }

    /**
     * DEBUG ONLY: jump qualifying time forward so the field's growth can be
     * watched without waiting real minutes. Wired to a long-press in FarmView,
     * gated behind a flag there. Remove/disable before shipping.
     */
    @Synchronized
    fun debugAdvance(seconds: Long) {
        producePoints += seconds
        lastTimestamp = System.currentTimeMillis()
        persist()
    }

    /**
     * Growth stage of plot [index], or [EMPTY_PLOT] if it hasn't been planted
     * yet. Plots are planted one every [PLANT_INTERVAL_SECONDS] of qualifying
     * time, so the field fills in left-to-right and each plot then climbs the
     * growth stages -- the whole field ends lush after sustained restraint and
     * stays that way (no reset in v1).
     */
    fun getPlotStage(index: Int): Int {
        val plantedAt = index * PLANT_INTERVAL_SECONDS
        val age = producePoints - plantedAt
        if (age < 0) return EMPTY_PLOT
        return (age / SECONDS_PER_GROWTH_STAGE).toInt().coerceAtMost(STAGES - 1)
    }

    /** How many plots are fully grown (harvest-ready). */
    fun ripeCount(): Int = (0 until PLOTS).count { getPlotStage(it) == STAGES - 1 }

    /** How many plots have been planted (stage >= seed). */
    fun plantedCount(): Int = (0 until PLOTS).count { getPlotStage(it) != EMPTY_PLOT }

    /** Human-readable qualifying-time total, e.g. "1h 20m", "5m 12s", "40s". */
    fun formatProduceTime(): String {
        val total = producePoints
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    private fun isNight(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour < 6 || hour >= 22
    }

    /**
     * The farmer's current activity. Driven by [sessionElapsedSec] -- seconds
     * since the user opened the launcher -- so he greets you by watering the
     * crops first, then settles into the longer tasks (rather than the watering
     * pass happening at some fixed clock time you'd never catch). He dwells on
     * each task for realistic stretches instead of flickering. One loop:
     * water every plot (~10s each) -> plough (20m) -> idle (1m) -> nap (15m).
     * At night he just sleeps.
     */
    fun getFarmerState(sessionElapsedSec: Long, ignoreNight: Boolean = false): FarmerState {
        if (isNight() && !ignoreNight) {
            return FarmerState(FarmerAction.NAP, -1, emptySet())
        }

        var pos = sessionElapsedSec % DAY_CYCLE_SECONDS

        val waterPhase = PLOTS * WATER_SECONDS_PER_PLOT
        if (pos < waterPhase) {
            val plot = (pos / WATER_SECONDS_PER_PLOT).toInt()
            // plots already passed are wet; the current one becomes wet as he finishes
            val alreadyWatered = (0 until plot).toSet()
            return FarmerState(FarmerAction.WATER, plot, alreadyWatered)
        }
        pos -= waterPhase

        // once watering is done the whole field stays wet until the next pass
        val allWatered = (0 until PLOTS).toSet()
        if (pos < PLOUGH_SECONDS) return FarmerState(FarmerAction.PLOUGH, -1, allWatered)
        pos -= PLOUGH_SECONDS

        if (pos < IDLE_SECONDS) return FarmerState(FarmerAction.IDLE, -1, allWatered)
        return FarmerState(FarmerAction.NAP, -1, allWatered)
    }
}
