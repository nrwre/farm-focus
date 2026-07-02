package com.dhruv.pixelfarm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.exp
import kotlin.math.sin

/**
 * Draws the farm at a smooth 60fps. Farm *logic* (growth, the farmer's routine)
 * stays slow and comes from [FarmRepository]/[DayClock]; the view renders that
 * with buttery, time-based animation -- easing, sway, breathing, particles --
 * rather than snapping between discrete frames.
 *
 * Every element tries a sprite first (via [Sprites], which trims transparent
 * padding) and falls back to a flat shape if the art isn't present. Sprites are
 * drawn preserving aspect ratio and anchored to the ground, so nothing squashes
 * or hovers.
 */
class FarmView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var running = false
    private var lastFrameNs = 0L
    private var tSec = 0f // wall-clock seconds accumulator for animation phases

    // farmer horizontal position (center x, world units) + facing
    private var farmerCx = Float.NaN
    private var facingLeft = false
    private var placeFarmerAtHome = true

    init {
        isLongClickable = true
        setOnLongClickListener {
            if (DEBUG_FASTFORWARD) {
                FarmRepository.debugAdvance(DEBUG_FASTFORWARD_SECONDS)
                true
            } else false
        }
    }

    // --- reusable paints/objects ---
    private val fillPaint = Paint()
    private val skyPaint = Paint()
    private val spritePaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
    private val fencePaint = Paint().apply { color = Color.parseColor("#8A6A43") }
    private val grassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5B8C3A") }
    private val fruitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD700") }
    private val seedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A240F") }
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8FD0F5") }
    private val splashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val dst = RectF()

    private val groundColor = Color.parseColor("#8B5A2B")
    private val furrowColor = Color.parseColor("#734820")
    private val dryMoundColor = Color.parseColor("#6E4420")
    private val wetMoundColor = Color.parseColor("#3B2411")

    private val cropColors = intArrayOf(
        Color.parseColor("#C9A45C"), Color.parseColor("#9ACD32"),
        Color.parseColor("#6B8E23"), Color.parseColor("#4F7942"),
        Color.parseColor("#3E6B2E")
    )

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

    private val stars = arrayOf(
        0.08f to 0.16f, 0.20f to 0.30f, 0.33f to 0.10f, 0.45f to 0.24f,
        0.55f to 0.14f, 0.66f to 0.30f, 0.78f to 0.18f, 0.90f to 0.10f,
        0.14f to 0.40f, 0.72f to 0.42f
    )

    // ---------------- lifecycle / loop ----------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startLoop()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    fun onResumed() {
        placeFarmerAtHome = true
        startLoop()
    }

    fun onPaused() {
        running = false // stop the 60fps loop while the launcher isn't visible (saves battery)
    }

    private fun startLoop() {
        if (running) return
        running = true
        lastFrameNs = System.nanoTime()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val now = System.nanoTime()
        val dt = ((now - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        lastFrameNs = now
        tSec += dt

        val groundTop = h * 0.52f
        val p = DayClock.progress()
        val phase = DayClock.phase(p)
        val farmer = FarmRepository.getFarmerState()

        drawSky(canvas, w, groundTop, p)
        drawStars(canvas, w, groundTop, p, phase)
        drawCelestial(canvas, w, groundTop, p, phase)
        drawClouds(canvas, w, groundTop, p, phase)

        fillPaint.color = groundColor
        canvas.drawRect(0f, groundTop, w, h, fillPaint)
        drawFence(canvas, w, groundTop)
        drawFurrows(canvas, w, h, groundTop)
        drawCrops(canvas, w, h, groundTop, farmer)
        drawGrass(canvas, w, h, groundTop)
        updateAndDrawFarmer(canvas, w, h, groundTop, farmer, dt)

        drawHud(canvas, w, h, phase)

        if (running) postInvalidateOnAnimation()
    }

    // ---------------- sky / celestial ----------------

    private fun drawSky(canvas: Canvas, w: Float, groundTop: Float, p: Float) {
        var lo = skyKeys[0]; var hi = skyKeys[skyKeys.size - 1]
        for (i in 0 until skyKeys.size - 1) {
            if (p >= skyKeys[i].f && p <= skyKeys[i + 1].f) { lo = skyKeys[i]; hi = skyKeys[i + 1]; break }
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
        val r = w * 0.005f
        for ((i, s) in stars.withIndex()) {
            // gentle twinkle
            val tw = 0.6f + 0.4f * sin(tSec * 2f + i).let { it * it }
            starPaint.alpha = (a * tw * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(s.first * w, s.second * groundTop, r, starPaint)
        }
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
            if (phase == DayClock.Phase.EARLY_MORNING) {
                val rayA = (0.5f * (1f - DayClock.phaseFraction(p)) + 0.2f).coerceIn(0f, 0.7f)
                rayPaint.color = Color.parseColor("#FFE9A8")
                rayPaint.alpha = (rayA * 255).toInt()
                val rr = size * (1.3f + 0.08f * sin(tSec * 2f)) // subtle pulse
                for (k in 0 until 8) {
                    val ang = Math.PI * 2 * k / 8 + tSec * 0.15
                    canvas.drawLine(cx, cy, cx + (Math.cos(ang) * rr).toFloat(), cy + (Math.sin(ang) * rr).toFloat(), rayPaint)
                }
            }
            drawCelestialBody(canvas, Sprites.SUN, cx, cy, size, Color.parseColor("#FFD86B"))
        }
    }

    private fun drawCelestialBody(canvas: Canvas, name: String, cx: Float, cy: Float, size: Float, fallback: Int) {
        val bmp = Sprites.get(context, name)
        if (bmp != null) {
            drawBitmapAnchored(canvas, bmp, cx, cy + size / 2f, size, size, false)
        } else {
            circlePaint.color = fallback
            canvas.drawCircle(cx, cy, size / 2f, circlePaint)
        }
    }

    private fun drawClouds(canvas: Canvas, w: Float, groundTop: Float, p: Float, phase: DayClock.Phase) {
        if (phase == DayClock.Phase.NIGHT) return
        val cloudW = w * 0.26f
        val cloudH = cloudW * 0.55f
        // smooth continuous drift using tSec (independent of the day fraction)
        val d1 = ((tSec * 0.006f + 0.10f) % 1f) * (w + cloudW) - cloudW
        val d2 = ((tSec * 0.004f + 0.55f) % 1f) * (w + cloudW) - cloudW
        drawOneCloud(canvas, d1, groundTop * 0.22f, cloudW, cloudH)
        drawOneCloud(canvas, d2, groundTop * 0.42f, cloudW * 0.8f, cloudH * 0.8f)
    }

    private fun drawOneCloud(canvas: Canvas, left: Float, top: Float, cw: Float, ch: Float) {
        val bmp = Sprites.get(context, Sprites.CLOUD)
        if (bmp != null) {
            dst.set(left, top, left + cw, top + ch)
            canvas.drawBitmap(bmp, null, dst, spritePaint)
        } else {
            circlePaint.color = Color.argb(235, 255, 255, 255)
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
        while (x < w) { canvas.drawRect(x, groundTop - postH, x + postW, groundTop, fencePaint); x += gap }
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
        val baseline = cropBaseline(h, groundTop)
        val maxCropHeight = (h - groundTop) * 0.72f
        val moundW = slotW * 0.8f
        val moundH = (h - baseline) * 0.7f

        for (i in 0 until FarmRepository.PLOTS) {
            val stage = FarmRepository.getPlotStage(i)
            val cx = plotCenterX(w, i)
            val watered = i in farmer.wateredPlots

            // soil tile
            if (Sprites.get(context, if (watered) Sprites.SOIL_WET else Sprites.SOIL_DRY) == null) {
                fillPaint.color = if (watered) wetMoundColor else dryMoundColor
                canvas.drawRect(cx - moundW / 2f, baseline, cx + moundW / 2f, baseline + moundH, fillPaint)
            } else {
                val soil = Sprites.get(context, if (watered) Sprites.SOIL_WET else Sprites.SOIL_DRY)!!
                dst.set(cx - moundW / 2f, baseline, cx + moundW / 2f, baseline + moundH)
                canvas.drawBitmap(soil, null, dst, spritePaint)
            }

            if (stage == FarmRepository.EMPTY_PLOT) {
                canvas.drawCircle(cx, baseline - moundW * 0.06f, slotW * 0.08f, seedPaint)
            } else {
                val heightFactor = 0.20f + stage * 0.20f
                val cropH = maxCropHeight * heightFactor
                // sway from the root; taller plants sway more
                val swayDeg = if (stage >= 1) sin(tSec * 1.1f + i) * (0.8f + stage * 0.5f) else 0f
                val bmp = Sprites.get(context, Sprites.CROP_STAGE[stage])
                if (bmp != null) {
                    // soft contact shadow
                    canvas.drawOval(cx - slotW * 0.28f, baseline - moundW * 0.03f, cx + slotW * 0.28f, baseline + moundW * 0.08f, shadowPaint)
                    canvas.save()
                    canvas.rotate(swayDeg, cx, baseline)
                    drawBitmapAnchored(canvas, bmp, cx, baseline, cropH, slotW * 0.95f, false)
                    canvas.restore()
                } else {
                    val stalkW = slotW * 0.5f
                    fillPaint.color = cropColors[stage]
                    canvas.drawRect(cx - stalkW / 2f, baseline - cropH, cx + stalkW / 2f, baseline, fillPaint)
                    if (stage == FarmRepository.STAGES - 1) canvas.drawCircle(cx, baseline - cropH, stalkW * 0.5f, fruitPaint)
                }
            }
        }

        // animated watering effect on the plot being tended
        if (farmer.wateringPlot in 0 until FarmRepository.PLOTS) {
            drawWatering(canvas, plotCenterX(w, farmer.wateringPlot), baseline, slotW)
        }
    }

    private fun drawWatering(canvas: Canvas, cx: Float, baseline: Float, slotW: Float) {
        val spoutY = baseline - slotW * 0.9f
        // falling droplets
        for (k in 0 until 4) {
            val ph = ((tSec * 1.6f + k * 0.25f) % 1f)
            val y = spoutY + ph * (baseline - spoutY)
            val x = cx + (k - 1.5f) * slotW * 0.06f
            dropPaint.alpha = (255 * (1f - ph * 0.3f)).toInt()
            canvas.drawCircle(x, y, slotW * 0.035f, dropPaint)
        }
        // expanding splash ring on the soil
        val rp = (tSec * 1.6f) % 1f
        splashPaint.color = Color.parseColor("#8FD0F5")
        splashPaint.alpha = (200 * (1f - rp)).toInt()
        canvas.drawCircle(cx, baseline + slotW * 0.05f, slotW * 0.1f + rp * slotW * 0.25f, splashPaint)
    }

    private fun drawGrass(canvas: Canvas, w: Float, h: Float, groundTop: Float) {
        val tufts = 7
        val y = h - (h - groundTop) * 0.02f
        val tuftH = (h - groundTop) * 0.05f
        for (i in 0 until tufts) {
            val x = w * (0.03f + 0.94f * i / (tufts - 1))
            val bob = sin(tSec * 1.3f + i) * tuftH * 0.15f
            canvas.drawCircle(x, y + bob, tuftH, grassPaint)
        }
    }

    // ---------------- farmer ----------------

    private fun updateAndDrawFarmer(canvas: Canvas, w: Float, h: Float, groundTop: Float, farmer: FarmRepository.FarmerState, dt: Float) {
        val baseline = cropBaseline(h, groundTop)
        val charH = (h - groundTop) * 0.6f
        val maxW = w * 0.16f
        val homeCx = w * 0.14f

        val targetCx = when (farmer.action) {
            FarmRepository.FarmerAction.WATER ->
                if (farmer.wateringPlot >= 0) plotCenterX(w, farmer.wateringPlot) else homeCx
            else -> homeCx
        }
        if (placeFarmerAtHome) { farmerCx = homeCx; placeFarmerAtHome = false }
        if (farmerCx.isNaN()) farmerCx = targetCx

        // frame-rate-independent easing (buttery)
        val k = 1f - exp(-EASE_RATE * dt)
        val prev = farmerCx
        farmerCx += (targetCx - farmerCx) * k
        val vx = farmerCx - prev
        if (vx < -0.4f) facingLeft = true else if (vx > 0.4f) facingLeft = false
        val moving = kotlin.math.abs(targetCx - farmerCx) > w * 0.01f

        // vertical motion: walk bounce while moving, gentle breathing while still
        val bob = if (moving)
            kotlin.math.abs(sin(tSec * 9f)) * charH * 0.05f
        else
            sin(tSec * 2.2f) * charH * 0.012f
        val feetY = baseline - bob

        val bmp = Sprites.get(context, farmerSprite(farmer.action))

        // contact shadow (shrinks a touch on the up-beat of the bounce)
        val shW = (bmp?.let { minOf(maxW, charH * it.width / it.height) } ?: (charH * 0.6f)) * (0.9f - bob / charH)
        canvas.drawOval(farmerCx - shW / 2f, baseline - charH * 0.03f, farmerCx + shW / 2f, baseline + charH * 0.06f, shadowPaint)

        if (bmp != null) {
            drawBitmapAnchored(canvas, bmp, farmerCx, feetY, charH, maxW, facingLeft)
        } else {
            drawFarmerFallback(canvas, farmer.action, farmerCx, feetY, charH)
        }
    }

    private fun farmerSprite(action: FarmRepository.FarmerAction): String = when (action) {
        FarmRepository.FarmerAction.PLOUGH -> Sprites.FARMER_PLOUGH
        FarmRepository.FarmerAction.WATER -> Sprites.FARMER_WATER
        FarmRepository.FarmerAction.NAP -> Sprites.FARMER_NAP
        FarmRepository.FarmerAction.IDLE -> Sprites.FARMER_IDLE
    }

    private fun drawFarmerFallback(canvas: Canvas, action: FarmRepository.FarmerAction, cx: Float, baseline: Float, charH: Float) {
        fillPaint.color = when (action) {
            FarmRepository.FarmerAction.PLOUGH -> Color.parseColor("#8B4513")
            FarmRepository.FarmerAction.WATER -> Color.parseColor("#4682B4")
            FarmRepository.FarmerAction.NAP -> Color.parseColor("#708090")
            FarmRepository.FarmerAction.IDLE -> Color.parseColor("#C19A6B")
        }
        val headPaint = Paint().apply { color = Color.parseColor("#F0C9A0") }
        val bodyW = charH * 0.42f
        if (action == FarmRepository.FarmerAction.NAP) {
            val napW = bodyW * 1.6f; val napH = charH * 0.3f
            canvas.drawRect(cx - napW / 2f, baseline - napH, cx + napW / 2f, baseline, fillPaint)
            canvas.drawRect(cx + napW / 2f, baseline - napH, cx + napW / 2f + napH * 0.9f, baseline, headPaint)
        } else {
            val bodyH = charH * 0.7f
            canvas.drawRect(cx - bodyW / 2f, baseline - bodyH, cx + bodyW / 2f, baseline, fillPaint)
            val headS = bodyW * 0.85f
            canvas.drawRect(cx - headS / 2f, baseline - bodyH - headS, cx + headS / 2f, baseline - bodyH, headPaint)
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
            hudTextPaint.measureText(produceLine), hudTextPaint.measureText(cropLine),
            hudTextPaint.measureText(timeLine), hudStatusPaint.measureText(statusLine)
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

    /**
     * Draws [bmp] preserving aspect ratio, scaled to fit within [targetH]x[maxW],
     * anchored bottom-center at ([cx],[bottomY]). Optionally mirrored for facing.
     */
    private fun drawBitmapAnchored(canvas: Canvas, bmp: Bitmap, cx: Float, bottomY: Float, targetH: Float, maxW: Float, flip: Boolean) {
        val ar = bmp.width.toFloat() / bmp.height.toFloat()
        var drawH = targetH
        var drawW = drawH * ar
        if (drawW > maxW) { drawW = maxW; drawH = drawW / ar }
        val left = cx - drawW / 2f
        val top = bottomY - drawH
        dst.set(left, top, left + drawW, bottomY)
        if (flip) {
            canvas.save()
            canvas.scale(-1f, 1f, cx, bottomY)
            canvas.drawBitmap(bmp, null, dst, spritePaint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bmp, null, dst, spritePaint)
        }
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
        private const val EASE_RATE = 3.5f // farmer walk easing (higher = snappier)

        // DEBUG fast-forward: each long-press adds this much qualifying time.
        private const val DEBUG_FASTFORWARD = true
        private const val DEBUG_FASTFORWARD_SECONDS = 60L
    }
}
