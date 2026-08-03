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

    /**
     * Mouse input. mask selects the action; x/y are absolute screen coords
     * (pre-scale, same convention as InputService.onMouseInput).
     */
    override fun onMouseInput(mask: Int, x: Int, y: Int) {
        val action = when (mask) {
            LEFT_DOWN -> MotionEvent.ACTION_DOWN
            LEFT_UP, RIGHT_UP -> MotionEvent.ACTION_UP
            LEFT_MOVE -> MotionEvent.ACTION_MOVE
            // wheel / back / unsupported → drop silently
            else -> {
                Log.d(TAG, "onMouseInput: unsupported mask=$mask, dropping")
                return
            }
        }
        val sx = max(0, x) * SCREEN_INFO.scale.toFloat()
        val sy = max(0, y) * SCREEN_INFO.scale.toFloat()
        Log.d(TAG, "onMouseInput mask=$mask x=$x y=$y scale=${SCREEN_INFO.scale} -> sx=$sx sy=$sy action=$action activity=${activity != null}")
        dispatchPointer(action, sx, sy)
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
        val act = activity ?: run {
            Log.w(TAG, "dispatchPointer: activity is null, dropping action=$action")
            return
        }
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
        Log.d(TAG, "dispatchPointer action=$action x=$x y=$y decorView=${act.window?.decorView != null}")
        mainHandler.post {
            try {
                // Prefer dispatching to the content view (FlutterView lives
                // inside it); fall back to decorView. Dispatching to decorView
                // should also work (it propagates to children) but the content
                // view is a more direct target.
                val dv = act.window?.decorView
                if (dv != null) {
                    val handled = dv.dispatchTouchEvent(me)
                    Log.d(TAG, "dispatchTouchEvent(decorView) result=$handled")
                } else {
                    Log.e(TAG, "decorView is null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "dispatchTouchEvent failed: ${e.message}")
            } finally {
                me.recycle()
            }
        }
    }

    /**
     * Key event input. Parses the protobuf KeyEvent (same wire format as
     * InputService.onKeyEvent) and dispatches an android.view.KeyEvent to the
     * DecorView; text input is committed via the focused view's InputConnection.
     */
    override fun onKeyEvent(data: ByteArray) {
        try {
            val keyEvent = hbb.MessageOuterClass.KeyEvent.parseFrom(data)
            var textToCommit: String? = null
            if (keyEvent.hasSeq()) {
                textToCommit = keyEvent.seq
            } else if (keyEvent.getMode() == hbb.MessageOuterClass.KeyboardMode.Legacy) {
                if (keyEvent.hasChr() && (keyEvent.getDown() || keyEvent.getPress())) {
                    val chr = keyEvent.getChr()
                    if (chr != 0) {
                        textToCommit = String(Character.toChars(chr))
                    }
                }
            }

            val androidEvent: KeyEventAndroid? =
                if (Build.VERSION.SDK_INT < 33 || textToCommit == null) {
                    hbb.KeyEventConverter.toAndroidKeyEvent(keyEvent)
                } else {
                    null
                }

            mainHandler.post {
                val act = activity ?: return@post
                val dv = act.window?.decorView ?: return@post
                try {
                    if (textToCommit != null) {
                        // Commit text through the focused view's InputConnection.
                        val focused = act.currentFocus
                        if (focused != null && focused.onCheckIsTextEditor()) {
                            val ic = focused.onCreateInputConnection(
                                android.view.inputmethod.EditorInfo()
                            )
                            ic?.commitText(textToCommit, 1)
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
}
