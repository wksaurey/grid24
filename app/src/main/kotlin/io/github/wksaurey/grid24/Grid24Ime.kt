package io.github.wksaurey.grid24

import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * The InputMethodService — the only Android entry point. Bridges the active
 * KeyboardEngine to the current InputConnection and keeps field selection state
 * synced via onUpdateSelection (the source of truth; never assume).
 */
class Grid24Ime : InputMethodService(), EngineHost {

    private lateinit var engine: KeyboardEngine
    private var boardView: BoardView? = null
    private val handler = Handler(Looper.getMainLooper())

    // Field selection as last reported by the host app (onUpdateSelection),
    // seeded from EditorInfo at session start.
    private var selStart = 0
    private var selEnd = 0

    // MUST be a field: SharedPreferences holds listeners weakly — a lambda
    // registered inline gets garbage-collected and silently stops firing.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshTunables()
    }

    override fun onCreate() {
        super.onCreate()
        engine = createEngine()
        // Live refresh: settings-lab changes apply while the keyboard is showing
        // (slider drags repaint the open board in real time).
        TunablesStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        TunablesStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    private var lastHeightSig = ""

    private fun refreshTunables() {
        val t = TunablesStore.load(this)
        engine.applyTunables(t)
        // Relayout ONLY when board height actually changes — a same-size IME
        // remeasure still relayouts the host activity, which scrolls the
        // settings page to its focused field on every slider tick.
        val heightSig = "${t.deadZone}/${t.rowHeight}/${t.fnRowHeight}"
        if (heightSig != lastHeightSig) {
            lastHeightSig = heightSig
            boardView?.requestLayout()
        }
        boardView?.invalidate()
        // Nav-strip icons + legacy strip color follow the theme's brightness.
        window?.window?.let { w ->
            val light = Color.luminance(engine.backgroundColor) > 0.5f
            w.insetsController?.setSystemBarsAppearance(
                if (light) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
            if (Build.VERSION.SDK_INT < 35) {
                @Suppress("DEPRECATION")
                w.navigationBarColor = engine.backgroundColor
            }
        }
    }

    /** Engine selection is a build-time constant for v1 (CLAUDE.md). */
    private fun createEngine(): KeyboardEngine = Grid24Engine(this)

    override fun onCreateInputView(): View {
        // API 35+: navigationBarColor is a documented no-op for gesture nav; the
        // system IME strip (chevron/globe/pill) is TRANSPARENT and shows whatever
        // we draw behind it (AOSP NavigationBarController sets background=null).
        // So: extend the window edge-to-edge; BoardView paints the inset band.
        // API 31-34: legacy path — the window fits insets and navigationBarColor
        // still tints the strip. (Unexpected-Keyboard PR #848 gates identically:
        // edge-to-edge below 35 has visual artifacts.)
        window?.window?.let { w ->
            if (Build.VERSION.SDK_INT >= 35) {
                w.setDecorFitsSystemWindows(false)
                w.attributes = w.attributes.apply { fitInsetsTypes = 0 }
            } else {
                @Suppress("DEPRECATION")
                w.navigationBarColor = engine.backgroundColor
            }
            // Not deprecated; kills the scrim over 3-button nav.
            w.isNavigationBarContrastEnforced = false
            // Dark board -> light strip icons: clear the LIGHT_NAVIGATION flag.
            w.insetsController?.setSystemBarsAppearance(
                0, WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
        return BoardView(this, engine).also {
            boardView = it
            // Inset dispatch to a fresh IME view is unreliable (HeliBoard/UK both
            // force it) — request explicitly so bottomInset is right on first show.
            it.requestApplyInsets()
        }
    }

    /** Never the fullscreen extract UI — the board stays a board even if the
     *  device rotates (portrait-only is v1 scope, not a guarantee). */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Settings lab: belt-and-suspenders with the live pref listener —
        // guarantees fresh tunables even if a change landed while unbound.
        refreshTunables()
        // Seed selection from the field — stale values from the previous field
        // would misdirect the first cursor command (initialSelStart is -1 when
        // the field doesn't report; treat as 0).
        selStart = (info?.initialSelStart ?: 0).coerceAtLeast(0)
        selEnd = (info?.initialSelEnd ?: 0).coerceAtLeast(0)
        // QA breadcrumb: which InputConnection dialect is this host speaking?
        Log.d("Grid24", "session: inputType=0x${Integer.toHexString(info?.inputType ?: -1)} raw=${rawKeyHost()}")
        // Fires on every field focus, repeatedly — the engine must reset
        // transient state (layer, gesture state) itself, every time.
        engine.onStartInput(info, restarting)
        engine.onSelectionUpdate(selStart, selEnd)
        boardView?.invalidate()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // A running hold/repeat timer must never fire into a closed field.
        engine.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Android may report reversed selections (start > end); normalize so the
        // engine can assume ordered values everywhere.
        selStart = minOf(newSelStart, newSelEnd)
        selEnd = maxOf(newSelStart, newSelEnd)
        engine.onSelectionUpdate(selStart, selEnd)
    }

    /* ---------------- EngineHost ---------------- */

    override fun execute(cmd: EngineCommand) {
        val ic = currentInputConnection ?: return // no focused field: no-op safely
        executeOn(ic, cmd)
    }

    private fun executeOn(ic: InputConnection, cmd: EngineCommand) {
        when (cmd) {
            is EngineCommand.CommitText -> {
                // Typing over a selection REPLACES it (Android convention; approved
                // deviation — prototype inserted non-destructively). Safety net: the
                // doomed selection is stashed to the system clipboard first.
                stashSelectionToClipboard(ic)
                ic.commitText(cmd.text, 1)
            }
            is EngineCommand.Backspace -> when {
                rawKeyHost() -> sendKey(ic, KeyEvent.KEYCODE_DEL) // terminals want the real key
                selEnd != selStart -> {
                    stashSelectionToClipboard(ic)
                    ic.commitText("", 1) // clear the selection
                }
                // UTF-16 code units: emoji/surrogate pairs need 2 — v1 is
                // English-only, revisit before shipping wider glyph sets.
                else -> ic.deleteSurroundingText(1, 0)
            }
            is EngineCommand.Enter -> if (rawKeyHost()) {
                sendKey(ic, KeyEvent.KEYCODE_ENTER)
            } else {
                val ei = currentInputEditorInfo
                val action = ei?.let { it.imeOptions and EditorInfo.IME_MASK_ACTION }
                    ?: EditorInfo.IME_ACTION_NONE
                val enterActionSuppressed =
                    ei != null && (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                if (action != EditorInfo.IME_ACTION_NONE &&
                    action != EditorInfo.IME_ACTION_UNSPECIFIED &&
                    !enterActionSuppressed
                ) {
                    ic.performEditorAction(action)   // search/go/send/done/next
                } else {
                    ic.commitText("\n", 1)           // multiline / plain fields
                }
            }
            is EngineCommand.MoveCursor -> if (rawKeyHost()) {
                // Terminals understand arrow keys, not setSelection.
                val key = if (cmd.delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                repeat(kotlin.math.abs(cmd.delta)) { sendKey(ic, key) }
            } else {
                val pos = (if (cmd.delta < 0) minOf(selStart, selEnd) else maxOf(selStart, selEnd)) + cmd.delta
                val p = pos.coerceAtLeast(0)
                ic.setSelection(p, p)
            }
            is EngineCommand.SetSelection ->
                if (!rawKeyHost()) ic.setSelection(cmd.a, cmd.b) // meaningless in terminals
            is EngineCommand.Batch -> {
                ic.beginBatchEdit()
                try {
                    cmd.commands.forEach { executeOn(ic, it) }
                } finally {
                    ic.endBatchEdit()
                }
            }
        }
    }

    override fun haptic(kind: HapticKind) {
        boardView?.performEngineHaptic(kind)
    }

    override fun requestRender() {
        boardView?.invalidate()
    }

    override fun schedule(delayMs: Long, action: () -> Unit): Scheduled {
        val r = Runnable { action() }
        handler.postDelayed(r, delayMs)
        return object : Scheduled {
            override fun cancel() = handler.removeCallbacks(r)
        }
    }

    override fun textBeforeCursor(n: Int): CharSequence? =
        currentInputConnection?.getTextBeforeCursor(n, 0)

    override fun autoCapsNow(): Boolean {
        if (isPasswordField()) return false
        val ic = currentInputConnection ?: return false
        // Force the sentence-caps mask (rather than the field's own inputType)
        // so the user's toggle governs everywhere, not only in fields that
        // opted into TYPE_TEXT_FLAG_CAP_SENTENCES themselves.
        val mask = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        return (ic.getCursorCapsMode(mask) and TextUtils.CAP_MODE_SENTENCES) != 0
    }

    override fun textLength(): Int? =
        currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)
            ?.let { it.startOffset + it.text.length } // extract may be a window, not the whole text

    /**
     * Any command about to destroy a live selection stashes it to the system
     * clipboard first — recovery net for accidental type-overs/deletes, and the
     * seed of the planned clipboard features. Never in password fields.
     */
    private fun stashSelectionToClipboard(ic: InputConnection) {
        if (selStart == selEnd || isPasswordField()) return
        val doomed = ic.getSelectedText(0)
        if (doomed.isNullOrEmpty()) return
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Grid24", doomed))
    }

    /** TYPE_NULL host (Termux etc.): the old "send me raw key events" contract. */
    private fun rawKeyHost(): Boolean =
        currentInputEditorInfo?.inputType == InputType.TYPE_NULL

    private fun sendKey(ic: InputConnection, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun isPasswordField(): Boolean {
        val type = currentInputEditorInfo?.inputType ?: return false
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            else -> false
        }
    }
}
