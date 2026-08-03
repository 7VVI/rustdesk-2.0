package com.carriez.flutter_hbb

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent as KeyEventAndroid
import kotlin.math.max

/**
 * In-app input injection for the rdsdk embedding.
 *
 * Mirrors the relevant subset of [InputService] but injects pointer/key events
 * directly into the host Activity's DecorView via dispatchTouchEvent /
 * dispatchKeyEvent, so it works WITHOUT the system Accessibility permission.
 *
 * Limitations (by design — the host app can switch to `accessibility` mode for
 * cross-app control):
 * - Only events landing inside the host app's own window are delivered;
 *   anything outside is silently dropped.
 * - No system-level gestures (Home/Recents/global Back, notification shade).
 *
 * Touch/pointer coordinates are scaled by [SCREEN_INFO.scale] exactly like
 * [InputService], so the remote (control-end) coordinates map to the same
 * on-screen position.
 *
 * The mask constants are kept in sync with [InputService].
 */
object RdInAppInputService : RdInputHandler {

    private const val TAG = "rd-inapp-input"

    override val isReady: Boolean
        get() = activity != null

    // ---- pointer masks (same values as InputService.kt) ----
    private const val LEFT_DOWN = 9
    private const val LEFT_MOVE = 8
    private const val LEFT_UP = 10
    private const val RIGHT_UP = 18
    // wheel/back are not supported in in-app mode (silently dropped)

    // ---- touch masks ----
    private const val TOUCH_PAN_START = 4
    private const val TOUCH_PAN_UPDATE = 5
    private const val TOUCH_PAN_END = 6

    /** Set by [RDMainActivity.onCreate]; cleared on onDestroy. */
    @Volatile
    var activity: Activity? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Track the down-time of the current gesture so that MOVE/UP events form a
    // continuous gesture that Android's GestureDetector / Flutter recognize.
    private var downTime = 0L
    // Last injected position (scaled), used for touch pan which reports deltas.
    private var lastX = 0f
    private var lastY = 0f
    // Tracked mouse position (scaled). The PC control-end sends mouse-move
    // events (mask=0) to report cursor position; click events (DOWN/UP) carry
    // x=0,y=0 and rely on the previously tracked position — exactly like the
    // original InputService which keeps mouseX/mouseY across events.
    private var mouseX = 0f
    private var mouseY = 0f
    // Whether a touch gesture is in progress (between DOWN and UP).
    private var pointerDown = false

    /**
     * Mouse input. Mirrors InputService.onMouseInput's position-tracking:
     * plain moves (mask=0) and LEFT_MOVE update [mouseX]/[mouseY]; click
     * events (LEFT_DOWN/LEFT_UP) use the tracked position because the
     * control-end sends them with x=0,y=0.
     */
    override fun onMouseInput(mask: Int, x: Int, y: Int) {
        // Update tracked position from any event that carries real coords.
        // The control-end's down/up events send x=0,y=0, so only update when
        // the incoming coords are non-zero (same guard as InputService which
        // only updates on mask==0 / LEFT_MOVE).
        if (x != 0 || y != 0) {
            mouseX = max(0, x) * SCREEN_INFO.scale.toFloat()
            mouseY = max(0, y) * SCREEN_INFO.scale.toFloat()
        }
        when (mask) {
            0, LEFT_MOVE -> {
                // Plain move or move-with-left-button. If a gesture is active
                // (button held), dispatch ACTION_MOVE so drags work; otherwise
                // just track position (no dispatch).
                if (pointerDown && mask == LEFT_MOVE) {
                    Log.d(TAG, "onMouseInput drag MOVE -> mx=$mouseX my=$mouseY")
                    dispatchPointer(MotionEvent.ACTION_MOVE, mouseX, mouseY)
                }
            }
            LEFT_DOWN -> {
                pointerDown = true
                Log.d(TAG, "onMouseInput DOWN -> mx=$mouseX my=$mouseY (raw x=$x y=$y)")
                dispatchPointer(MotionEvent.ACTION_DOWN, mouseX, mouseY)
            }
            LEFT_UP, RIGHT_UP -> {
                pointerDown = false
                Log.d(TAG, "onMouseInput UP -> mx=$mouseX my=$mouseY (raw x=$x y=$y)")
                dispatchPointer(MotionEvent.ACTION_UP, mouseX, mouseY)
            }
            else -> {
                Log.d(TAG, "onMouseInput: unsupported mask=$mask, dropping")
            }
        }
    }

