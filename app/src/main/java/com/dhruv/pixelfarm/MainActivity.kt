package com.dhruv.pixelfarm

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * The home screen itself. onResume/onPause double as the "is the launcher
 * foregrounded" signal that [FarmRepository] needs -- no separate detection
 * mechanism required.
 */
class MainActivity : Activity() {

    private lateinit var drawerContainer: View
    private lateinit var appGrid: RecyclerView
    private lateinit var handle: View
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerContainer = findViewById(R.id.drawerContainer)
        appGrid = findViewById(R.id.appGrid)
        handle = findViewById(R.id.drawerHandle)

        appGrid.layoutManager = GridLayoutManager(this, 4)
        appGrid.adapter = AppListAdapter(loadInstalledApps()) { app ->
            launchApp(app)
            closeDrawer()
        }

        handle.setOnClickListener { toggleDrawer() }
        drawerContainer.setOnClickListener { closeDrawer() }

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val deltaY = e2.y - e1.y
                if (deltaY < -SWIPE_THRESHOLD && abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    openDrawer()
                    return true
                }
                return false
            }
        })

        requestNotificationPermissionIfNeeded()
        FarmTrackerService.start(this)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        FarmRepository.onLauncherResumed()
    }

    override fun onPause() {
        super.onPause()
        FarmRepository.onLauncherPaused()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A launcher has nowhere to go back to.
    }

    private fun openDrawer() {
        drawerContainer.visibility = View.VISIBLE
    }

    private fun closeDrawer() {
        drawerContainer.visibility = View.GONE
    }

    private fun toggleDrawer() {
        if (drawerContainer.visibility == View.VISIBLE) closeDrawer() else openDrawer()
    }

    private fun loadInstalledApps(): List<AppListAdapter.AppInfo> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { info ->
                AppListAdapter.AppInfo(
                    label = info.loadLabel(pm).toString(),
                    packageName = info.activityInfo.packageName,
                    activityName = info.activityInfo.name,
                    icon = info.loadIcon(pm)
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun launchApp(app: AppListAdapter.AppInfo) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(app.packageName, app.activityName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
        }
    }

    companion object {
        private const val SWIPE_THRESHOLD = 100
        private const val SWIPE_VELOCITY_THRESHOLD = 100
        private const val REQUEST_NOTIFICATION_PERMISSION = 42
    }
}
