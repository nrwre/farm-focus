package com.dhruv.pixelfarm

import android.app.Application

/** Ensures [FarmRepository] is initialized before any Activity or Service touches it. */
class PixelFarmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FarmRepository.init(this)
    }
}
