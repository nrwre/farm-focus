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
 * is meant to feel ambient rather than reactive.
 */
class FarmView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var frame: Long = 0L

    private val frameHandler = Handler(Looper.getMainLooper())
    private val frameRunnable = object : Runnable {
        override fun run() {
            frame++
            invalidate()
            frameHandler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    // --- sky / ground ---
    private val daySkyPaint = Paint().apply { color = Color.parseColor("#87CEEB") }
    private val nightSkyPaint = Paint().apply { color = Color.parseColor("#1B2A4A") }
    private val groundPaint = Paint().apply { color = Color.parseColor("#8B5A2B") }
    private val furrowPaint = Paint().apply { color = Color.parseColor("#734820") }
    private val moundPaint = Paint().apply { color = Color.parseColor("#6E4420") }

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

        // sky + ground
        canvas.drawRect(0f, 0f, w, groundTop, if (isNight) nightSkyPaint else daySkyPaint)
        canvas.drawRect(0f, groundTop, w, h, groundPaint)

        drawCelestial(canvas, w, groundTop, isNight)
        drawFurrows(canvas, w, h, groundTop)
        drawCrops(canvas, w, h, groundTop)
        drawFarmer(canvas, w, h, groundTop)
        drawHud(canvas, w, h)
    }

    private fun isNightNow(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour < 6 || hour >= 22
    }

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

    private fun drawCrops(canvas: Canvas, w: Float, h: Float, groundTop: Float) {
        val areaLeft = w * 0.30f
        val areaRight = w * 0.97f
        val baseline = h - (h - groundTop) * 0.10f
        val slot = (areaRight - areaLeft) / NUM_PLOTS
        val stalkWidth = slot * 0.45f
        val maxCropHeight = (h - groundTop) * 0.7f

        for (i in 0 until NUM_PLOTS) {
            // stagger each plot one stage further so the field shows a range
            val stage = FarmRepository.getCropStage(i.toLong() * FarmRepository.SECONDS_PER_GROWTH_STAGE)
            val cx = areaLeft + slot * (i + 0.5f)

            // soil mound
            val moundW = slot * 0.7f
            canvas.drawRect(
                cx - moundW / 2f, baseline,
                cx + moundW / 2f, baseline + (h - baseline) * 0.6f,
                moundPaint
            )

            // stalk height scales with stage (seed is a small nub)
            val heightFactor = 0.18f + stage * 0.205f
            val cropH = maxCropHeight * heightFactor
            canvas.drawRect(
                cx - stalkWidth / 2f, baseline - cropH,
                cx + stalkWidth / 2f, baseline,
                cropPaints[stage]
            )

            // ripe crops get a golden fruit
            if (stage == FarmRepository.STAGES - 1) {
                canvas.drawCircle(cx, baseline - cropH, stalkWidth * 0.55f, fruitPaint)
            }
        }
    }

    private fun drawFarmer(canvas: Canvas, w: Float, h: Float, groundTop: Float) {
        val action = FarmRepository.getFarmerAction(frame)
        farmerBodyPaint.color = when (action) {
            FarmRepository.FarmerAction.PLOUGH -> Color.parseColor("#8B4513")
            FarmRepository.FarmerAction.WATER -> Color.parseColor("#4682B4")
            FarmRepository.FarmerAction.NAP -> Color.parseColor("#708090")
            FarmRepository.FarmerAction.IDLE -> Color.parseColor("#C19A6B")
        }

        val bodyW = w * 0.10f
        val bodyH = (h - groundTop) * 0.45f
        val left = w * 0.06f
        val baseline = h - (h - groundTop) * 0.10f

        if (action == FarmRepository.FarmerAction.NAP) {
            // lie down: wide, short, with head to one side
            val napW = bodyW * 1.6f
            val napH = bodyH * 0.45f
            canvas.drawRect(left, baseline - napH, left + napW, baseline, farmerBodyPaint)
            val headS = napH * 0.9f
            canvas.drawRect(left + napW, baseline - napH, left + napW + headS, baseline, farmerHeadPaint)
        } else {
            // stand: body + head stacked
            val bodyTop = baseline - bodyH
            canvas.drawRect(left, bodyTop, left + bodyW, baseline, farmerBodyPaint)
            val headS = bodyW * 0.85f
            canvas.drawRect(
                left + (bodyW - headS) / 2f, bodyTop - headS,
                left + (bodyW + headS) / 2f, bodyTop,
                farmerHeadPaint
            )
        }
    }

    private fun drawHud(canvas: Canvas, w: Float, h: Float) {
        val pad = w * 0.03f
        val textSize = h * 0.026f
        hudTextPaint.textSize = textSize
        hudStatusPaint.textSize = textSize

        val stage = FarmRepository.getCropStage()
        val produceLine = "produce  ${FarmRepository.formatProduceTime()}"
        val stageLine = "stage  ${stage + 1}/${FarmRepository.STAGES}"
        val farming = FarmRepository.isFarming
        val statusLine = if (farming) "FARMING" else "PAUSED"
        hudStatusPaint.color = if (farming) Color.parseColor("#7CFC7C") else Color.parseColor("#B0B0B0")

        val lineGap = textSize * 1.45f
        val panelW = maxOf(
            hudTextPaint.measureText(produceLine),
            hudTextPaint.measureText(stageLine),
            hudStatusPaint.measureText(statusLine)
        ) + pad * 2f
        val panelH = lineGap * 3f + pad
        hudRect.set(pad, pad, pad + panelW, pad + panelH)
        canvas.drawRoundRect(hudRect, pad * 0.5f, pad * 0.5f, hudBgPaint)

        var y = pad + pad * 0.6f + textSize
        canvas.drawText(produceLine, pad * 2f, y, hudTextPaint)
        y += lineGap
        canvas.drawText(stageLine, pad * 2f, y, hudTextPaint)
        y += lineGap
        canvas.drawText(statusLine, pad * 2f, y, hudStatusPaint)
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 1500L
        private const val NUM_PLOTS = 5
    }
}
