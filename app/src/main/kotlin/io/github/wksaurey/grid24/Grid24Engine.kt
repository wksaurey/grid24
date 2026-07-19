package io.github.wksaurey.grid24

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The Grid24 keyboard engine — port of reference/grid24-proto.html (the
 * executable spec; when in doubt, open it and do what it does).
 *
 * v1-complete (M0–M6): board rendering + geometry-correct hit-testing, holds
 * (merged secondaries, positional digits/punctuation, delete repeat, space-hold
 * enter), quick-swipe cursor moves, swipe-up shift/caps, double-space period,
 * the hybrid-physics drag engine (cursor on letter keys, selection on the fn
 * row), symbol/calculator layers, numeric-field auto-calculator.
 *
 * Deliberate deviations from the prototype are listed in CLAUDE.md
 * ("Approved deviations") — notably shift-tracking glyph case, the drag zone
 * split, replace-on-type, and the 2026-07-17 constant retunes.
 */
class Grid24Engine(private val host: EngineHost) : KeyboardEngine {

    /* Tuned constants — the prototype's values (css-px ≈ dp). These numbers
     * encode real debugging: keep them. */
    object Config {
        const val TAP_T = 18f            // dp — travel at or below this = tap
        const val GESTURE_T = 100f       // dp — below this, sloppy taps are forgiven as taps
        const val DRAG_T = 60f           // dp — horizontal drag engagement (2026-07-17: split
                                         // from GESTURE_T so slides register sooner; vertical
                                         // gestures and tap forgiveness stay at GESTURE_T)
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

        /* Selection-drag hybrid physics (M4) — prototype values, dp unless noted. */
        const val DEL_STEP = 20f         // dp/char — neutral (positional) zone resolution
                                         // (prototype 22; retuned on-device 2026-07-17)
        const val DEL_BREAK = 220f       // dp from touch: velocity-zone breakpoint (prototype
                                         // 280; retuned 2026-07-17 with DRAG_T=60 to keep the
                                         // ~8-char positional runway of the original design)
        const val DEL_REV_BREAK = 120f   // dp right of touch: reverse-velocity breakpoint
        const val DEL_EDGE = 60f         // dp: either screen edge forces its velocity zone
        const val DEL_RATE_MIN = 10f     // chars/sec at zone boundary (prototype 2;
                                         // retuned 2026-07-17 — felt too slow on entry)
        const val DEL_RATE_MAX = 60f     // chars/sec cap
        const val DEL_RATE_SPAN = 60f    // dp: quadratic ramp normalizer
        const val DEL_RATE_SCALE = 18f   // quadratic ramp gain
        const val DEL_TICK = 50L         // ms: velocity integrator period
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

    private enum class Layer { ALPHA, SYM, NUM }

    /** Grid keys, row-major (24 on alpha/sym, 16 on num) — rebuilt on layer change. */
    private val keys = ArrayList<Key>()
    private var layer = Layer.ALPHA

    /** 6 columns on alpha/symbols; the calculator renders at 4 (wider keys). */
    private fun cols(): Int = if (layer == Layer.NUM) 4 else 6

    /** Function row: prototype fnOrder default = DELETE left slot, SPACE right. */
    private val fnDel = Key("⌫ DELETE", null, null, fn = "del")
    private val fnSpace = Key("SPACE", null, null, fn = "space")
    private val fnOrder = arrayOf(fnDel, fnSpace)

    private enum class DragMode { BACK, FWD, MOVE, CURSOR }

