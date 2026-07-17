package io.github.wksaurey.grid24

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.inputmethod.EditorInfo

/**
 * The Grid24 keyboard engine — the port target for reference/grid24-proto.html
 * (the executable spec; when in doubt, open it and do what it does).
 *
 * M0 state: flat board placeholder, any tap commits "a". The prototype's state
 * machine (touches map, layers, holds, drag physics) lands here through M1–M6.
 */
class Grid24Engine(private val host: EngineHost) : KeyboardEngine {

    /* Tuned constants — port of the prototype's values (css-px ≈ dp; convert
     * via density at use sites). These numbers encode real debugging: keep them. */
    object Config {
        const val TAP_T = 18f          // dp — travel below this = tap
        // GESTURE_T, HOLD_MS, FLICK_MS, SPACE_HOLD_MS, DEL_* land with M2–M4.
        const val BOARD_HEIGHT = 280f  // dp — M0 placeholder; M1 derives from row clamps
    }

    private data class PointerState(
        val x0: Float,
        val y0: Float,
        val t0: Long,
        var maxTravel: Float = 0f,
    )

    /** Per-pointer state, keyed by stable pointer id — the prototype's `touches` map. */
    private val touches = HashMap<Int, PointerState>()

    private val bgPaint = Paint().apply { color = Color.parseColor("#191c22") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9a958a")
        textAlign = Paint.Align.CENTER
    }

    override fun measureHeight(widthPx: Int, density: Float): Int =
        (Config.BOARD_HEIGHT * density).toInt()

    override fun render(canvas: Canvas, widthPx: Int, heightPx: Int, density: Float) {
        canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), bgPaint)
        labelPaint.textSize = 14f * density
        canvas.drawText("GRID24 · M0 · tap = a", widthPx / 2f, heightPx / 2f, labelPaint)
    }

    override fun onPointerDown(id: Int, x: Float, y: Float, t: Long) {
        touches[id] = PointerState(x, y, t)
    }

    override fun onPointerMove(id: Int, x: Float, y: Float, t: Long) {
        val st = touches[id] ?: return
        val travel = Math.hypot((x - st.x0).toDouble(), (y - st.y0).toDouble()).toFloat()
        if (travel > st.maxTravel) st.maxTravel = travel
    }

    override fun onPointerUp(id: Int, x: Float, y: Float, t: Long) {
        val st = touches.remove(id) ?: return
        val density = 3f // M0 shortcut; M1 threads real density into pointer handling
        if (st.maxTravel <= Config.TAP_T * density) {
            host.execute(EngineCommand.CommitText("a"))
            host.haptic(HapticKind.COMMIT)
        }
    }

    override fun onPointerCancel(id: Int) {
        touches.remove(id) // abandon, commit nothing
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        touches.clear() // reset transient state on every session, incl. restarts
        host.requestRender()
    }

    override fun onSelectionUpdate(selStart: Int, selEnd: Int) {
        // Selection-aware behavior (quick-swipe collapse, drag ratchet) lands at M3–M4.
    }
}
