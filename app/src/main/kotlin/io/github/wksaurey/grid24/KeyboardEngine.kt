package io.github.wksaurey.grid24

import android.graphics.Canvas
import android.view.inputmethod.EditorInfo

/**
 * The plumbing/engine seam (CLAUDE.md "Pluggable engine boundary").
 *
 * Everything Android — the IME service, InputConnection, haptics, insets, raw
 * MotionEvent demux — lives on the host side and is written once. Everything a
 * keyboard prototype varies — gesture grammar, layout data, tuned constants,
 * rendering — lives behind this interface. Engines never touch Android input
 * APIs directly; they emit [EngineCommand]s and the host executes them.
 */
interface KeyboardEngine {

    /** Board height in px for the given width. Called from BoardView.onMeasure. */
    fun measureHeight(widthPx: Int, density: Float): Int

    /** Draw the whole board. Canvas is already clipped to widthPx × heightPx. */
    fun render(canvas: Canvas, widthPx: Int, heightPx: Int, density: Float)

    /*
     * Pointer stream. Mirrors the prototype's pointerdown/move/up handlers:
     * ids are stable per finger (MotionEvent pointer *id*, never index),
     * coordinates are view-local px, t is MotionEvent.eventTime (ms).
     */
    fun onPointerDown(id: Int, x: Float, y: Float, t: Long)
    fun onPointerMove(id: Int, x: Float, y: Float, t: Long)
    fun onPointerUp(id: Int, x: Float, y: Float, t: Long)

    /** Gesture stolen (system nav, etc.). Abandon this pointer, commit nothing. */
    fun onPointerCancel(id: Int)

    /**
     * A new input session started (fires repeatedly, incl. restarting=true —
     * reset transient state explicitly every time). info may be null.
     */
    fun onStartInput(info: EditorInfo?, restarting: Boolean)

    /**
     * Field selection changed (source of truth for cursor/selection — the user
     * can touch the text directly). Includes echoes of the engine's own
     * SetSelection commands.
     */
    fun onSelectionUpdate(selStart: Int, selEnd: Int)
}

/** What an engine asks the host to do against the current input field. */
sealed class EngineCommand {
    data class CommitText(val text: String) : EngineCommand()

    /** Deletes the selection if one exists, else one char before the cursor. */
    object Backspace : EngineCommand()

    /** Move the cursor by delta chars, collapsing any selection. */
    data class MoveCursor(val delta: Int) : EngineCommand()

    data class SetSelection(val a: Int, val b: Int) : EngineCommand()
}

/**
 * Semantic haptic events; the host maps them to HapticFeedbackConstants so the
 * system keyboard-haptics toggle is respected and no VIBRATE permission is needed.
 */
enum class HapticKind { COMMIT, HOLD_FLIP, DRAG_TICK, CONFIRM, CANCEL }

/** Host services available to an engine. Implemented by Grid24Ime + BoardView. */
interface EngineHost {
    fun execute(cmd: EngineCommand)
    fun haptic(kind: HapticKind)

    /** Ask for a redraw (engines have no View reference). */
    fun requestRender()
}
