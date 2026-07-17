package io.github.wksaurey.grid24

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.inputmethod.EditorInfo
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The Grid24 keyboard engine — port of reference/grid24-proto.html (the
 * executable spec; when in doubt, open it and do what it does).
 *
 * M1+M2+M3 state: board renders (qwerty), geometry-correct hit-testing, taps
 * type; holds (merged secondaries, positional digits, delete repeat, space-hold
 * enter); quick-swipe cursor moves (selection-collapse aware); swipe-up
 * shift/caps; double-space period with auto-shift.
 * Still to come: selection-drag engine (M4), symbol/number layers (M5).
 *
 * Deliberate deviation from the prototype (user request 2026-07-17): key glyphs
 * render lowercase normally and uppercase while shift/caps is active — the
 * prototype always drew uppercase.
 */
class Grid24Engine(private val host: EngineHost) : KeyboardEngine {

    /* Tuned constants — the prototype's values (css-px ≈ dp). These numbers
     * encode real debugging: keep them. */
    object Config {
        const val TAP_T = 18f            // dp — travel at or below this = tap
        const val GESTURE_T = 100f       // dp — below this, sloppy taps are forgiven as taps
        const val HOLD_MS = 200L         // hold tier: merged/digit alt, delete repeat start
        const val FLICK_MS = 280L        // fast-vs-slow discriminator for cursor swipes
        const val SPACE_HOLD_MS = 550L   // extra-long hold on SPACE = enter
        const val REPEAT_MS = 45L        // delete hold-repeat rate
        const val DBL_SPACE_MS = 600L    // double-space period window
        const val SHIFT_DBL_MS = 450L    // shift-again-within = caps lock
        const val LETTER_ROW_H = 48f     // dp — prototype clamp(40px, 5.6vh, 52px) midpoint
        const val FN_ROW_H = 60f         // dp — function row ~25% taller
        const val DEAD_ZONE = 18f        // dp — gap below board (nav inset stacks on top)
        const val GAP = 4f               // dp — visual gap between keys
        const val KEY_RADIUS = 10f       // dp
        // DEL_* selection-drag physics land at M4.
    }

    /* Prototype CSS :root colors, verbatim. */
    private object Palette {
        val BG = Color.parseColor("#191c22")
        val SURFACE = Color.parseColor("#22262e")
        val SURFACE_2 = Color.parseColor("#2a2f39")
        val LINE = Color.parseColor("#363c48")
        val INK = Color.parseColor("#ece7d9")
        val INK_DIM = Color.parseColor("#9a958a")
        val ACCENT = Color.parseColor("#e8873a")
        val PRESS_INK = Color.parseColor("#1a1408")
    }

    private class Key(
        val pri: String,
        val sec: String?,          // merged-key hold secondary
        val num: String?,          // positional digit (alpha layer, right half)
        val fn: String? = null,    // "del" | "space" — function row keys
    ) {
        val rect = RectF()
        var pressed = false
        var holdFlipped = false    // hold fired: label shows the alt (prototype .longing)
    }

    /** 24 grid keys, row-major — rebuilt on layout change. */
    private val keys = ArrayList<Key>()

    /** Function row: prototype fnOrder default = DELETE left slot, SPACE right. */
    private val fnDel = Key("⌫ DELETE", null, null, fn = "del")
    private val fnSpace = Key("SPACE", null, null, fn = "space")
    private val fnOrder = arrayOf(fnDel, fnSpace)

    private class PointerState(
        val x0: Float,
        val y0: Float,
        val t0: Long,
        val key: Key?,
        val sel0: IntRange?,       // selection as it stood at touch-down (prototype st.sel0)
    ) {
        var maxTravel = 0f
        var long = false           // hold fired (alt armed)
        var held = false           // space-hold newline fired (single fire)
        var repeating = false      // delete hold-repeat engaged
        var holdTask: Scheduled? = null
        var repTask: Scheduled? = null

        fun cancelTasks() {
            holdTask?.cancel(); holdTask = null
            repTask?.cancel(); repTask = null
        }
    }

    /** Per-pointer state, keyed by stable pointer id — the prototype's `touches` map. */
    private val touches = HashMap<Int, PointerState>()

