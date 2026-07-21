package io.github.wksaurey.mosaic

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
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
class MosaicIme : InputMethodService(), EngineHost {

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

    /* In-keyboard clip slots (v1 of the registers model, Kolter 2026-07-19):
     * slot 0 = newest SYSTEM clipboard (live, read-only), slot 1 = the DELETE
     * buffer (last selection destroyed by typing/DELETE; live, read-only),
     * slots 2-7 = six manual registers that ONLY explicit stores ever touch.
     * No eviction policy — ownership makes one unnecessary. In-memory, v0. */
    private var sysClip: String? = null
    private var delBuf: String? = null
    private val registers = arrayOfNulls<String>(6)

    private val clipboardManager by lazy { getSystemService(ClipboardManager::class.java) }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = clipboardManager?.primaryClip ?: return@OnPrimaryClipChangedListener
        val sensitive = clip.description?.extras
            ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        if (!sensitive) {
            clip.getItemAt(0)?.coerceToText(this)?.toString()
                ?.takeIf { it.isNotBlank() }?.let { sysClip = it }
        }
    }

    /** Auto-feed from destroyed selections → the delete buffer. */
    private fun addClip(text: CharSequence?) {
        text?.toString()?.takeIf { it.isNotBlank() }?.let { delBuf = it }
    }

    /** Manual store — only the six registers (slots 2-7) are writable. */
    private fun storeClipAt(index: Int, text: String) {
        if (index in 2..7) registers[index - 2] = text
    }

    override fun clips(): List<String?> = listOf(sysClip, delBuf) + registers.toList()

    override fun onCreate() {
        super.onCreate()
        engine = createEngine()
        // Live refresh: settings-lab changes apply while the keyboard is showing
        // (slider drags repaint the open board in real time).
        TunablesStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        clipboardManager?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        TunablesStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }

    private var lastHeightSig = ""
    private var tunables = Tunables()   // host-side copy for haptic gating

    private fun refreshTunables() {
        val t = TunablesStore.load(this)
        tunables = t
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
    private fun createEngine(): KeyboardEngine = MosaicEngine(this)

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
        Log.d("Mosaic", "session: inputType=0x${Integer.toHexString(info?.inputType ?: -1)} raw=${rawKeyHost()}")
        // Fires on every field focus, repeatedly — the engine must reset
        // transient state (layer, gesture state) itself, every time.
        engine.onStartInput(info, restarting)
        engine.onSelectionUpdate(selStart, selEnd)
        boardView?.invalidate()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // Re-ask for insets on every show — a missed dispatch otherwise leaves
        // the board overlapping the system strip until something else relayouts
        // (observed 2026-07-19: stuck until a dead-zone nudge).
        boardView?.requestApplyInsets()
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
            is EngineCommand.Copy ->
                Log.d("Mosaic", "ctx copy handled=${ic.performContextMenuAction(android.R.id.copy)} sel=$selStart..$selEnd")
            is EngineCommand.Cut ->
                Log.d("Mosaic", "ctx cut handled=${ic.performContextMenuAction(android.R.id.cut)} sel=$selStart..$selEnd")
            is EngineCommand.Paste ->
                Log.d("Mosaic", "ctx paste handled=${ic.performContextMenuAction(android.R.id.paste)}")
            is EngineCommand.StoreClip -> {
                val sel = ic.getSelectedText(0)?.toString()
                Log.d("Mosaic", "storeClip idx=${cmd.index} cut=${cmd.cut} len=${sel?.length}")
                if (!sel.isNullOrEmpty() && !isPasswordField()) {
                    storeClipAt(cmd.index, sel)
                    if (cmd.cut) ic.commitText("", 1)   // cut: remove from the text
                }
            }
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

    private val vibrator by lazy {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    }

    /** User-controlled haptics (settings lab): per-event-class toggles +
     *  amplitude from the intensity %. VibrationEffect (not
     *  performHapticFeedback) because intensity needs amplitude control —
     *  the reason the manifest carries VIBRATE. Durations approximate the
     *  prototype's buzz() patterns. */
    override fun haptic(kind: HapticKind) {
        val t = tunables
        if (t.hapticPct <= 0) return
        val enabled = when (kind) {
            HapticKind.COMMIT -> t.hapticTaps
            HapticKind.HOLD_FLIP -> t.hapticHolds
            HapticKind.DRAG_TICK -> t.hapticTicks
            HapticKind.CONFIRM, HapticKind.CANCEL -> t.hapticEvents
        }
        if (!enabled) return
        val amp = (t.hapticPct * 255 / 100).coerceIn(1, 255)
        val base = when (kind) {
            HapticKind.COMMIT -> 8L        // prototype buzz(8)
            HapticKind.HOLD_FLIP -> 16L    // buzz([10,30,10]) condensed
            HapticKind.DRAG_TICK -> 5L     // buzz(4)
            HapticKind.CONFIRM -> 20L      // buzz([8,40,8]) condensed
            HapticKind.CANCEL -> 14L       // buzz([8,30,8]) condensed
        }
        val dur = (base * t.hapticDurPct / 100L).coerceAtLeast(1L)
        vibrator?.vibrate(VibrationEffect.createOneShot(dur, amp))
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
        val ic = currentInputConnection ?: run {
            Log.d("Mosaic", "autoCapsNow: no InputConnection")
            return false
        }
        // Force the caps mask (rather than the field's own inputType) so the
        // user's toggle governs everywhere, not only in fields that opted in.
        val mask = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val modes = ic.getCursorCapsMode(mask)
        Log.d("Mosaic", "autoCapsNow: modes=0x${Integer.toHexString(modes)}")
        if (modes and TextUtils.CAP_MODE_SENTENCES != 0) return true
        // AOSP reports WORDS (not SENTENCES) at the very start of empty text —
        // logcat-verified 2026-07-19. WORDS counts only when nothing precedes
        // the cursor (a true field start); mid-text WORDS = every-word caps, no.
        return modes and TextUtils.CAP_MODE_WORDS != 0 &&
            ic.getTextBeforeCursor(1, 0).isNullOrEmpty()
    }

    override fun textLength(): Int? =
        currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)
            ?.let { it.startOffset + it.text.length } // extract may be a window, not the whole text

    /**
     * Any command about to destroy a live selection stashes it to the
     * IN-KEYBOARD clip history — recovery net for accidental type-overs/deletes.
     * (2026-07-19: no longer written to the system clipboard, which clobbered
     * the user's real clip on every type-over.) Never in password fields.
     */
    private fun stashSelectionToClipboard(ic: InputConnection) {
        if (selStart == selEnd || isPasswordField()) return
        addClip(ic.getSelectedText(0))
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
