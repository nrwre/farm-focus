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
    const val FARMER_PLOUGH = "farmer_plough"
    const val FARMER_WATER = "farmer_water"
    const val FARMER_NAP = "farmer_nap"

    val CROP_STAGE = arrayOf(
        "crop_0_seed", "crop_1_sprout", "crop_2_growing", "crop_3_mature", "crop_4_ripe"
    )

    const val SOIL_DRY = "soil_dry"
    const val SOIL_WET = "soil_wet"
    const val SUN = "sun"
    const val MOON = "moon"
    const val CLOUD = "cloud"

    private val cache = HashMap<String, Bitmap?>()

    /** Returns the bitmap for [name], or null if no such drawable exists. */
    fun get(context: Context, name: String): Bitmap? {
        cache[name]?.let { return it }
        if (cache.containsKey(name)) return null // cached miss

        val res = context.resources
        val id = res.getIdentifier(name, "drawable", context.packageName)
        val bmp = if (id != 0) {
            try {
                BitmapFactory.decodeResource(res, id)
            } catch (t: Throwable) {
                null
            }
        } else {
            null
        }
        cache[name] = bmp
        return bmp
    }
}