    /* Keyboard-side state (prototype globals). */
    private var shiftState = 0     // 0 none, 1 next, 2 lock
    private var lastShiftTs = 0L
    private var lastSpaceTs = 0L
    private var selStart = 0
    private var selEnd = 0

    // Real display density + board size, cached from measure/render (which
    // always precede touch). The onPointer* callbacks deliberately carry neither.
    private var density = 3f
    private var boardW = 0
    private var boardH = 0

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Palette.LINE
    }
    private val priPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val secPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }
    private val fnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.14f
    }

    override val backgroundColor: Int get() = Palette.BG

    init {
        buildKeys()
    }

    private fun buildKeys() {
        keys.clear()
        Layouts.ALPHA.getValue(Layouts.DEFAULT).forEachIndexed { ri, row ->
            row.forEachIndexed { ci, def ->
                val num = if (ci >= 3) Layouts.NUMGRID[ri][ci - 3] else null
                keys.add(Key(def.pri, def.sec, num))
            }
        }
    }

    /** Hold resolution priority: merged secondary beats positional digit (sec || num). */
    private fun altOf(k: Key?): String? = k?.sec ?: k?.num

    /* ---------------- geometry ---------------- */

    override fun measureHeight(widthPx: Int, density: Float): Int {
        this.density = density
        return ((4 * Config.LETTER_ROW_H + Config.FN_ROW_H + Config.DEAD_ZONE) * density).toInt()
    }

    /** Cell layout mirrors the prototype's keyAt(): 6 equal columns; letter rows
     *  equally subdivide the space above the function row's measured top. */
    private fun layoutKeys(w: Int, h: Int) {
        boardW = w
        boardH = h
        val dead = Config.DEAD_ZONE * density
        val fnH = Config.FN_ROW_H * density
        val fnTop = h - dead - fnH
        val cellW = w / 6f
        val cellH = fnTop / 4f
        val inset = Config.GAP * density / 2f
        keys.forEachIndexed { i, k ->
            val r = i / 6
            val c = i % 6
            k.rect.set(
                c * cellW + inset, r * cellH + inset,
                (c + 1) * cellW - inset, (r + 1) * cellH - inset,
            )
        }
        fnOrder.forEachIndexed { i, k ->
            k.rect.set(
                i * w / 2f + inset, fnTop + inset,
                (i + 1) * w / 2f - inset, fnTop + fnH - inset,
            )
        }
    }

    /** Port of the prototype's keyAt(): cell math, not rect hit-tests, so gaps
     *  between keys still resolve to the nearest key. */
    private fun keyAt(x: Float, y: Float): Key? {
        if (boardW == 0 || boardH == 0) return null
        if (x < 0 || x >= boardW || y < 0) return null
        val dead = Config.DEAD_ZONE * density
        val fnTop = boardH - dead - Config.FN_ROW_H * density
        if (y >= boardH - dead) return null                       // dead zone
        val col = ((x / (boardW / 6f)).toInt()).coerceIn(0, 5)
        if (y >= fnTop) return fnOrder[if (col < 3) 0 else 1]
        val row = ((y / (fnTop / 4f)).toInt()).coerceIn(0, 3)
        return keys[row * 6 + col]
    }

    /* ---------------- rendering ---------------- */

    override fun render(canvas: Canvas, widthPx: Int, heightPx: Int, density: Float) {
        this.density = density
        if (widthPx != boardW || heightPx != boardH) layoutKeys(widthPx, heightPx)
        fillPaint.color = Palette.BG
        canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), fillPaint)
        keys.forEach { drawKey(canvas, it) }
        fnOrder.forEach { drawKey(canvas, it) }
    }

    private fun drawKey(canvas: Canvas, k: Key) {
        val radius = Config.KEY_RADIUS * density
        val active = k.pressed || k.holdFlipped
        fillPaint.color = when {
            active -> Palette.ACCENT
            k.fn != null || k.sec != null -> Palette.SURFACE_2
            else -> Palette.SURFACE
        }
        canvas.drawRoundRect(k.rect, radius, radius, fillPaint)
        canvas.drawRoundRect(k.rect, radius, radius, strokePaint)

        if (k.fn != null) {
            fnPaint.textSize = 13f * density
            fnPaint.color = if (active) Palette.PRESS_INK else Palette.INK_DIM
            drawCentered(canvas, k.pri, fnPaint, k.rect)
            return
        }

        // Hold-flip shows the armed alt; otherwise glyph case tracks shift/caps
        // (deviation from the always-uppercase prototype — see class doc).
        val glyph = when {
            k.holdFlipped -> (altOf(k) ?: k.pri).uppercase()
            shiftState > 0 -> k.pri.uppercase()
            else -> k.pri.lowercase()
        }
        priPaint.textSize = 19f * density
        priPaint.color = if (active) Palette.PRESS_INK else Palette.INK
        drawCentered(canvas, glyph, priPaint, k.rect)

        // Secondary glyph, bottom-right: merged secondary (ink-dim) or positional
        // digit (accent, 70% opacity) — prototype .sec / .sec.num styling.
        val secText = k.sec ?: k.num
        if (secText != null && !k.holdFlipped) {
            secPaint.textSize = 10f * density
            secPaint.color = when {
                active -> Palette.PRESS_INK
                k.sec != null -> Palette.INK_DIM
                else -> Palette.ACCENT
            }
            secPaint.alpha = if (active || k.sec != null) 255 else 178   // num: 0.7 opacity
            canvas.drawText(
                secText,
                k.rect.right - 5f * density,
                k.rect.bottom - 4f * density,
                secPaint,
            )
        }
    }

    private fun drawCentered(canvas: Canvas, text: String, paint: Paint, rect: RectF) {
        val y = rect.centerY() - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, rect.centerX(), y, paint)
    }

    /* ---------------- editing ops (prototype commitChar/doSpace/doShift) ---------------- */

    private fun commitChar(ch: String) {
        var out = ch
        if (ch.length == 1 && ch[0] in 'a'..'z' && shiftState > 0) {
            out = ch.uppercase()
            if (shiftState == 1) {
                shiftState = 0
                host.requestRender()
            }
        }
        host.execute(EngineCommand.CommitText(out))
        host.haptic(HapticKind.COMMIT)
    }

    private fun doSpace(t: Long) {
        val prevCharIsSpace = host.textBeforeCursor(1)?.toString() == " "
        if (selStart == selEnd && prevCharIsSpace && t - lastSpaceTs < Config.DBL_SPACE_MS) {
            // Double-space = period: replace the space with ". ", auto-shift next.
            // (Prototype also guards "text before isn't all whitespace" — needs a
            // fuller text read than the boundary offers; accepted fidelity gap.)
            host.execute(
                EngineCommand.Batch(
                    listOf(EngineCommand.Backspace, EngineCommand.CommitText(". ")),
                ),
            )
            shiftState = if (shiftState == 2) 2 else 1
            host.haptic(HapticKind.CONFIRM)
            host.requestRender()
        } else {
            commitChar(" ")
        }
        lastSpaceTs = t
    }

    private fun doShift(t: Long) {
        shiftState = if (t - lastShiftTs < Config.SHIFT_DBL_MS) 2
        else if (shiftState == 0) 1 else 0
        lastShiftTs = t
        host.haptic(HapticKind.COMMIT)
        host.requestRender()
    }

    /* ---------------- pointers (prototype pointerdown/move/end) ---------------- */

    override fun onPointerDown(id: Int, x: Float, y: Float, t: Long) {
        val k = keyAt(x, y)
        val st = PointerState(x, y, t, k, if (selStart != selEnd) selStart..selEnd else null)
        touches[id] = st
        if (k == null) return
        k.pressed = true
        host.requestRender()

        val alt = altOf(k)
        if (alt != null) {                                  // hold = merged/digit alt
            st.holdTask = host.schedule(Config.HOLD_MS) {
                st.long = true
                k.holdFlipped = true
                host.haptic(HapticKind.HOLD_FLIP)
                host.requestRender()
            }
        }
        if (k.fn == "del") {                                // hold delete = repeat
            st.holdTask = host.schedule(Config.HOLD_MS) {
                st.repeating = true
                repeatBackspace(st)
            }
        }
        if (k.fn == "space") {                              // extra-long hold space = enter
            st.holdTask = host.schedule(Config.SPACE_HOLD_MS) {
                st.held = true
                commitChar("\n")
            }
        }
    }

    private fun repeatBackspace(st: PointerState) {
        host.execute(EngineCommand.Backspace)
        host.haptic(HapticKind.DRAG_TICK)
        st.repTask = host.schedule(Config.REPEAT_MS) { repeatBackspace(st) }
    }

    override fun onPointerMove(id: Int, x: Float, y: Float, t: Long) {
        val st = touches[id] ?: return
        val travel = hypot((x - st.x0).toDouble(), (y - st.y0).toDouble()).toFloat()
        if (travel > st.maxTravel) st.maxTravel = travel
        // Past TAP_T this is no longer a clean tap: cancel the pending hold tier
        // and clear the press highlight (prototype clears st.timer + .press here).
        // An already-running delete repeat survives movement, as in the prototype.
        if (st.maxTravel > Config.TAP_T * density) {
            if (!st.repeating) {
                st.holdTask?.cancel()
                st.holdTask = null
            }
            if (st.key?.pressed == true) {
                st.key.pressed = false
                host.requestRender()
            }
        }
    }

    override fun onPointerUp(id: Int, x: Float, y: Float, t: Long) {
        val st = touches.remove(id) ?: return
        st.cancelTasks()
        st.key?.let {
            it.pressed = false
            it.holdFlipped = false
        }
        host.requestRender()

        if (st.repeating) return                            // hold-repeat consumed this touch
        if (st.held) return                                 // space-hold enter already fired

        val dx = x - st.x0
        val dy = y - st.y0
        val tapPx = Config.TAP_T * density
        val gesturePx = Config.GESTURE_T * density

        // Fast horizontal swipes move the cursor. With a selection (as it stood
        // at touch-down), collapse to the swiped end; otherwise nudge one char.
        if (st.maxTravel > tapPx && (t - st.t0) < Config.FLICK_MS && abs(dx) > abs(dy)) {
            if (st.sel0 != null) {
                val pos = if (dx < 0) st.sel0.first else st.sel0.last
                host.execute(EngineCommand.SetSelection(pos, pos))
            } else {
                host.execute(EngineCommand.MoveCursor(if (dx < 0) -1 else 1))
            }
            host.haptic(HapticKind.DRAG_TICK)
            return
        }

        if (st.maxTravel <= tapPx) {                        // tap
            val k = st.key ?: return
            when {
                k.fn == "space" -> doSpace(t)
                k.fn == "del" -> {
                    host.execute(EngineCommand.Backspace)
                    host.haptic(HapticKind.COMMIT)
                }
                st.long -> commitChar(altOf(k) ?: k.pri)    // hold fired: commit the alt
                else -> commitChar(k.pri)
            }
            return
        }
        if (st.maxTravel < gesturePx) {                     // slow flick band: forgiving sloppy tap
            val k = st.key ?: return
            when (k.fn) {
                "space" -> doSpace(t)
                "del" -> {
                    host.execute(EngineCommand.Backspace)
                    host.haptic(HapticKind.COMMIT)
                }
                else -> commitChar(k.pri)
            }
            return
        }

        // Slow long gestures past GESTURE_T.
        if (abs(dy) >= abs(dx)) {
            if (dy < 0) {
                doShift(t)                                  // swipe up = shift / caps
            }
            // Swipe down = layers (M5). No-op until then.
        }
        // Slow horizontal past GESTURE_T = selection drags (M4). No-op until then.
    }

    override fun onPointerCancel(id: Int) {
        // Abandon, commit nothing (system stole the gesture).
        val st = touches.remove(id) ?: return
        st.cancelTasks()
        st.key?.let {
            it.pressed = false
            it.holdFlipped = false
        }
        host.requestRender()
    }

    /* ---------------- session ---------------- */

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        // Reset transient state on every session, including restarts.
        clearPointers()
        shiftState = 0
        lastSpaceTs = 0
        lastShiftTs = 0
        host.requestRender()
    }

    override fun onSelectionUpdate(selStart: Int, selEnd: Int) {
        this.selStart = selStart
        this.selEnd = selEnd
        // M4's drag engine adds the own-echo guard here.
    }

    override fun onFinishInput() {
        clearPointers()
    }

    private fun clearPointers() {
        touches.values.forEach { it.cancelTasks() }
        touches.clear()
        keys.forEach { it.pressed = false; it.holdFlipped = false }
        fnOrder.forEach { it.pressed = false; it.holdFlipped = false }
    }
}
