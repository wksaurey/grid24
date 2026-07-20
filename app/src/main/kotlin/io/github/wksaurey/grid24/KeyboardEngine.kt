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

    /** Board background color. The host paints it across the full window —
     *  including the nav-inset band the transparent system strip sits over
     *  (API 35+ edge-to-edge) — so appearance stays engine-owned. */
    val backgroundColor: Int

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

    /** Input session ended / view hidden: cancel timers, drop all pointer state. */
    fun onFinishInput()

    /** Live-tunable constants + feature toggles changed (settings lab). The host
     *  re-reads prefs on every session start and pushes them here; engines apply
     *  cheaply when nothing changed. */
    fun applyTunables(t: Tunables)
}

/** What an engine asks the host to do against the current input field. */
sealed class EngineCommand {
    data class CommitText(val text: String) : EngineCommand()

    /** Deletes the selection if one exists, else one char before the cursor. */
    object Backspace : EngineCommand()

    /** Enter, resolved host-side: fields declaring an IME action (search/go/
     *  send/...) get performEditorAction; multiline/plain fields get "\n". */
    object Enter : EngineCommand()

    /** Copy/Cut/Paste via the field's own context actions. Copy/Cut are
     *  intentional: they DO write the system clipboard (the clip listener
     *  mirrors them into the in-keyboard slots automatically). */
    object Copy : EngineCommand()
    object Cut : EngineCommand()
    object Paste : EngineCommand()

    /** Store the current selection into clip register [index] (keyboard-local;
     *  the system clipboard is untouched). cut=true also removes it from the
     *  text. No-op without a selection or in password fields. */
    data class StoreClip(val index: Int, val cut: Boolean) : EngineCommand()

    /** Move the cursor by delta chars, collapsing any selection. */
    data class MoveCursor(val delta: Int) : EngineCommand()

    data class SetSelection(val a: Int, val b: Int) : EngineCommand()

    /** Execute atomically inside beginBatchEdit/endBatchEdit (no flicker,
     *  single onUpdateSelection) — e.g. double-space → delete space + ". ". */
    data class Batch(val commands: List<EngineCommand>) : EngineCommand()
}

/**
 * Semantic haptic events; the host maps them to HapticFeedbackConstants so the
 * system keyboard-haptics toggle is respected and no VIBRATE permission is needed.
 */
enum class HapticKind { COMMIT, HOLD_FLIP, DRAG_TICK, CONFIRM, CANCEL }

/** A cancellable scheduled action (see EngineHost.schedule). */
interface Scheduled {
    fun cancel()
}

/** Host services available to an engine. Implemented by Grid24Ime + BoardView. */
interface EngineHost {
    fun execute(cmd: EngineCommand)
    fun haptic(kind: HapticKind)

    /** Ask for a redraw (engines have no View reference). */
    fun requestRender()

    /**
     * Run [action] after [delayMs] on the main thread. Timers are host-provided
     * (not Handler-in-engine) so gesture timing stays JVM-testable; repeat loops
     * self-chain by rescheduling inside their action.
     */
    fun schedule(delayMs: Long, action: () -> Unit): Scheduled

    /**
     * Up to [n] chars before the cursor, or null if unknown/no field. The one
     * sanctioned field-content peek (CLAUDE.md: double-space period check).
     */
    fun textBeforeCursor(n: Int): CharSequence?

    /** True when a sentence-cap applies at the cursor right now (field start,
     *  after terminal punctuation + space). Host answers via getCursorCapsMode;
     *  always false in password fields / fields without an InputConnection. */
    fun autoCapsNow(): Boolean

    /** The 8 clip REGISTERS, by stable slot position (null = empty). Manually
     *  stored via StoreClip; auto-fed (copies made anywhere via the primary-clip
     *  listener, and selections destroyed by typing/DELETE) into the first empty
     *  slot, evicting the oldest entry only when all are full. Never contains
     *  password-field content or sensitive-flagged clips. */
    fun clips(): List<String?>

    /**
     * Total field text length, or null if the field won't say (some apps
     * implement getExtractedText badly). Queried once per drag engagement to
     * clamp selection bounds — never polled.
     */
    fun textLength(): Int?
}