    /**
     * Touch input. Unlike mouse, touch pan reports:
     *  - START: absolute start coords
     *  - UPDATE: delta to subtract from current position
     *  - END: final absolute coords
     */
    override fun onTouchInput(mask: Int, x: Int, y: Int) {
        when (mask) {
            TOUCH_PAN_START -> {
                val sx = max(0, x) * SCREEN_INFO.scale.toFloat()
                val sy = max(0, y) * SCREEN_INFO.scale.toFloat()
                lastX = sx
                lastY = sy
                dispatchPointer(MotionEvent.ACTION_DOWN, sx, sy)
            }
            TOUCH_PAN_UPDATE -> {
                // pan update carries a delta (see InputService.onTouchInput)
                var nx = lastX - x * SCREEN_INFO.scale.toFloat()
                var ny = lastY - y * SCREEN_INFO.scale.toFloat()
                if (nx < 0f) nx = 0f
                if (ny < 0f) ny = 0f
                lastX = nx
                lastY = ny
                dispatchPointer(MotionEvent.ACTION_MOVE, nx, ny)
            }
            TOUCH_PAN_END -> {
                var nx = max(0, x) * SCREEN_INFO.scale.toFloat()
                var ny = max(0, y) * SCREEN_INFO.scale.toFloat()
                lastX = nx
                lastY = ny
                dispatchPointer(MotionEvent.ACTION_UP, nx, ny)
            }
            else -> {}
        }
    }

