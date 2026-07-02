package com.dhruv.pixelfarm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Loads sprites from res/drawable-nodpi by name, cached, with a null fallback
 * so the app runs whether or not the art exists yet. Drop a PNG named exactly
 * like the key (e.g. "farmer_water.png") into res/drawable-nodpi and it appears
 * automatically -- no code change, no recompile of the draw logic.
 *
 * Resolving by name (instead of a hard R.drawable reference) is what lets you
 * add/replace art one file at a time without breaking the build.
 */
object Sprites {

    // known sprite keys (must match the placeholder filenames)
    const val FARMER_IDLE = "farmer_idle"
    const val FARMER_WALK = "farmer_walk"
    const val FARMER_PLOUGH = "farmer_plough"
    const val FARMER_WATER = "farmer_water"
    const val FARMER_NAP = "farmer_nap"

    private const val MAX_FRAMES = 16

    val CROP_STAGE = arrayOf(
        "crop_0_seed", "crop_1_sprout", "crop_2_growing", "crop_3_mature", "crop_4_ripe"
    )

    const val SOIL_DRY = "soil_dry"
    const val SOIL_WET = "soil_wet"
    const val SUN = "sun"
    const val MOON = "moon"
    const val CLOUD = "cloud"

    private val cache = HashMap<String, Bitmap?>()
    private val frameCache = HashMap<String, List<Bitmap>>()

    /** Returns the bitmap for [name], or null if no such drawable exists. */
    fun get(context: Context, name: String): Bitmap? {
        cache[name]?.let { return it }
        if (cache.containsKey(name)) return null // cached miss

        val res = context.resources
        val id = res.getIdentifier(name, "drawable", context.packageName)
        val bmp = if (id != 0) load(res, id) else null
        cache[name] = bmp
        return bmp
    }

    /**
     * Returns the animation frames for [key]. Looks for a numbered sequence
     * "<key>_1", "<key>_2", ... (any length up to [MAX_FRAMES]); if none exist,
     * falls back to a single "<key>" bitmap; if that's missing too, returns an
     * empty list. So `farmer_walk_1..4` animates, but a lone `farmer_walk` (or
     * nothing) still works.
     */
    fun frames(context: Context, key: String): List<Bitmap> {
        frameCache[key]?.let { return it }
        val res = context.resources
        val pkg = context.packageName

        // decode the raw sequence (no per-frame trim yet)
        val raws = ArrayList<Bitmap>()
        var i = 1
        while (i <= MAX_FRAMES) {
            val id = res.getIdentifier("${key}_$i", "drawable", pkg)
            if (id == 0) break
            decodeRaw(res, id)?.let { raws.add(it) }
            i++
        }

        val list: List<Bitmap> = if (raws.isEmpty()) {
            // no numbered sequence -> single bitmap fallback (trimmed on its own)
            get(context, key)?.let { listOf(it) } ?: emptyList()
        } else {
            trimTogether(raws)
        }
        frameCache[key] = list
        return list
    }

    private fun decodeRaw(res: android.content.res.Resources, id: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        BitmapFactory.decodeResource(res, id, opts)
    } catch (t: Throwable) {
        null
    }

    private fun load(res: android.content.res.Resources, id: Int): Bitmap? =
        trimTransparent(decodeRaw(res, id))

    /** [minX, minY, maxX, maxY] of non-transparent content, or null if none. */
    private fun contentBounds(src: Bitmap): IntArray? {
        val w = src.width
        val h = src.height
        val row = IntArray(w)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if ((row[x] ushr 24) and 0xFF > 8) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX < minX || maxY < minY) null else intArrayOf(minX, minY, maxX, maxY)
    }

    /**
     * Crops away fully-transparent borders so a sprite's visible art fills its
     * box (stops single sprites hovering / scaling inconsistently).
     */
    private fun trimTransparent(src: Bitmap?): Bitmap? {
        if (src == null) return null
        val b = contentBounds(src) ?: return src
        if (b[0] == 0 && b[1] == 0 && b[2] == src.width - 1 && b[3] == src.height - 1) return src
        return Bitmap.createBitmap(src, b[0], b[1], b[2] - b[0] + 1, b[3] - b[1] + 1)
    }

    /**
     * Crops every frame to the SAME (union) bounding box across the whole
     * sequence, so shared padding is removed but limbs that move between frames
     * don't cause the sprite to jump/bob. Assumes frames share one canvas size.
     */
    private fun trimTogether(raws: List<Bitmap>): List<Bitmap> {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = -1; var maxY = -1
        for (b in raws) {
            contentBounds(b)?.let {
                if (it[0] < minX) minX = it[0]
                if (it[1] < minY) minY = it[1]
                if (it[2] > maxX) maxX = it[2]
                if (it[3] > maxY) maxY = it[3]
            }
        }
        if (maxX < minX || maxY < minY) return raws // all transparent -> leave as-is
        // clamp union box to the smallest frame so createBitmap never overruns
        val w = raws.minOf { it.width }
        val h = raws.minOf { it.height }
        val x0 = minX.coerceIn(0, w - 1)
        val y0 = minY.coerceIn(0, h - 1)
        val cw = (maxX - x0 + 1).coerceIn(1, w - x0)
        val ch = (maxY - y0 + 1).coerceIn(1, h - y0)
        return raws.map { Bitmap.createBitmap(it, x0, y0, cw, ch) }
    }
}
