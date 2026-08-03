package com.carriez.flutter_hbb

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log

/**
 * Tracks the host application's current foreground (resumed) Activity so that
 * [RdInAppInputService] can dispatch remote input events to whatever Activity
 * is on screen — not just the RustDesk [RDServerActivity].
 *
 * This is what lets the in-app input mode control the **entire host app**
 * (device list, settings, business pages, …) without any extra permission.
 *
 * Registered once via [register]; [Application.registerActivityLifecycleCallbacks]
 * covers every Activity in the process, including the host app's own Activities.
 */
object RdForegroundActivityTracker {

    private const val TAG = "rd-fg-tracker"

    @Volatile
    var currentActivity: Activity? = null
        private set

    @Volatile
    private var registered = false

    /**
     * Register the lifecycle callbacks. Call once from the SDK entry Activity
     * (e.g. [RDMainActivity.onCreate]). Safe to call multiple times.
     */
    fun register(app: Application) {
        if (registered) return
        registered = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
                Log.d(TAG, "foreground -> ${activity.javaClass.simpleName}")
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentActivity === activity) {
                    currentActivity = null
                    Log.d(TAG, "background <- ${activity.javaClass.simpleName}")
                }
            }

            // Unused callbacks — required by the interface.
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        Log.d(TAG, "registered on $app")
    }
}
