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
        val list = ArrayList<Bitmap>()
        var i = 1
        while (i <= MAX_FRAMES) {
            val id = res.getIdentifier("${key}_$i", "drawable", pkg)
            if (id == 0) break
            load(res, id)?.let { list.add(it) }
            i++
        }
        if (list.isEmpty()) get(context, key)?.let { list.add(it) }
        frameCache[key] = list
        return list
    }

    private fun load(res: android.content.res.Resources, id: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        trimTransparent(BitmapFactory.decodeResource(res, id, opts))
    } catch (t: Throwable) {
        null
    }

    /**
     * Crops away fully-transparent borders so a sprite's visible art fills its
     * box. Without this, padding baked into the PNG makes things look like they
     * hover and makes scaling inconsistent between assets.
     */
    private fun trimTransparent(src: Bitmap?): Bitmap? {
        if (src == null) return null
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
        if (maxX < minX || maxY < minY) return src // fully transparent or opaque edge-to-edge
        if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) return src
        return Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }
}
