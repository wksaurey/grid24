package io.github.wksaurey.grid24

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * The InputMethodService — the only Android entry point. Bridges the active
 * KeyboardEngine to the current InputConnection and keeps field selection state
 * synced via onUpdateSelection (the source of truth; never assume).
 */
class Grid24Ime : InputMethodService(), EngineHost {

    private lateinit var engine: KeyboardEngine
    private var boardView: BoardView? = null

    // Field selection as last reported by the host app (onUpdateSelection).
    private var selStart = 0
    private var selEnd = 0

    override fun onCreate() {
        super.onCreate()
        engine = createEngine()
    }

    /** Engine selection is a build-time constant for v1 (CLAUDE.md). */
    private fun createEngine(): KeyboardEngine = Grid24Engine(this)

    override fun onCreateInputView(): View {
        return BoardView(this, engine).also { boardView = it }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Fires on every field focus, repeatedly — the engine must reset
        // transient state (layer, gesture state) itself, every time.
        engine.onStartInput(info, restarting)
        boardView?.invalidate()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        engine.onSelectionUpdate(newSelStart, newSelEnd)
    }

    /* ---------------- EngineHost ---------------- */

    override fun execute(cmd: EngineCommand) {
        val ic = currentInputConnection ?: return // no focused field: no-op safely
        when (cmd) {
            is EngineCommand.CommitText -> ic.commitText(cmd.text, 1)
            is EngineCommand.Backspace -> {
                if (selEnd != selStart) {
                    ic.commitText("", 1) // clear the selection
                } else {
                    // UTF-16 code units: emoji/surrogate pairs need 2 — v1 is
                    // English-only, revisit before shipping wider glyph sets.
                    ic.deleteSurroundingText(1, 0)
                }
            }
            is EngineCommand.MoveCursor -> {
                val pos = (if (cmd.delta < 0) minOf(selStart, selEnd) else maxOf(selStart, selEnd)) + cmd.delta
                val p = pos.coerceAtLeast(0)
                ic.setSelection(p, p)
            }
            is EngineCommand.SetSelection -> ic.setSelection(cmd.a, cmd.b)
        }
    }

    override fun haptic(kind: HapticKind) {
        boardView?.performEngineHaptic(kind)
    }

    override fun requestRender() {
        boardView?.invalidate()
    }
}
