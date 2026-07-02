package com.dhruv.pixelfarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View

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

    private val skyPaint = Paint().apply { color = Color.parseColor("#87CEEB") }
    private val nightSkyPaint = Paint().apply { color = Color.parseColor("#1B2A4A") }
    private val groundPaint = Paint().apply { color = Color.parseColor("#8B5A2B") }

    private val cropPaints = arrayOf(
        Paint().apply { color = Color.parseColor("#C9A45C") }, // seed
        Paint().apply { color = Color.parseColor("#9ACD32") }, // sprout
        Paint().apply { color = Color.parseColor("#6B8E23") }, // growing
        Paint().apply { color = Color.parseColor("#4F7942") }, // mature
        Paint().apply { color = Color.parseColor("#FFD700") }  // harvest-ready
    )

    private val farmerPaint = Paint()

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

        val groundTop = h * 0.55f
        val isNight = isNightNow()
        canvas.drawRect(0f, 0f, w, groundTop, if (isNight) nightSkyPaint else skyPaint)
        canvas.drawRect(0f, groundTop, w, h, groundPaint)

        drawCrops(canvas, w, h)
        drawFarmer(canvas, w, groundTop, h)
    }

    private fun isNightNow(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour < 6 || hour >= 22
    }

    private fun drawCrops(canvas: Canvas, w: Float, h: Float) {
        val stage = FarmRepository.getCropStage()
        val paint = cropPaints[stage]
        val plotWidth = w * 0.15f
        val plotHeight = (h * 0.35f) * (0.3f + stage * 0.15f)
        val left = w * 0.62f
        val bottom = h - 20f
        canvas.drawRect(left, bottom - plotHeight, left + plotWidth, bottom, paint)
    }

    private fun drawFarmer(canvas: Canvas, w: Float, groundTop: Float, h: Float) {
        val action = FarmRepository.getFarmerAction(frame)
        farmerPaint.color = when (action) {
            FarmRepository.FarmerAction.PLOUGH -> Color.parseColor("#8B4513")
            FarmRepository.FarmerAction.WATER -> Color.parseColor("#4682B4")
            FarmRepository.FarmerAction.NAP -> Color.parseColor("#708090")
            FarmRepository.FarmerAction.IDLE -> Color.parseColor("#DEB887")
        }
        val size = w * 0.12f
        val left = w * 0.22f
        val top = groundTop + (h - groundTop) * 0.15f
        canvas.drawRect(left, top, left + size, top + size, farmerPaint)
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 1500L
    }
}
