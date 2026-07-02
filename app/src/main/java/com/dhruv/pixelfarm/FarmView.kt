package com.dhruv.pixelfarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * Draws the farm. Everything stateful comes from [FarmRepository] and
 * [DayClock]; the view only turns that into pixels. Each drawn element tries a
 * sprite first (via [Sprites]) and falls back to a flat shape if the art isn't
 * present -- so dropping a PNG into res/drawable-nodpi upgrades the look with no
 * code change.
 */
class FarmView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // interpolated farmer x-position (NaN until first layout)
    private var farmerX: Float = Float.NaN
    private var placeFarmerAtHome = true

    private val frameHandler = Handler(Looper.getMainLooper())
    private val frameRunnable = object : Runnable {
        override fun run() {
            invalidate()
            frameHandler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    init {
        // DEBUG: long-press fast-forwards qualifying time so the field's growth
        // is watchable without waiting. Flip DEBUG_FASTFORWARD off before shipping.
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

    // --- reusable paints/objects (avoid per-frame allocation) ---
    private val fillPaint = Paint()
    private val skyPaint = Paint()
    private val spritePaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val fencePaint = Paint().apply { color = Color.parseColor("#8A6A43") }
    private val grassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5B8C3A") }
    private val fruitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD700") }
    private val seedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A240F") }
    private val waterDropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6FB7E8") }
    private val dst = RectF()

    // ground fallbacks
    private val groundColor = Color.parseColor("#8B5A2B")
    private val furrowColor = Color.parseColor("#734820")
    private val dryMoundColor = Color.parseColor("#6E4420")
    private val wetMoundColor = Color.parseColor("#3B2411")

    // crop stage fallback colors
    private val cropColors = intArrayOf(
        Color.parseColor("#C9A45C"), Color.parseColor("#9ACD32"),
        Color.parseColor("#6B8E23"), Color.parseColor("#4F7942"),
        Color.parseColor("#3E6B2E")
    )

    // sky gradient keyframes: fraction -> (topColor, bottomColor)
    private val skyKeys = arrayOf(
        SkyKey(0.00f, 0xFF0B1026.toInt(), 0xFF16213A.toInt()),
        SkyKey(0.18f, 0xFF12203F.toInt(), 0xFF243A63.toInt()),
        SkyKey(0.24f, 0xFF4A5A9B.toInt(), 0xFFF0A56B.toInt()),
        SkyKey(0.32f, 0xFF7EC0EE.toInt(), 0xFFCFEBFF.toInt()),
        SkyKey(0.50f, 0xFF6FB7E8.toInt(), 0xFFAEDDF6.toInt()),
        SkyKey(0.66f, 0xFF7FB0E0.toInt(), 0xFFF3D9A6.toInt()),
        SkyKey(0.74f, 0xFF6A3E7A.toInt(), 0xFFF5794B.toInt()),
        SkyKey(0.80f, 0xFF2A2350.toInt(), 0xFF6A4A6B.toInt()),
        SkyKey(0.90f, 0xFF0E1530.toInt(), 0xFF1B2A4A.toInt()),
        SkyKey(1.00f, 0xFF0B1026.toInt(), 0xFF16213A.toInt())
    )
    private class SkyKey(val f: Float, val top: Int, val bottom: Int)

    // deterministic star field (fractions of width / sky-height)
    private val stars = arrayOf(
        0.08f to 0.16f, 0.20f to 0.30f, 0.33f to 0.10f, 0.45f to 0.24f,
        0.55f to 0.14f, 0.66f to 0.30f, 0.78f to 0.18f, 0.90f to 0.10f,
        0.14f to 0.40f, 0.72f to 0.42f
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        frameHandler.post(frameRunnable)
    }

    override fun onDetachedFromWindow() {
        frameHandler.removeCallbacks(frameRunnable)
        super.onDetachedFromWindow()
    }

    /** Called from MainActivity.onResume so the farmer eases in from home again. */
    fun onResumed() {
        placeFarmerAtHome = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val groundTop = h * 0.52f
        val p = DayClock.progress()
        val phase = DayClock.phase(p)
        val farmer = FarmRepository.getFarmerState()

        drawSky(canvas, w, groundTop, p)
        drawStars(canvas, w, groundTop, p, phase)
        drawCelestial(canvas, w, groundTop, p, phase)
        drawClouds(canvas, w, groundTop, p, phase)

        // ground
        fillPaint.color = groundColor
        canvas.drawRect(0f, groundTop, w, h, fillPaint)
        drawFence(canvas, w, groundTop)
        drawFurrows(canvas, w, h, groundTop)
        drawCrops(canvas, w, h, groundTop, farmer)
        drawGrass(canvas, w, h, groundTop)
        drawFarmer(canvas, w, h, groundTop, farmer)

        drawHud(canvas, w, h, phase)
    }

    // ---------------- sky / celestial ----------------

    private fun drawSky(canvas: Canvas, w: Float, groundTop: Float, p: Float) {
        var lo = skyKeys[0]
        var hi = skyKeys[skyKeys.size - 1]
        for (i in 0 until skyKeys.size - 1) {
            if (p >= skyKeys[i].f && p <= skyKeys[i + 1].f) {
                lo = skyKeys[i]; hi = skyKeys[i + 1]; break
            }
        }
        val t = if (hi.f == lo.f) 0f else (p - lo.f) / (hi.f - lo.f)
        val top = lerpColor(lo.top, hi.top, t)
        val bottom = lerpColor(lo.bottom, hi.bottom, t)
        skyPaint.shader = LinearGradient(0f, 0f, 0f, groundTop, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, groundTop, skyPaint)
    }

    private fun starAlpha(p: Float, phase: DayClock.Phase): Float = when (phase) {
        DayClock.Phase.NIGHT -> 1f
        DayClock.Phase.EARLY_MORNING -> (1f - DayClock.phaseFraction(p)).coerceIn(0f, 1f)
        DayClock.Phase.EVENING -> DayClock.phaseFraction(p).coerceIn(0f, 1f) * 0.8f
        else -> 0f
    }

    private fun drawStars(canvas: Canvas, w: Float, groundTop: Float, p: Float, phase: DayClock.Phase) {
        val a = starAlpha(p, phase)
        if (a <= 0.02f) return
        starPaint.alpha = (a * 255).toInt()
        val r = w * 0.005f
        for ((fx, fy) in stars) canvas.drawCircle(fx * w, fy * groundTop, r, starPaint)
    }

    private fun drawCelestial(canvas: Canvas, w: Float, groundTop: Float, p: Float, phase: DayClock.Phase) {
        val size = w * 0.13f
        if (phase == DayClock.Phase.NIGHT) {
            val arc = nightArc(p)
            val cx = w * (0.12f + 0.76f * arc)
            val cy = groundTop * 0.85f - sin(Math.PI * arc).toFloat() * groundTop * 0.6f
            drawCelestialBody(canvas, Sprites.MOON, cx, cy, size, Color.parseColor("#EDEDF7"))
        } else {
            val arc = DayClock.sunArc(p)
            val cx = w * (0.12f + 0.76f * arc)
            val cy = groundTop * 0.9f - sin(Math.PI * arc).toFloat() * groundTop * 0.65f
            // warm sunrays at dawn
            if (phase == DayClock.Phase.EARLY_MORNING) {
                val rayA = (0.5f * (1f - DayClock.phaseFraction(p)) + 0.2f).coerceIn(0f, 0.7f)
                rayPaint.color = Color.parseColor("#FFE9A8")
                rayPaint.alpha = (rayA * 255).toInt()
                val rr = size * 1.4f
                for (k in 0 until 8) {
                    val ang = Math.PI * 2 * k / 8
                    canvas.drawLine(
                        cx, cy,
                        cx + (Math.cos(ang) * rr).toFloat(),
                        cy + (Math.sin(ang) * rr).toFloat(),
                        rayPaint
                    )
                }
            }
            drawCelestialBody(canvas, Sprites.SUN, cx, cy, size, Color.parseColor("#FFD86B"))
        }
    }

    private fun drawCelestialBody(canvas: Canvas, name: String, cx: Float, cy: Float, size: Float, fallback: Int) {
        val bmp = Sprites.get(context, name)
        if (bmp != null) {
            dst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
            canvas.drawBitmap(bmp, null, dst, spritePaint)
        } else {
            circlePaint.color = fallback
            canvas.drawCircle(cx, cy, size / 2f, circlePaint)
        }
    }

    private fun drawClouds(canvas: Canvas, w: Float, groundTop: Float, p: Float, phase: DayClock.Phase) {
        if (phase == DayClock.Phase.NIGHT) return
        val cloudW = w * 0.26f
        val cloudH = cloudW * 0.55f
        // two clouds drifting at slightly different speeds/heights
        drawOneCloud(canvas, ((p * 1.0f + 0.10f) % 1f) * (w + cloudW) - cloudW, groundTop * 0.22f, cloudW, cloudH)
        drawOneCloud(canvas, ((p * 0.6f + 0.55f) % 1f) * (w + cloudW) - cloudW, groundTop * 0.42f, cloudW * 0.8f, cloudH * 0.8f)
    }

    private fun drawOneCloud(canvas: Canvas, left: Float, top: Float, cw: Float, ch: Float) {
        val bmp = Sprites.get(context, Sprites.CLOUD)
        if (bmp != null) {
            dst.set(left, top, left + cw, top + ch)
            canvas.drawBitmap(bmp, null, dst, spritePaint)
        } else {
            circlePaint.color = Color.argb(242, 255, 255, 255)
            canvas.drawCircle(left + cw * 0.3f, top + ch * 0.6f, ch * 0.5f, circlePaint)
            canvas.drawCircle(left + cw * 0.55f, top + ch * 0.45f, ch * 0.6f, circlePaint)
            canvas.drawCircle(left + cw * 0.75f, top + ch * 0.6f, ch * 0.45f, circlePaint)
        }
    }

    // ---------------- ground / crops ----------------

    private fun drawFence(canvas: Canvas, w: Float, groundTop: Float) {
        val postW = w * 0.012f
        val postH = groundTop * 0.10f
        val railY = groundTop - postH * 0.55f
        canvas.drawRect(0f, railY, w, railY + postH * 0.12f, fencePaint)
        var x = w * 0.04f
        val gap = w * 0.16f
        while (x < w) {
            canvas.drawRect(x, groundTop - postH, x + postW, groundTop, fencePaint)
            x += gap
        }
    }

    private fun drawFurrows(canvas: Canvas, w: Float, h: Float, groundTop: Float) {
        fillPaint.color = furrowColor
        val rows = 4
        val gap = (h - groundTop) / (rows + 1)
        val thickness = gap * 0.1f
        for (i in 1..rows) {
            val y = groundTop + gap * i
            canvas.drawRect(0f, y - thickness / 2f, w, y + thickness / 2f, fillPaint)
        }
    }

    private fun plotCenterX(w: Float, i: Int): Float {
        val areaLeft = w * 0.30f
        val areaRight = w * 0.97f
        val slot = (areaRight - areaLeft) / FarmRepository.PLOTS
        return areaLeft + slot * (i + 0.5f)
    }

    private fun cropBaseline(h: Float, groundTop: Float): Float = h - (h - groundTop) * 0.10f

    private fun drawCrops(canvas: Canvas, w: Float, h: Float, groundTop: Float, farmer: FarmRepository.FarmerState) {
        val slotW = (w * 0.97f - w * 0.30f) / FarmRepository.PLOTS
        val stalkWidth = slotW * 0.5f
        val baseline = cropBaseline(h, groundTop)
        val maxCropHeight = (h - groundTop) * 0.72f
        val sway = sin(System.currentTimeMillis() / 700.0).toFloat() * stalkWidth * 0.08f

        for (i in 0 until FarmRepository.PLOTS) {
            val stage = FarmRepository.getPlotStage(i)
            val cx = plotCenterX(w, i)
            val watered = i in farmer.wateredPlots

            // soil tile
            val moundW = slotW * 0.78f
            val soilTop = baseline
            val soilBottom = baseline + (h - baseline) * 0.7f
            if (!drawSprite(canvas, if (watered) Sprites.SOIL_WET else Sprites.SOIL_DRY,
                    cx - moundW / 2f, soilTop, cx + moundW / 2f, soilBottom)) {
                fillPaint.color = if (watered) wetMoundColor else dryMoundColor
                canvas.drawRect(cx - moundW / 2f, soilTop, cx + moundW / 2f, soilBottom, fillPaint)
            }

            if (stage == FarmRepository.EMPTY_PLOT) {
                canvas.drawCircle(cx, baseline - stalkWidth * 0.12f, stalkWidth * 0.16f, seedPaint)
            } else {
                val heightFactor = 0.18f + stage * 0.205f
                val cropH = maxCropHeight * heightFactor
                val topSway = if (stage >= 1) sway else 0f
                val cropTop = baseline - cropH
                if (!drawSprite(canvas, Sprites.CROP_STAGE[stage],
                        cx - stalkWidth / 2f + topSway, cropTop, cx + stalkWidth / 2f + topSway, baseline)) {
                    fillPaint.color = cropColors[stage]
                    canvas.drawRect(cx - stalkWidth / 2f + topSway, cropTop, cx + stalkWidth / 2f + topSway, baseline, fillPaint)
                    if (stage == FarmRepository.STAGES - 1) {
                        canvas.drawCircle(cx + topSway, cropTop, stalkWidth * 0.5f, fruitPaint)
                    }
                }
            }

            if (i == farmer.wateringPlot) {
                canvas.drawCircle(cx, baseline - stalkWidth * 0.3f, stalkWidth * 0.2f, waterDropPaint)
            }
        }
    }

    private fun drawGrass(canvas: Canvas, w: Float, h: Float, groundTop: Float) {
        val tufts = 7
        val y = h - (h - groundTop) * 0.02f
        val tuftH = (h - groundTop) * 0.06f
        for (i in 0 until tufts) {
            val x = w * (0.03f + 0.94f * i / (tufts - 1))
            canvas.drawCircle(x, y, tuftH, grassPaint)
        }
    }

    // ---------------- farmer ----------------

    private fun drawFarmer(canvas: Canvas, w: Float, h: Float, groundTop: Float, farmer: FarmRepository.FarmerState) {
        val footprint = (h - groundTop) * 0.5f
        val homeX = w * 0.08f
        val baseline = cropBaseline(h, groundTop)

        val targetX = when (farmer.action) {
            FarmRepository.FarmerAction.WATER ->
                if (farmer.wateringPlot >= 0) plotCenterX(w, farmer.wateringPlot) - footprint / 2f else homeX
            else -> homeX
        }
        if (placeFarmerAtHome) { farmerX = homeX; placeFarmerAtHome = false }
        farmerX = if (farmerX.isNaN()) targetX else farmerX + (targetX - farmerX) * WALK_LERP

        // gentle bob while active
        val bob = when (farmer.action) {
            FarmRepository.FarmerAction.PLOUGH, FarmRepository.FarmerAction.WATER ->
                sin(System.currentTimeMillis() / 220.0).toFloat() * footprint * 0.04f
            else -> 0f
        }

        val name = when (farmer.action) {
            FarmRepository.FarmerAction.PLOUGH -> Sprites.FARMER_PLOUGH
            FarmRepository.FarmerAction.WATER -> Sprites.FARMER_WATER
            FarmRepository.FarmerAction.NAP -> Sprites.FARMER_NAP
            FarmRepository.FarmerAction.IDLE -> Sprites.FARMER_IDLE
        }

        val top = baseline - footprint + bob
        if (!drawSprite(canvas, name, farmerX, top, farmerX + footprint, baseline + bob)) {
            drawFarmerFallback(canvas, farmer.action, farmerX, baseline, footprint)
        }
    }

    /** Rectangle farmer used only when no sprite art is present. */
    private fun drawFarmerFallback(canvas: Canvas, action: FarmRepository.FarmerAction, x: Float, baseline: Float, footprint: Float) {
        fillPaint.color = when (action) {
            FarmRepository.FarmerAction.PLOUGH -> Color.parseColor("#8B4513")
            FarmRepository.FarmerAction.WATER -> Color.parseColor("#4682B4")
            FarmRepository.FarmerAction.NAP -> Color.parseColor("#708090")
            FarmRepository.FarmerAction.IDLE -> Color.parseColor("#C19A6B")
        }
        val headPaint = Paint().apply { color = Color.parseColor("#F0C9A0") }
        val bodyW = footprint * 0.6f
        if (action == FarmRepository.FarmerAction.NAP) {
            val napW = bodyW * 1.6f
            val napH = footprint * 0.35f
            canvas.drawRect(x, baseline - napH, x + napW, baseline, fillPaint)
            canvas.drawRect(x + napW, baseline - napH, x + napW + napH * 0.9f, baseline, headPaint)
        } else {
            val bodyH = footprint * 0.75f
            val bodyTop = baseline - bodyH
            canvas.drawRect(x, bodyTop, x + bodyW, baseline, fillPaint)
            val headS = bodyW * 0.85f
            canvas.drawRect(x + (bodyW - headS) / 2f, bodyTop - headS, x + (bodyW + headS) / 2f, bodyTop, headPaint)
        }
    }

    // ---------------- HUD ----------------

    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#99000000") }
    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.LEFT }
    private val hudStatusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val hudRect = RectF()

    private fun drawHud(canvas: Canvas, w: Float, h: Float, phase: DayClock.Phase) {
        val pad = w * 0.03f
        val textSize = h * 0.024f
        hudTextPaint.textSize = textSize
        hudStatusPaint.textSize = textSize

        val produceLine = "produce  ${FarmRepository.formatProduceTime()}"
        val cropLine = "ripe  ${FarmRepository.ripeCount()}/${FarmRepository.PLOTS}"
        val timeLine = "time  ${phaseLabel(phase)}"
        val farming = FarmRepository.isFarming
        val statusLine = if (farming) "FARMING" else "PAUSED"
        hudStatusPaint.color = if (farming) Color.parseColor("#7CFC7C") else Color.parseColor("#B0B0B0")

        val lineGap = textSize * 1.45f
        val panelW = maxOf(
            hudTextPaint.measureText(produceLine),
            hudTextPaint.measureText(cropLine),
            hudTextPaint.measureText(timeLine),
            hudStatusPaint.measureText(statusLine)
        ) + pad * 2f
        val panelH = lineGap * 4f + pad
        hudRect.set(pad, pad, pad + panelW, pad + panelH)
        canvas.drawRoundRect(hudRect, pad * 0.5f, pad * 0.5f, hudBgPaint)

        var y = pad + pad * 0.6f + textSize
        canvas.drawText(produceLine, pad * 2f, y, hudTextPaint); y += lineGap
        canvas.drawText(cropLine, pad * 2f, y, hudTextPaint); y += lineGap
        canvas.drawText(timeLine, pad * 2f, y, hudTextPaint); y += lineGap
        canvas.drawText(statusLine, pad * 2f, y, hudStatusPaint)
    }

    private fun phaseLabel(phase: DayClock.Phase): String = when (phase) {
        DayClock.Phase.NIGHT -> "night"
        DayClock.Phase.EARLY_MORNING -> "sunrise"
        DayClock.Phase.MORNING -> "morning"
        DayClock.Phase.AFTERNOON -> "afternoon"
        DayClock.Phase.EVENING -> "evening"
    }

    // ---------------- helpers ----------------

    private fun drawSprite(canvas: Canvas, name: String, l: Float, t: Float, r: Float, b: Float): Boolean {
        val bmp = Sprites.get(context, name) ?: return false
        dst.set(l, t, r, b)
        canvas.drawBitmap(bmp, null, dst, spritePaint)
        return true
    }

    private fun nightArc(p: Float): Float =
        if (p >= 0.82f) (p - 0.82f) / 0.36f else (p + 0.18f) / 0.36f

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val aa = (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * tt).toInt()
        val rr = (Color.red(a) + (Color.red(b) - Color.red(a)) * tt).toInt()
        val gg = (Color.green(a) + (Color.green(b) - Color.green(a)) * tt).toInt()
        val bb = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * tt).toInt()
        return Color.argb(aa, rr, gg, bb)
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 300L // smooth enough for the day cycle, still calm
        private const val WALK_LERP = 0.15f // how fast the farmer eases toward his target x

        // DEBUG fast-forward: each long-press adds this much qualifying time.
        private const val DEBUG_FASTFORWARD = true
        private const val DEBUG_FASTFORWARD_SECONDS = 60L // +1 qualifying minute per long-press
    }
}
