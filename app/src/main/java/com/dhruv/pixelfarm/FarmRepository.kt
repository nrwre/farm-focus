package com.dhruv.pixelfarm

import android.content.Context
import android.content.SharedPreferences

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

    // Economy constants (tune-able, not magic numbers). Growth is accelerated
    // so progress is tangible -- seconds per stage, not minutes -- while still
    // being driven purely by qualifying (farming) time.
    const val SECONDS_PER_GROWTH_STAGE = 20L // qualifying seconds per growth stage
    const val STAGES = 5 // seed -> sprout -> ... -> harvest-ready
    const val PLOTS = 5 // plots in the field
    const val EMPTY_PLOT = -1 // getPlotStage() sentinel: no crop right now (unplanted or just harvested)

    // A new plot is planted this often (in qualifying time), so the field fills
    // in left-to-right the first time instead of all at once.
    const val PLANT_INTERVAL_SECONDS = 8L

    // After a plot ripens it's harvested and lies fallow briefly, then regrows
    // -- so the farm keeps living instead of freezing once everything is ripe.
    private const val FALLOW_SECONDS = 12L
    private const val PLOT_CYCLE_SECONDS = SECONDS_PER_GROWTH_STAGE * STAGES + FALLOW_SECONDS

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
     * Growth stage of plot [index], or [EMPTY_PLOT] when it currently holds no
     * crop (not planted yet the first time, or freshly harvested and lying
     * fallow). Plots are staggered by [PLANT_INTERVAL_SECONDS] so the field
     * fills left-to-right, then each plot loops: seed -> ripe -> harvested ->
     * regrow. Everything is driven by qualifying time, so the farm keeps living
     * as long as you keep earning farming time.
     */
    fun getPlotStage(index: Int): Int {
        val plantedAt = index * PLANT_INTERVAL_SECONDS
        val age = producePoints - plantedAt
        if (age < 0) return EMPTY_PLOT // not planted for the first time yet

        val posInCycle = age % PLOT_CYCLE_SECONDS
        val growSpan = SECONDS_PER_GROWTH_STAGE * STAGES
        if (posInCycle >= growSpan) return EMPTY_PLOT // harvested, lying fallow
        return (posInCycle / SECONDS_PER_GROWTH_STAGE).toInt().coerceAtMost(STAGES - 1)
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

    /**
     * The farmer's current activity, tied to the accelerated [DayClock] so it
     * reads as a living daily routine:
     *   night         -> sleeps
     *   early morning  -> wakes, idles
     *   morning        -> walks the field watering each plot in turn
     *   afternoon      -> ploughs
     *   evening        -> idles / winds down
     * Watered soil stays wet from the morning pass through the evening, then
     * dries overnight.
     */
    fun getFarmerState(): FarmerState {
        val p = DayClock.progress()
        val allWatered = (0 until PLOTS).toSet()
        return when (DayClock.phase(p)) {
            DayClock.Phase.NIGHT -> FarmerState(FarmerAction.NAP, -1, emptySet())
            DayClock.Phase.EARLY_MORNING -> FarmerState(FarmerAction.IDLE, -1, emptySet())
            DayClock.Phase.MORNING -> {
                // sweep across the plots over the course of the morning
                val frac = DayClock.phaseFraction(p)
                val plot = (frac * PLOTS).toInt().coerceIn(0, PLOTS - 1)
                FarmerState(FarmerAction.WATER, plot, (0 until plot).toSet())
            }
            DayClock.Phase.AFTERNOON -> FarmerState(FarmerAction.PLOUGH, -1, allWatered)
            DayClock.Phase.EVENING -> FarmerState(FarmerAction.IDLE, -1, allWatered)
        }
    }
}
