package io.github.wksaurey.mosaic

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets

/**
 * The single Canvas surface. Owns exactly two jobs: demux raw MotionEvents into
 * the engine's per-pointer stream, and delegate drawing. No key hierarchy, no
 * gesture logic — that is all engine-side (CLAUDE.md architecture).
 *
 * The bottom nav-bar inset is consumed here as real padding — this is the
 * prototype's "dead-zone gap below the board", adapting to 3-button vs gesture nav.
 */
class BoardView(context: Context, private val engine: KeyboardEngine) : View(context) {

    private var bottomInset = 0

    init {
        setOnApplyWindowInsetsListener { _, insets ->
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            if (nav != bottomInset) {
                bottomInset = nav
                requestLayout()
            }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Inset dispatch to IME views is unreliable; without this the board can
        // show with bottomInset=0 and sit under the system globe/chevron strip.
        requestApplyInsets()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = engine.measureHeight(w, resources.displayMetrics.density) + bottomInset
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        // Full bounds incl. the nav-inset band: on API 35+ the system IME strip
        // (chevron/globe/pill) is transparent and shows whatever we paint here.
        canvas.drawColor(engine.backgroundColor)
        engine.render(canvas, width, height - bottomInset, resources.displayMetrics.density)
    }

    @SuppressLint("ClickableViewAccessibility") // keyboard surface; keys aren't child views
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                engine.onPointerDown(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                // No action index on MOVE — every active pointer may have moved.
                for (i in 0 until event.pointerCount) {
                    engine.onPointerMove(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                engine.onPointerUp(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until event.pointerCount) {
                    engine.onPointerCancel(event.getPointerId(i))
                }
            }
        }
        return true
    }

    // Haptics moved host-side (MosaicIme.haptic): intensity control needs
    // VibrationEffect, which performHapticFeedback can't express.
}
