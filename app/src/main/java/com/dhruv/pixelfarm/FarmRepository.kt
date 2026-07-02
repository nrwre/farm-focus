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
    private const val CYCLE_SECONDS = SECONDS_PER_GROWTH_STAGE * STAGES

    enum class FarmerAction { PLOUGH, WATER, NAP, IDLE }

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
     * Current crop stage. [offsetSec] lets the renderer stagger a row of plots
     * so the field shows a range of growth at once -- purely cosmetic, the
     * accrual math stays centralized here.
     */
    fun getCropStage(offsetSec: Long = 0L): Int {
        val posInCycle = (producePoints + offsetSec) % CYCLE_SECONDS
        return (posInCycle / SECONDS_PER_GROWTH_STAGE).toInt().coerceIn(0, STAGES - 1)
    }

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

    /** Farmer action for the given animation frame. Naps more often at night. */
    fun getFarmerAction(frame: Long): FarmerAction {
        if (!isFarming) return FarmerAction.IDLE

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isNight = hour < 6 || hour >= 22

        return if (isNight) {
            when ((frame % 5)) {
                0L -> FarmerAction.PLOUGH
                1L -> FarmerAction.WATER
                2L, 3L -> FarmerAction.NAP
                else -> FarmerAction.IDLE
            }
        } else {
            when ((frame % 4)) {
                0L -> FarmerAction.PLOUGH
                1L -> FarmerAction.WATER
                2L -> FarmerAction.NAP
                else -> FarmerAction.IDLE
            }
        }
    }
}
