package com.dhruv.pixelfarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import java.util.Calendar

/**
 * Draws the farm. V1 is flat-color rectangles; the frame/state plumbing here
 * should not change when V2 swaps in a sprite sheet -- only the draw calls
 * inside [drawFarmer]/[drawCrops] will.
 *
 * The frame counter advances slowly (tamagotchi pace, not 60fps) since this
 * is meant to feel ambient rather than reactive. The farmer's activity and
 * the crop stages both come from [FarmRepository]; the view only interpolates
 * his horizontal position so walking between plots looks smooth.
 */
class FarmView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var frame: Long = 0L

    // interpolated farmer x-position (NaN until first layout)
    private var farmerX: Float = Float.NaN
    private var placeFarmerAtHome = true

    // when the current viewing session began; the farmer's routine is measured
    // from here so he waters the crops each time you open the launcher
    private var sessionStartMs: Long = System.currentTimeMillis()

    private val frameHandler = Handler(Looper.getMainLooper())
    private val frameRunnable = object : Runnable {
        override fun run() {
            frame++
            invalidate()
            frameHandler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    init {
        // DEBUG: long-press fast-forwards qualifying time so the field's growth
        // is watchable without waiting real minutes. Flip DEBUG_FASTFORWARD off
        // (or remove) before shipping.
        isLongClickable = true
        setOnLongClickListener {
            if (DEBUG_FASTFORWARD) {
                FarmRepository.debugAdvance(DEBUG_FASTFORWARD_SECONDS)
                invalidate()
                true
            } else {
                false
            }
        }
    }

    // --- sky / ground ---
    private val daySkyPaint = Paint().apply { color = Color.parseColor("#87CEEB") }
    private val nightSkyPaint = Paint().apply { color = Color.parseColor("#1B2A4A") }
    private val groundPaint = Paint().apply { color = Color.parseColor("#8B5A2B") }
    private val furrowPaint = Paint().apply { color = Color.parseColor("#734820") }
    private val dryMoundPaint = Paint().apply { color = Color.parseColor("#6E4420") }
    private val wetMoundPaint = Paint().apply { color = Color.parseColor("#3B2411") } // watered soil

    // --- celestial ---
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD86B") }
    private val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EDEDF7") }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFFFFF") }

    // --- crops, indexed by stage ---
    private val cropPaints = arrayOf(
        Paint().apply { color = Color.parseColor("#C9A45C") }, // seed
        Paint().apply { color = Color.parseColor("#9ACD32") }, // sprout
        Paint().apply { color = Color.parseColor("#6B8E23") }, // growing
        Paint().apply { color = Color.parseColor("#4F7942") }, // mature
        Paint().apply { color = Color.parseColor("#3E6B2E") }  // harvest-ready
    )
    private val fruitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD700") }
    private val seedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A240F") }
    private val waterDropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6FB7E8") }

    // --- farmer ---
    private val farmerBodyPaint = Paint()
    private val farmerHeadPaint = Paint().apply { color = Color.parseColor("#F0C9A0") }

    // --- HUD ---
    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#99000000") }
    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }
    private val hudStatusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val hudRect = RectF()

    // deterministic star field (fractions of width/sky-height)
    private val stars = arrayOf(
        0.10f to 0.18f, 0.22f to 0.30f, 0.35f to 0.12f, 0.48f to 0.25f,
        0.62f to 0.15f, 0.74f to 0.32f, 0.85f to 0.20f, 0.92f to 0.10f
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        frameHandler.post(frameRunnable)
    }

    /** Called by MainActivity.onResume so the farmer's routine restarts (and he
     *  re-waters) each time the home screen comes to the foreground. */
    fun onResumed() {
        sessionStartMs = System.currentTimeMillis()
        placeFarmerAtHome = true
        invalidate()
    }

    override fun onDetachedFromWindow() {
        frameHandler.removeCallbacks(frameRunnable)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val groundTop = h * 0.5f
        val isNight = isNightNow()
        val sessionElapsedSec = (System.currentTimeMillis() - sessionStartMs) / 1000L
        val farmer = FarmRepository.getFarmerState(sessionElapsedSec, ignoreNight = DEBUG_FASTFORWARD)

        // sky + ground
        canvas.drawRect(0f, 0f, w, groundTop, if (isNight) nightSkyPaint else daySkyPaint)
        canvas.drawRect(0f, groundTop, w, h, groundPaint)

        drawCelestial(canvas, w, groundTop, isNight)
        drawFurrows(canvas, w, h, groundTop)
        drawCrops(canvas, w, h, groundTop, farmer)
        drawFarmer(canvas, w, h, groundTop, farmer)
        drawHud(canvas, w, h)
    }

    private fun isNightNow(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour < 6 || hour >= 22
    }

    /** x-center of plot [i] on the field, kept consistent for crops and farmer. */
    private fun plotCenterX(w: Float, i: Int): Float {
        val areaLeft = w * 0.30f
        val areaRight = w * 0.97f
        val slot = (areaRight - areaLeft) / FarmRepository.PLOTS
        return areaLeft + slot * (i + 0.5f)
    }

    private fun cropBaseline(h: Float, groundTop: Float): Float =
        h - (h - groundTop) * 0.10f

    private fun drawCelestial(canvas: Canvas, w: Float, groundTop: Float, isNight: Boolean) {
        val radius = w * 0.06f
        val cx = w * 0.80f
        val cy = groundTop * 0.35f
        if (isNight) {
            canvas.drawCircle(cx, cy, radius, moonPaint)
            val starR = w * 0.006f
            for ((fx, fy) in stars) {
                canvas.drawCircle(fx * w, fy * groundTop, starR, starPaint)
            }
        } else {
            canvas.drawCircle(cx, cy, radius, sunPaint)
        }
    }

    private fun drawFurrows(canvas: Canvas, w: Float, h: Float, groundTop: Float) {
        val rows = 4
        val gap = (h - groundTop) / (rows + 1)
        val thickness = gap * 0.12f
        for (i in 1..rows) {
            val y = groundTop + gap * i
            canvas.drawRect(0f, y - thickness / 2f, w, y + thickness / 2f, furrowPaint)
        }
    }

    private fun drawCrops(
        canvas: Canvas, w: Float, h: Float, groundTop: Float,
        farmer: FarmRepository.FarmerState
    ) {
        val slotW = (w * 0.97f - w * 0.30f) / FarmRepository.PLOTS
        val stalkWidth = slotW * 0.45f
        val baseline = cropBaseline(h, groundTop)
        val maxCropHeight = (h - groundTop) * 0.7f

        for (i in 0 until FarmRepository.PLOTS) {
            val stage = FarmRepository.getPlotStage(i)
            val cx = plotCenterX(w, i)
            val watered = i in farmer.wateredPlots

            // soil mound -- darker when watered (Stardew-style wet tile)
            val moundW = slotW * 0.7f
            canvas.drawRect(
                cx - moundW / 2f, baseline,
                cx + moundW / 2f, baseline + (h - baseline) * 0.6f,
                if (watered) wetMoundPaint else dryMoundPaint
            )

            if (stage == FarmRepository.EMPTY_PLOT) {
                // prepared but unplanted: just a little seed hole
                canvas.drawCircle(cx, baseline - stalkWidth * 0.15f, stalkWidth * 0.18f, seedPaint)
            } else {
                val heightFactor = 0.18f + stage * 0.205f
                val cropH = maxCropHeight * heightFactor
                canvas.drawRect(
                    cx - stalkWidth / 2f, baseline - cropH,
                    cx + stalkWidth / 2f, baseline,
                    cropPaints[stage]
                )
                if (stage == FarmRepository.STAGES - 1) {
                    canvas.drawCircle(cx, baseline - cropH, stalkWidth * 0.55f, fruitPaint)
                }
            }

            // little water splash on the plot he's actively watering
            if (i == farmer.wateringPlot) {
                canvas.drawCircle(cx, baseline - stalkWidth * 0.3f, stalkWidth * 0.22f, waterDropPaint)
            }
        }
    }

    private fun drawFarmer(
        canvas: Canvas, w: Float, h: Float, groundTop: Float,
        farmer: FarmRepository.FarmerState
    ) {
        farmerBodyPaint.color = when (farmer.action) {
            FarmRepository.FarmerAction.PLOUGH -> Color.parseColor("#8B4513")
            FarmRepository.FarmerAction.WATER -> Color.parseColor("#4682B4")
            FarmRepository.FarmerAction.NAP -> Color.parseColor("#708090")
            FarmRepository.FarmerAction.IDLE -> Color.parseColor("#C19A6B")
        }

        val bodyW = w * 0.10f
        val homeX = w * 0.08f

        // where the farmer wants to be this frame
        val targetX = when (farmer.action) {
            FarmRepository.FarmerAction.WATER ->
                if (farmer.wateringPlot >= 0) plotCenterX(w, farmer.wateringPlot) - bodyW / 2f else homeX
            else -> homeX
        }
        if (placeFarmerAtHome) {
            farmerX = homeX // start each session at home, then walk out to the crops
            placeFarmerAtHome = false
        }
        farmerX = if (farmerX.isNaN()) targetX else farmerX + (targetX - farmerX) * WALK_LERP

        val bodyH = (h - groundTop) * 0.45f
        val baseline = cropBaseline(h, groundTop)

        if (farmer.action == FarmRepository.FarmerAction.NAP) {
            // lie down: wide, short, head off to one side
            val napW = bodyW * 1.6f
            val napH = bodyH * 0.45f
            canvas.drawRect(farmerX, baseline - napH, farmerX + napW, baseline, farmerBodyPaint)
            val headS = napH * 0.9f
            canvas.drawRect(farmerX + napW, baseline - napH, farmerX + napW + headS, baseline, farmerHeadPaint)
        } else {
            val bodyTop = baseline - bodyH
            canvas.drawRect(farmerX, bodyTop, farmerX + bodyW, baseline, farmerBodyPaint)
            val headS = bodyW * 0.85f
            canvas.drawRect(
                farmerX + (bodyW - headS) / 2f, bodyTop - headS,
                farmerX + (bodyW + headS) / 2f, bodyTop,
                farmerHeadPaint
            )
        }
    }

    private fun drawHud(canvas: Canvas, w: Float, h: Float) {
        val pad = w * 0.03f
        val textSize = h * 0.026f
        hudTextPaint.textSize = textSize
        hudStatusPaint.textSize = textSize

        val produceLine = "produce  ${FarmRepository.formatProduceTime()}"
        val cropLine = "ripe  ${FarmRepository.ripeCount()}/${FarmRepository.PLOTS}"
        val farming = FarmRepository.isFarming
        val statusLine = if (farming) "FARMING" else "PAUSED"
        hudStatusPaint.color = if (farming) Color.parseColor("#7CFC7C") else Color.parseColor("#B0B0B0")

        val lineGap = textSize * 1.45f
        val panelW = maxOf(
            hudTextPaint.measureText(produceLine),
            hudTextPaint.measureText(cropLine),
            hudStatusPaint.measureText(statusLine)
        ) + pad * 2f
        val panelH = lineGap * 3f + pad
        hudRect.set(pad, pad, pad + panelW, pad + panelH)
        canvas.drawRoundRect(hudRect, pad * 0.5f, pad * 0.5f, hudBgPaint)

        var y = pad + pad * 0.6f + textSize
        canvas.drawText(produceLine, pad * 2f, y, hudTextPaint)
        y += lineGap
        canvas.drawText(cropLine, pad * 2f, y, hudTextPaint)
        y += lineGap
        canvas.drawText(statusLine, pad * 2f, y, hudStatusPaint)
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 1500L
        private const val WALK_LERP = 0.25f // how fast the farmer eases toward his target x

        // DEBUG fast-forward: each long-press adds this much qualifying time.
        private const val DEBUG_FASTFORWARD = true
        private const val DEBUG_FASTFORWARD_SECONDS = 60L * 5 // +5 qualifying minutes per long-press
    }
}