    private fun dispatchPointer(action: Int, x: Float, y: Float) {
        // Prefer the lifecycle-tracked foreground Activity (covers the whole
        // host app: device list, settings, business pages, ...). Fall back to
        // the RDServerActivity reference (the RustDesk page itself) so that
        // the SDK still works before the tracker sees a resume.
        val act = RdForegroundActivityTracker.currentActivity ?: activity ?: run {
            Log.w(TAG, "dispatchPointer: no foreground activity, dropping action=$action")
            return
        }
        Log.d(TAG, "dispatchPointer target=${act.javaClass.simpleName} action=$action x=$x y=$y")
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) {
            downTime = now
        }
        val me = MotionEvent.obtain(
            downTime, now, action, x, y,
            1f, // pressure
            1f, // size
            0,  // metaState
            1f, // xPrecision
            1f, // yPrecision
            0,  // deviceId (default)
            0   // edgeFlags
        )
        me.source = InputDevice.SOURCE_TOUCHSCREEN
        // Dialogs/BottomSheets/popups live in separate Windows stacked above
        // the Activity's main window. Find the top-most window (across the
        // activity main window + any dialog/popup windows registered by the
        // tracker) whose visible bounds contain (x,y) and dispatch there.
        // This makes taps on a popup (e.g. "Add device" AlertDialog) land in
        // the popup, not the dimmed activity behind it.
        val candidates = mutableListOf<android.view.View>()
        act.window?.decorView?.let { candidates.add(it) }
        candidates.addAll(RdForegroundActivityTracker.snapshotDialogDecorViews())
        val dv = candidates.reversed().firstOrNull { v ->
            v.isShown && hitTestView(v, x, y)
        } ?: candidates.lastOrNull()
        Log.d(TAG, "dispatchPointer action=$action x=$x y=$y windows=${candidates.size} target=${dv?.javaClass?.simpleName}")
        if (dv == null) {
            me.recycle()
            return
        }
        // Translate screen coordinates to the target view's LOCAL coordinates.
        // The activity's main decorView sits at (0,0) full-screen so the offset
        // is 0, but a dialog is a floating window at a non-zero origin and its
        // decorView expects local coords. Without this translation the touch
        // lands outside the dialog box and AlertDialog dismisses it as an
        // outside touch (and buttons/EditText never receive the event).
        val loc = IntArray(2)
        dv.getLocationOnScreen(loc)
        me.offsetLocation(-loc[0].toFloat(), -loc[1].toFloat())
        mainHandler.post {
            try {
                val handled = dv.dispatchTouchEvent(me)
                Log.d(TAG, "dispatchTouchEvent result=$handled localX=${me.x} localY=${me.y}")
            } catch (e: Exception) {
                Log.e(TAG, "dispatchTouchEvent failed: ${e.message}")
            } finally {
                me.recycle()
            }
        }
    }

    private fun hitTestView(view: android.view.View, x: Float, y: Float): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + view.width &&
               y >= loc[1] && y <= loc[1] + view.height
    }

    /**
     * Key event input. Parses the protobuf KeyEvent (same wire format as
     * InputService.onKeyEvent) and dispatches an android.view.KeyEvent to the
     * DecorView; text input is committed via the focused view's InputConnection.
     */
    override fun onKeyEvent(data: ByteArray) {
        try {
            val keyEvent = hbb.MessageOuterClass.KeyEvent.parseFrom(data)
            // Determine text to commit. Works across keyboard modes: the
            // control-end sends chr (unicode) in Legacy/Translate modes and
            // seq for multi-char input. Previously only Legacy was handled,
            // so PC keyboards (default Translate) produced no text.
            var textToCommit: String? = null
            if (keyEvent.hasSeq()) {
                textToCommit = keyEvent.seq
            } else if (keyEvent.hasChr() && (keyEvent.getDown() || keyEvent.getPress())) {
                val chr = keyEvent.getChr()
                if (chr != 0) {
                    textToCommit = String(Character.toChars(chr))
                }
            }

            val androidEvent: KeyEventAndroid? =
                if (Build.VERSION.SDK_INT < 33 || textToCommit == null) {
                    hbb.KeyEventConverter.toAndroidKeyEvent(keyEvent)
                } else {
                    null
                }

            mainHandler.post {
                val act = RdForegroundActivityTracker.currentActivity ?: activity ?: return@post
                // Find the focused view across all windows (activity + dialogs).
                // A dialog's EditText is focused inside the dialog window, not
                // the activity window, so check dialog windows first.
                val candidates = mutableListOf<android.view.View>()
                candidates.addAll(RdForegroundActivityTracker.snapshotDialogDecorViews())
                act.window?.decorView?.let { candidates.add(it) }
                val dv = candidates.reversed().firstOrNull { it.isShown } ?: act.window?.decorView ?: return@post
                try {
                    if (textToCommit != null) {
                        // Commit text through the focused view's InputConnection.
                        // Search the focused view in the top-most shown window.
                        val focused = findFocusedTextView(act)
                        if (focused != null && focused.onCheckIsTextEditor()) {
                            val ic = focused.onCreateInputConnection(
                                android.view.inputmethod.EditorInfo()
                            )
                            ic?.commitText(textToCommit, 1)
                            Log.d(TAG, "commitText '$textToCommit' to ${focused.javaClass.simpleName}")
                        } else {
                            // Fallback: dispatch the text as key events.
                            Log.d(TAG, "no text editor focused, dispatching key event")
                            if (androidEvent != null) dv.dispatchKeyEvent(androidEvent)
                        }
                    }
                    if (androidEvent != null) {
                        dv.dispatchKeyEvent(androidEvent)
                        if (keyEvent.getPress()) {
                            val up = KeyEventAndroid(
                                KeyEventAndroid.ACTION_UP,
                                androidEvent.keyCode
                            )
                            dv.dispatchKeyEvent(up)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "dispatchKeyEvent failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onKeyEvent parse failed: ${e.message}")
        }
    }

    /**
     * Find the currently focused View that is a text editor, checking dialog
     * windows first (their decorView has its own focus), then the activity.
     */
    private fun findFocusedTextView(act: android.app.Activity): android.view.View? {
        // Dialog windows first (most likely to have the focused EditText).
        for (dv in RdForegroundActivityTracker.snapshotDialogDecorViews().reversed()) {
            val f = dv.findFocus()
            if (f != null) return f
        }
        return act.currentFocus
    }
}
