package com.carriez.flutter_hbb

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window

/**
 * Tracks the host application's current foreground (resumed) Activity so that
 * [RdInAppInputService] can dispatch remote input events to whatever Activity
 * is on screen — not just the RustDesk [RDServerActivity].
 *
 * Also tracks dialog/popup windows shown on top of an Activity so that taps on
 * an AlertDialog (e.g. "Add device") land inside the dialog, not the dimmed
 * Activity behind it.
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

    // ------------------------------------------------------------------
    // Dialog / popup window tracking
    // ------------------------------------------------------------------
    //
    // Dialogs (AlertDialog, BottomSheetDialog, …) create their own Window
    // whose decorView is independent of the Activity's main window. Touch
    // coordinates dispatched to the Activity's decorView never reach the
    // dialog. To support taps/typing inside dialogs we need to know about
    // their decorView too.
    //
    // Strategy: the host app calls [registerDialogWindow] when it shows a
    // dialog, and [unregisterDialogWindow] when it dismisses. To stay
    // transparent (host code unchanged) we also auto-scan: every time input
    // is dispatched, [collectDialogDecorViews] walks the activity's window
    // manager's default display for shown views — but since that API is
    // restricted, the practical auto-detect uses a global layout listener on
    // the activity decorView to catch newly-added child windows.

    /** Public list consulted by [RdInAppInputService]; cleared per dispatch cycle. */
    private val dialogDecorViews: MutableList<View> = mutableListOf()

    /** Manually register a dialog/popup decorView (called by host or auto-detect). */
    fun registerDialogWindow(decorView: View) {
        synchronized(dialogDecorViews) {
            if (!dialogDecorViews.contains(decorView)) {
                dialogDecorViews.add(decorView)
                Log.d(TAG, "dialog window + (total=${dialogDecorViews.size})")
            }
        }
    }

    /** Manually unregister a dialog/popup decorView. */
    fun unregisterDialogWindow(decorView: View) {
        synchronized(dialogDecorViews) {
            if (dialogDecorViews.remove(decorView)) {
                Log.d(TAG, "dialog window - (total=${dialogDecorViews.size})")
            }
        }
    }

    /** Snapshot of currently registered dialog decorViews (for dispatch). */
    fun snapshotDialogDecorViews(): List<View> = synchronized(dialogDecorViews) {
        dialogDecorViews.filter { it.isShown }
    }
}