    private class PointerState(
        val x0: Float,
        val y0: Float,
        val t0: Long,
        val key: Key?,
        val sel0: IntRange?,       // selection as it stood at touch-down (prototype st.sel0)
        val caret0: Int,           // caret at touch-down (drags don't move it; flicks use it)
    ) {
        var maxTravel = 0f
        var long = false           // hold fired (alt armed)
        var held = false           // space-hold newline fired (single fire)
        var repeating = false      // delete hold-repeat engaged
        var holdTask: Scheduled? = null
        var repTask: Scheduled? = null

        /* Drag machinery (prototype st.del/mode/f/anchor/...). */
        var del = false            // slow-horizontal drag engaged
        var mode = DragMode.BACK
        var f = 0f                 // fractional selection count / window offset
        var anchor = 0
        var a0 = 0                 // move mode: selection at engagement
        var b0 = 0
        var textLen = 0            // clamp bound, queried once at engagement
        var lastVal: Int? = null
        var prevDx: Float? = null
        var lastDx = 0f
        var lastX = 0f
        var prevNeutral = true     // zone-boundary freeze guard
        var tickTask: Scheduled? = null

        fun cancelTasks() {
            holdTask?.cancel(); holdTask = null
            repTask?.cancel(); repTask = null
            tickTask?.cancel(); tickTask = null
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
        val rows = when (layer) {
            Layer.ALPHA -> Layouts.ALPHA.getValue(Layouts.DEFAULT)
            Layer.SYM -> Layouts.SYM
            Layer.NUM -> Layouts.NUM
        }
        rows.forEachIndexed { ri, row ->
            row.forEachIndexed { ci, def ->
                // Positional holds (digits + punctuation) ride the alpha layer only.
                val num = if (layer == Layer.ALPHA) Layouts.ALPHA_HOLDS[ri][ci] else null
                keys.add(Key(def.pri, def.sec, num))
            }
        }
        if (boardW > 0) layoutKeys(boardW, boardH)   // column count may have changed
    }

    private fun setLayer(l: Layer) {
        if (layer == l) return
        layer = l
        buildKeys()
        host.haptic(HapticKind.CONFIRM)
        host.requestRender()
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
        val nc = cols()
        val cellW = w / nc.toFloat()
        val cellH = fnTop / 4f
        val inset = Config.GAP * density / 2f
        keys.forEachIndexed { i, k ->
            val r = i / nc
            val c = i % nc
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
        // The DEAD_ZONE strip is spacing, not a tap trap: low fn-row taps are
        // forgiven down to boardH (prototype keyAt behavior). Below boardH is
        // the nav-inset band — the system's globe/chevron strip, never ours.
        if (y >= boardH) return null
        val dead = Config.DEAD_ZONE * density
        val fnTop = boardH - dead - Config.FN_ROW_H * density
        val nc = cols()
        val col = ((x / (boardW / nc.toFloat())).toInt()).coerceIn(0, nc - 1)
        if (y >= fnTop) return fnOrder[if (col < nc / 2) 0 else 1]
        val row = ((y / (fnTop / 4f)).toInt()).coerceIn(0, 3)
        return keys[row * nc + col]
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
        priPaint.textSize = (if (layer == Layer.NUM) 23f else 19f) * density  // calculator keys read bigger
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
        val before = host.textBeforeCursor(48)
        val prevCharIsSpace = before?.lastOrNull() == ' '
        // Prototype guard: some non-whitespace must precede (48-char window
        // approximates "anywhere before the cursor") — no ". " at field start.
        val hasContentBefore = before?.isNotBlank() == true
        if (selStart == selEnd && prevCharIsSpace && hasContentBefore &&
            t - lastSpaceTs < Config.DBL_SPACE_MS
        ) {
            // Double-space = period: replace the space with ". ", auto-shift next.
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
        val st = PointerState(
            x, y, t, k,
            if (selStart != selEnd) selStart..selEnd else null,
            selEnd,
        )
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
                host.execute(EngineCommand.Enter)           // host resolves action-vs-newline
                host.haptic(HapticKind.COMMIT)
            }
        }
    }

    private fun repeatBackspace(st: PointerState) {
        host.execute(EngineCommand.Backspace)
        host.haptic(HapticKind.DRAG_TICK)
        st.repTask = host.schedule(Config.REPEAT_MS) { repeatBackspace(st) }
    }

    /* ---- drag zone helpers (prototype leftOver/rightOver/zoneRate) ----
     * leftOver > 0 near the left break/edge, rightOver > 0 near the right. */

    private fun leftOver(dx: Float, x: Float): Float =
        maxOf(-dx - Config.DEL_BREAK * density, (Config.DEL_EDGE * density - x) * 3f)

    private fun rightOver(dx: Float, x: Float): Float =
        maxOf(dx - Config.DEL_REV_BREAK * density, (x - (boardW - Config.DEL_EDGE * density)) * 3f)

    private fun zoneRate(over: Float): Float {
        val n = over / (Config.DEL_RATE_SPAN * density)
        return minOf(Config.DEL_RATE_MAX, Config.DEL_RATE_MIN + n * n * Config.DEL_RATE_SCALE)
    }

    private fun dragClamp(st: PointerState) {
        st.f = when (st.mode) {
            DragMode.BACK -> st.f.coerceIn(0f, st.anchor.toFloat())
            DragMode.FWD -> st.f.coerceIn(0f, (st.textLen - st.anchor).toFloat())
            DragMode.MOVE -> st.f.coerceIn(-st.a0.toFloat(), (st.textLen - st.b0).toFloat())
            DragMode.CURSOR -> st.f.coerceIn(-st.anchor.toFloat(), (st.textLen - st.anchor).toFloat())
        }
    }

    /** Emit the selection for the current f; haptic tick per whole-char change. */
    private fun applyDrag(st: PointerState) {
        val v: Int
        val a: Int
        val b: Int
        when (st.mode) {
            DragMode.BACK -> {
                v = kotlin.math.floor(st.f).toInt()
                a = st.anchor - v; b = st.anchor
            }
            DragMode.FWD -> {
                v = kotlin.math.floor(st.f).toInt()
                a = st.anchor; b = st.anchor + v
            }
            DragMode.MOVE -> {
                v = kotlin.math.round(st.f).toInt()
                a = st.a0 + v; b = st.b0 + v
            }
            DragMode.CURSOR -> {                       // caret slides, no selection
                v = kotlin.math.round(st.f).toInt()
                a = st.anchor + v; b = a
            }
        }
        if (v != st.lastVal) {
            st.lastVal = v
            if ((st.mode == DragMode.BACK || st.mode == DragMode.FWD) && v <= 0) {
                // Fully reversed to zero = cancel: collapse at the anchor.
                host.execute(EngineCommand.SetSelection(st.anchor, st.anchor))
            } else {
                host.execute(EngineCommand.SetSelection(a.coerceAtLeast(0), b.coerceAtLeast(0)))
            }
            host.haptic(HapticKind.DRAG_TICK)
        }
    }

    /** Velocity integrator (prototype's delTick interval), self-chaining. */
    private fun dragTick(st: PointerState) {
        val lo = leftOver(st.lastDx, st.lastX)
        val ro = rightOver(st.lastDx, st.lastX)
        val rate = if (st.mode == DragMode.BACK) {
            if (lo > 0) zoneRate(lo) else if (ro > 0) -zoneRate(ro) else 0f
        } else { // fwd & move mirror
            if (ro > 0) zoneRate(ro) else if (lo > 0) -zoneRate(lo) else 0f
        }
        if (rate != 0f) {
            st.f += rate * (Config.DEL_TICK / 1000f)
            dragClamp(st)
            applyDrag(st)
        }
        st.tickTask = host.schedule(Config.DEL_TICK) { dragTick(st) }
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

        val dx = x - st.x0
        val dy = y - st.y0
        val ddx = st.prevDx?.let { dx - it } ?: 0f
        st.prevDx = dx
        st.lastDx = dx
        st.lastX = x

        // Engage the drag: slow horizontal past DRAG_T (prototype used GESTURE_T;
        // split 2026-07-17 so drags register sooner).
        // Zone split (2026-07-17 deviation): letter-key starts move the CURSOR;
        // selection drags live on the function row. A running delete-repeat never
        // also engages a drag.
        if (!st.del && !st.repeating &&
            st.maxTravel > Config.DRAG_T * density && abs(dx) > abs(dy)
        ) {
            st.del = true
            // Clamp bound, queried once — text can't change during a drag. Fields
            // that won't report length get a loose bound; setSelection past the
            // real end is ignored by well-behaved editors and recovers on reverse.
            st.textLen = host.textLength() ?: (maxOf(selStart, selEnd) + 100_000)
            if (st.key?.fn == null) {                      // letter keys / dead zone: cursor
                st.mode = DragMode.CURSOR
                st.anchor = st.caret0
                st.f = 0f
            } else if (dx < 0) {                           // select backward (extends existing)
                st.mode = DragMode.BACK
                st.anchor = if (selStart != selEnd) selEnd else st.caret0
                st.f = if (selStart != selEnd) (selEnd - selStart).toFloat() else 1f
            } else if (selStart != selEnd) {               // slide the selection window
                st.mode = DragMode.MOVE
                st.a0 = selStart
                st.b0 = selEnd
                st.f = 0f
            } else {                                       // select forward from caret
                st.mode = DragMode.FWD
                st.anchor = st.caret0
                st.f = 1f
            }
            dragClamp(st)
            st.lastVal = null
            st.prevNeutral = leftOver(dx, x) <= 0 && rightOver(dx, x) <= 0
            applyDrag(st)
            dragTick(st)                                   // starts the integrator
            return
        }

        if (st.del) {
            val neutral = leftOver(dx, x) <= 0 && rightOver(dx, x) <= 0
            // Neutral band: 1:1 positional tracking; skip the delta on zone-boundary
            // crossings (the prevNeutral freeze — retreating from a velocity burst
            // must never mass-reverse the count).
            if (neutral && st.prevNeutral) {
                val d = ddx / (Config.DEL_STEP * density)
                if (st.mode == DragMode.BACK) st.f -= d    // leftward grows backward selection
                else st.f += d                             // rightward grows fwd / slides window
                dragClamp(st)
                applyDrag(st)
            }
            st.prevNeutral = neutral
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
        // at touch-down), collapse to the swiped end; otherwise nudge one char —
        // discarding anything a dying (fast) drag built, from the pre-drag caret.
        if (st.maxTravel > tapPx && (t - st.t0) < Config.FLICK_MS && abs(dx) > abs(dy)) {
            if (st.sel0 != null) {
                val pos = if (dx < 0) st.sel0.first else st.sel0.last
                host.execute(EngineCommand.SetSelection(pos, pos))
            } else if (st.del) {
                val pos = (st.caret0 + if (dx < 0) -1 else 1).coerceAtLeast(0)
                host.execute(EngineCommand.SetSelection(pos, pos))
            } else {
                host.execute(EngineCommand.MoveCursor(if (dx < 0) -1 else 1))
            }
            host.haptic(HapticKind.DRAG_TICK)
            return
        }

        // Slow drag released: the selection persists as it stands (deletion is
        // the DELETE key only — nothing destructive lives on a swipe).
        if (st.del) {
            host.haptic(if (selStart != selEnd) HapticKind.CONFIRM else HapticKind.COMMIT)
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

        // Slow long vertical gestures past GESTURE_T.
        if (abs(dy) >= abs(dx)) {
            if (dy < 0) {
                doShift(t)                                  // swipe up = shift / caps
                return
            }
            if (layer != Layer.ALPHA) {                     // down in any layer = back to alpha
                setLayer(Layer.ALPHA)
                return
            }
            // Alpha: start-x half decides — left = symbols, right = calculator.
            setLayer(if (st.x0 < boardW / 2f) Layer.SYM else Layer.NUM)
        }
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
        // Reset transient state on every session, including restarts. Layer
        // memory deliberately doesn't survive sessions (password-field hygiene);
        // numeric-class fields open straight onto the calculator (M6), and the
        // universal swipe-down exit still works if the user wants letters.
        clearPointers()
        shiftState = 0
        lastSpaceTs = 0
        lastShiftTs = 0
        val target = when (info?.inputType?.and(InputType.TYPE_MASK_CLASS)) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
            -> Layer.NUM
            else -> Layer.ALPHA
        }
        if (layer != target) {
            layer = target
            buildKeys()
        }
        host.requestRender()
    }

    override fun onSelectionUpdate(selStart: Int, selEnd: Int) {
        // Includes echoes of our own SetSelection commands — harmless: drags
        // snapshot anchor/f at engagement and never re-read these mid-drag.
        this.selStart = selStart
        this.selEnd = selEnd
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
