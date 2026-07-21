package io.github.wksaurey.mosaic

import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The Mosaic keyboard engine — port of reference/grid24-proto.html (the
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
class MosaicEngine(private val host: EngineHost) : KeyboardEngine {

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
        const val LETTER_ROW_H = 48f     // dp — prototype clamp(40px, 5.6vh, 52px) midpoint
        const val FN_ROW_H = 60f         // dp — function row ~25% taller
        const val DEAD_ZONE = 18f        // dp — gap below board (nav inset stacks on top)
        const val GAP = 4f               // dp — visual gap between keys
        const val KEY_RADIUS = 10f       // dp — round corner style (slider-tunable)
        const val CHAMFER_CUT = 5f       // dp — chamfer facet, FIXED by design
                                         // (Kolter 2026-07-19: not user-editable;
                                         // 7 -> 5 same day, "drop it even more")

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

    /* Colors come from the selected Theme (Themes.kt); "slate" is the
     * prototype's CSS :root palette verbatim and the default. */
    private var theme = Themes.ALL.getValue(Themes.DEFAULT)

    private class Key(
        val pri: String,
        val sec: String?,          // merged-key hold secondary
        val num: String?,          // positional digit (alpha layer, right half)
        val fn: String? = null,    // "del" | "space" — function row keys
    ) {
        val rect = RectF()
        var idx = -1               // alpha-grid index (mosaic tile lookup); -1 = fn
        var payload: String? = null // clip layer: full text this key commits
        var pressed = false
        var holdFlipped = false    // hold fired: label shows the alt (prototype .longing)
    }

    private enum class Layer { ALPHA, SYM, NUM, CLIP }

    /** Grid keys, row-major (24 on alpha/sym, 16 on num) — rebuilt on layer change. */
    private val keys = ArrayList<Key>()
    private var layer = Layer.ALPHA

    /** 6 columns on alpha/symbols; calculator 4; clip layer = full-width rows. */
    private fun cols(): Int = when (layer) {
        Layer.NUM -> 4
        Layer.CLIP -> 1
        else -> 6
    }

    /** Function row (experiment 2026-07-19): SHIFT/ENTER added as 1u tap
     *  fallbacks for the swipe-up-shift / space-hold-enter gestures (which
     *  remain). DELETE-left / SPACE-right preserved in the middle. Widths in
     *  grid units of 6 — tweak FN_UNITS to try e.g. four 1.5u keys. */
    private val fnShift = Key("SHIFT", null, null, fn = "shift")
    private val fnDel = Key("⌫ DELETE", null, null, fn = "del")
    private val fnSpace = Key("SPACE", null, null, fn = "space")
    private val fnEnter = Key("ENTER", null, null, fn = "enter")
    private var fnOrder = arrayOf(fnShift, fnDel, fnSpace, fnEnter)
    private var fnUnits = floatArrayOf(1f, 2f, 2f, 1f)

    private fun rebuildFnRow() {
        if (tun.fnRowKeys) {
            fnOrder = arrayOf(fnShift, fnDel, fnSpace, fnEnter)
            fnUnits = floatArrayOf(1f, 2f, 2f, 1f)
        } else {                                            // classic prototype row
            fnOrder = arrayOf(fnDel, fnSpace)
            fnUnits = floatArrayOf(3f, 3f)
        }
    }

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

    /* Live tunables (settings lab) — defaults mirror Config. */
    private var tun = Tunables()

    /* Keyboard-side state (prototype globals). */
    private var rawKeyMode = false // TYPE_NULL host (terminal): arrows, no selection
    private var shiftState = 0     // 0 none, 1 next, 2 lock
    private var autoShifted = false // shift was ARMED BY AUTO-CAPS (can auto-disarm)
    private var lastSpaceTs = 0L
    private var selStart = 0
    private var selEnd = 0

    // Real display density + board size, cached from measure/render (which
    // always precede touch). The onPointer* callbacks deliberately carry neither.
    private var density = 3f
    private var boardW = 0
    private var boardH = 0

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x36000000 }
    private val shadowRect = RectF()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Themes.ALL.getValue(Themes.DEFAULT).line // re-set per render from theme
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

    override val backgroundColor: Int get() = theme.bg

    init {
        buildKeys()
    }

    private fun buildKeys() {
        keys.clear()
        if (layer == Layer.CLIP) {
            // Clip slots (v4 of the layer — Kolter's two-tier model): row 0 =
            // live SOURCES (system clipboard ⌁, delete buffer ⌫ — read-only,
            // always paste, even over a selection); rows 1-3 = six manual
            // REGISTERS. With a live selection, registers are store targets
            // (tap = copy in, hold = cut in); otherwise tap pastes. Flick ⇢ exits.
            val clips = host.clips()
            val storing = selStart != selEnd
            repeat(8) { i ->
                val txt = clips.getOrNull(i)
                val label = when {
                    i == 0 -> "⌁ " + (txt?.let(::clipLabel) ?: "system ·")
                    i == 1 -> "⌫ " + (txt?.let(::clipLabel) ?: "deleted ·")
                    txt != null -> clipLabel(txt)
                    storing -> "＋ store"
                    else -> "·"
                }
                keys.add(Key(label, null, null).also {
                    it.idx = keys.size
                    it.payload = txt
                })
            }
            if (boardW > 0) layoutKeys(boardW, boardH)
            return
        }
        val rows = when (layer) {
            Layer.ALPHA -> Layouts.ALPHA[tun.layout] ?: Layouts.ALPHA.getValue(Layouts.DEFAULT)
            Layer.SYM -> Layouts.SYM
            else -> Layouts.NUM
        }
        rows.forEachIndexed { ri, row ->
            row.forEachIndexed { ci, def ->
                // Positional holds (digits + punctuation) ride the alpha layer only.
                val num = if (layer == Layer.ALPHA) Layouts.ALPHA_HOLDS[ri][ci] else null
                keys.add(Key(def.pri, def.sec, num).also { it.idx = keys.size })
            }
        }
        if (boardW > 0) layoutKeys(boardW, boardH)   // column count may have changed
    }

    private fun clipLabel(text: String): String {
        val flat = text.replace("\n", "⏎").trim()
        return if (flat.length > 16) flat.take(15) + "…" else flat
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
        return ((4 * tun.rowHeight + tun.fnRowHeight + tun.deadZone) * density).toInt()
    }

    /** Cell layout mirrors the prototype's keyAt(): 6 equal columns; letter rows
     *  equally subdivide the space above the function row's measured top. */
    private fun layoutKeys(w: Int, h: Int) {
        boardW = w
        boardH = h
        val dead = tun.deadZone * density
        val fnH = tun.fnRowHeight * density
        val fnTop = h - dead - fnH
        val nc = cols()
        val cellH = fnTop / 4f
        val insetX = tun.gapH * density / 2f
        val insetY = tun.gapV * density / 2f
        // Side dead zones: keys lay out inside [side, w-side]; hit-testing
        // forgives margin touches to the edge keys (keyAt clamps).
        val side = tun.sideZone * density
        val uw = w - 2 * side
        if (layer == Layer.CLIP) {
            keys.forEachIndexed { i, k ->
                val r = i / 2
                val c = i % 2
                k.rect.set(
                    side + c * uw / 2f + insetX, r * cellH + insetY,
                    side + (c + 1) * uw / 2f - insetX, (r + 1) * cellH - insetY,
                )
            }
        } else {
            val cw = uw / nc.toFloat()
            keys.forEachIndexed { i, k ->
                val r = i / nc
                val c = i % nc
                k.rect.set(
                    side + c * cw + insetX, r * cellH + insetY,
                    side + (c + 1) * cw - insetX, (r + 1) * cellH - insetY,
                )
            }
        }
        // Fn-row widths are unit-based over 6, independent of the grid's column
        // count (proportions hold on the 4-col calculator too).
        val unitW = uw / 6f
        var accU = 0f
        fnOrder.forEachIndexed { i, k ->
            val x0 = side + accU * unitW
            accU += fnUnits[i]
            k.rect.set(x0 + insetX, fnTop + insetY, side + accU * unitW - insetX, fnTop + fnH - insetY)
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
        val dead = tun.deadZone * density
        val fnTop = boardH - dead - tun.fnRowHeight * density
        // Side dead zones forgive to the edge keys: clamp x into the usable band.
        val side = tun.sideZone * density
        val uw = boardW - 2 * side
        val cx = (x - side).coerceIn(0f, uw - 0.01f)
        if (y >= fnTop) {
            val u = cx / (uw / 6f)                        // fn row lives in 6-unit space
            var acc = 0f
            fnOrder.forEachIndexed { i, k ->
                acc += fnUnits[i]
                if (u < acc) return k
            }
            return fnOrder.last()
        }
        if (layer == Layer.CLIP) {
            val row = ((y / (fnTop / 4f)).toInt()).coerceIn(0, 3)
            val col = if (cx < uw / 2f) 0 else 1
            return keys.getOrNull(row * 2 + col)
        }
        val nc = cols()
        val col = ((cx / (uw / nc.toFloat())).toInt()).coerceIn(0, nc - 1)
        val row = ((y / (fnTop / 4f)).toInt()).coerceIn(0, 3)
        return keys[row * nc + col]
    }

    /* ---------------- rendering ---------------- */

    override fun render(canvas: Canvas, widthPx: Int, heightPx: Int, density: Float) {
        this.density = density
        if (widthPx != boardW || heightPx != boardH) layoutKeys(widthPx, heightPx)
        strokePaint.color = theme.line
        fillPaint.color = theme.bg
        canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), fillPaint)
        keys.forEach { drawKey(canvas, it, -1) }
        fnOrder.forEachIndexed { i, k -> drawKey(canvas, k, i) }
    }

    /** Key silhouette per corner style: rounded radius (slider-tunable) or 45°
     *  chamfer at a FIXED 7dp facet with light vertex softening (~20% of the
     *  cut) — crisp bevels, no blade edges, not user-scalable by design. */
    private val keyPath = Path()
    private var softenPx = -1f
    private var soften: CornerPathEffect? = null
    private fun drawKeyShape(canvas: Canvas, rect: RectF, paint: Paint) {
        val base = if (tun.cornerStyle == "chamfer") Config.CHAMFER_CUT else Config.KEY_RADIUS
        val c = (base * density).coerceAtMost(minOf(rect.width(), rect.height()) / 2f)
        if (tun.cornerStyle == "chamfer") {
            if (softenPx != c * 0.2f) {
                softenPx = c * 0.2f
                soften = if (softenPx > 0f) CornerPathEffect(softenPx) else null
            }
            keyPath.rewind()
            keyPath.moveTo(rect.left + c, rect.top)
            keyPath.lineTo(rect.right - c, rect.top)
            keyPath.lineTo(rect.right, rect.top + c)
            keyPath.lineTo(rect.right, rect.bottom - c)
            keyPath.lineTo(rect.right - c, rect.bottom)
            keyPath.lineTo(rect.left + c, rect.bottom)
            keyPath.lineTo(rect.left, rect.bottom - c)
            keyPath.lineTo(rect.left, rect.top + c)
            keyPath.close()
            paint.pathEffect = soften
            canvas.drawPath(keyPath, paint)
            paint.pathEffect = null
        } else {
            canvas.drawRoundRect(rect, c, c, paint)
        }
    }

    private fun drawKey(canvas: Canvas, k: Key, fnIndex: Int) {
        // Mosaic themes tint individual alpha-layer keys / fn slots.
        val tile = if (k.fn == null && layer == Layer.ALPHA) theme.tiles[k.idx] else null
        val fnFill = if (fnIndex >= 0 && theme.fnFills?.size == fnOrder.size) theme.fnFills!![fnIndex] else null
        val fnInk = if (fnIndex >= 0 && theme.fnInks?.size == fnOrder.size) theme.fnInks!![fnIndex] else null

        // The SHIFT key displays the armed/locked state the gesture never could.
        val active = k.pressed || k.holdFlipped || (k.fn == "shift" && shiftState > 0)
        fillPaint.color = when {
            active -> theme.accent
            tile != null -> tile.fill
            fnFill != null -> fnFill
            k.fn != null || k.sec != null -> theme.surface2
            else -> theme.surface
        }
        // Soft lift: the key silhouette offset down, in translucent black —
        // hardware-canvas-safe fake shadow, all themes.
        shadowRect.set(k.rect)
        shadowRect.offset(0f, 1.6f * density)
        drawKeyShape(canvas, shadowRect, shadowPaint)
        drawKeyShape(canvas, k.rect, fillPaint)
        drawKeyShape(canvas, k.rect, strokePaint)

        if (k.fn != null) {
            fnPaint.textSize = 13f * density
            fnPaint.color = if (active) theme.pressInk else (fnInk ?: theme.inkDim)
            val label = if (k.fn == "shift" && shiftState == 2) "CAPS" else k.pri
            drawCentered(canvas, label, fnPaint, k.rect)
            return
        }

        // Hold-flip shows the armed alt; otherwise glyph case tracks shift/caps
        // (deviation from the always-uppercase prototype — see class doc).
        // Clip labels render verbatim — they're text, not key glyphs.
        val glyph = when {
            layer == Layer.CLIP -> k.pri
            k.holdFlipped -> (altOf(k) ?: k.pri).uppercase()
            shiftState > 0 -> k.pri.uppercase()
            else -> k.pri.lowercase()
        }
        priPaint.textSize = when (layer) {
            Layer.NUM -> 23f    // calculator keys read bigger
            Layer.CLIP -> 13f   // clip rows are text, not glyphs
            else -> 19f
        } * density
        priPaint.color = when {
            active -> theme.pressInk
            layer == Layer.CLIP && k.idx < 2 -> theme.inkDim          // live source slots
            layer == Layer.CLIP && k.payload == null -> theme.inkDim  // empty slot
            else -> tile?.ink ?: theme.ink
        }
        drawCentered(canvas, glyph, priPaint, k.rect)

        // Secondary glyph, bottom-right: merged secondary (ink-dim) or positional
        // hold (theme hold-accent) — prototype .sec / .sec.num styling.
        val secText = k.sec ?: k.num
        if (secText != null && !k.holdFlipped) {
            secPaint.textSize = 10f * density
            secPaint.color = when {
                active -> theme.pressInk
                k.sec != null -> tile?.ink ?: theme.inkDim
                else -> tile?.holdAccent ?: theme.holdAccent
            }
            secPaint.alpha = if (active || k.sec != null) 255 else 235
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
                autoShifted = false
                host.requestRender()
            }
        }
        // Auto-space after sentence punctuation (setting) — alpha holds and the
        // symbol layer only: the calculator types "3.14", terminals type paths.
        // Apostrophe/quotes excluded: mid-word characters.
        if (tun.autoSpacePunct && !rawKeyMode && layer != Layer.NUM &&
            ch.length == 1 && ch[0] in ",.?!;:"
        ) {
            out += " "
        }
        host.execute(EngineCommand.CommitText(out))
        host.haptic(HapticKind.COMMIT)
        // One-shot symbol layer (setting): any symbol typed hops back to alpha.
        if (layer == Layer.SYM && tun.symOneShot) setLayer(Layer.ALPHA)
    }

    private fun doSpace(t: Long) {
        val before = host.textBeforeCursor(48)
        val prevCharIsSpace = before?.lastOrNull() == ' '
        // Prototype guard: some non-whitespace must precede (48-char window
        // approximates "anywhere before the cursor") — no ". " at field start.
        val hasContentBefore = before?.isNotBlank() == true
        // Never in raw-key hosts: injecting ". " into a shell command is hostile.
        if (!rawKeyMode && selStart == selEnd && prevCharIsSpace && hasContentBefore &&
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

    /** Clip slot tap. Sources (idx 0-1) always paste — over a selection too
     *  (the replaced text lands in the delete buffer, so it's reversible).
     *  Registers (idx 2-7): with a live selection, STORE (copy) into the slot;
     *  otherwise paste it. Every action hops back to letters. */
    private fun clipTap(k: Key) {
        if (k.idx >= 2 && selStart != selEnd) {
            host.execute(EngineCommand.StoreClip(k.idx, cut = false))
            host.haptic(HapticKind.CONFIRM)
            setLayer(Layer.ALPHA)
            return
        }
        val text = k.payload ?: return   // empty slot
        host.execute(EngineCommand.CommitText(text))
        host.haptic(HapticKind.COMMIT)
        setLayer(Layer.ALPHA)
    }

    private fun fnTap(k: Key, t: Long) {
        when (k.fn) {
            "space" -> doSpace(t)
            "del" -> {
                host.execute(EngineCommand.Backspace)
                host.haptic(HapticKind.COMMIT)
            }
            "shift" -> doShift(t)
            "enter" -> {
                host.execute(EngineCommand.Enter)
                host.haptic(HapticKind.COMMIT)
            }
        }
    }

    private fun doShift(t: Long) {
        // Pure toggle: shift-once on/off (also exits caps). Caps lock = HOLD the
        // SHIFT key — the double-tap/double-swipe window was removed 2026-07-19.
        shiftState = if (shiftState == 0) 1 else 0
        autoShifted = false          // manual intent always wins over auto-caps
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
            st.holdTask = host.schedule(tun.holdMs) {
                st.long = true
                k.holdFlipped = true
                host.haptic(HapticKind.HOLD_FLIP)
                host.requestRender()
            }
        }
        if (k.fn == "del") {                                // hold delete = repeat
            st.holdTask = host.schedule(tun.holdMs) {
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
        if (k.fn == "shift") {                              // hold shift = caps lock
            st.holdTask = host.schedule(tun.holdMs) {       // (2026-07-19: replaces double-tap)
                st.held = true
                shiftState = if (shiftState == 2) 0 else 2
                autoShifted = false
                host.haptic(HapticKind.HOLD_FLIP)
                host.requestRender()
            }
        }
        // Clip layer with a live selection: HOLD a manual register = CUT into
        // it (the two live source slots, idx 0-1, are never store targets).
        if (layer == Layer.CLIP && k.fn == null && k.idx >= 2 && selStart != selEnd) {
            st.holdTask = host.schedule(tun.holdMs) {
                st.held = true
                host.execute(EngineCommand.StoreClip(k.idx, cut = true))
                host.haptic(HapticKind.CONFIRM)
                setLayer(Layer.ALPHA)
            }
        }
    }

    private fun repeatBackspace(st: PointerState) {
        host.execute(EngineCommand.Backspace)
        host.haptic(HapticKind.COMMIT)   // key-driven: buzzes with taps, not gesture ticks
        st.repTask = host.schedule(Config.REPEAT_MS) { repeatBackspace(st) }
    }

    /* ---- drag zone helpers (prototype leftOver/rightOver/zoneRate) ----
     * leftOver > 0 near the left break/edge, rightOver > 0 near the right. */

    private fun leftOver(dx: Float, x: Float): Float =
        maxOf(-dx - tun.delBreak * density, (Config.DEL_EDGE * density - x) * 3f)

    private fun rightOver(dx: Float, x: Float): Float =
        maxOf(dx - Config.DEL_REV_BREAK * density, (x - (boardW - Config.DEL_EDGE * density)) * 3f)

    private fun zoneRate(over: Float): Float {
        val n = over / (Config.DEL_RATE_SPAN * density)
        return minOf(tun.delRateMax, tun.delRateMin + n * n * Config.DEL_RATE_SCALE)
    }

    private fun dragClamp(st: PointerState) {
        // Raw-key hosts: no trustworthy text bounds (Termux reports only the
        // text before the cursor) and arrows self-limit at line edges anyway.
        if (rawKeyMode) return
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
            val step = v - (st.lastVal ?: 0)
            st.lastVal = v
            if (rawKeyMode) {
                // Terminal host: relative arrow-key steps, not absolute positions.
                if (step != 0) host.execute(EngineCommand.MoveCursor(step))
            } else if ((st.mode == DragMode.BACK || st.mode == DragMode.FWD) && v <= 0) {
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
        if (st.maxTravel > tun.tapT * density) {
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
            st.maxTravel > tun.dragT * density && abs(dx) > abs(dy)
        ) {
            st.del = true
            // Clamp bound, queried once — text can't change during a drag. Fields
            // that won't report length get a loose bound; setSelection past the
            // real end is ignored by well-behaved editors and recovers on reverse.
            // Raw-key hosts skip the query (their extract lies; clamp is off anyway).
            st.textLen = if (rawKeyMode) 0 else host.textLength() ?: (maxOf(selStart, selEnd) + 100_000)
            if (st.key?.fn == null || rawKeyMode) {        // letter keys / dead zone: cursor.
                                                           // Raw hosts: fn-row drags too —
                                                           // terminals have no selection.
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
                val d = ddx / (tun.delStep * density)
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
        val tapPx = tun.tapT * density
        val gesturePx = tun.gestureT * density

        // Quick flicks = COPY (left) / PASTE (right) — 2026-07-19 redesign; the
        // ±1 cursor nudge is removed (slow drags own cursor movement). Raw-key
        // hosts keep arrow flicks: terminals can't do context copy/paste.
        if (st.maxTravel > tapPx && (t - st.t0) < Config.FLICK_MS && abs(dx) > abs(dy)) {
            if (rawKeyMode) {
                host.execute(EngineCommand.MoveCursor(if (dx < 0) -1 else 1))
                host.haptic(HapticKind.DRAG_TICK)
                return
            }
            // A fast flick can cross DRAG_T mid-gesture and briefly engage the
            // drag engine, collapsing the selection live — restore the
            // touch-down snapshot so copy/paste act on what the user selected.
            // Engine-side fields update OPTIMISTICALLY too: the clip layer
            // builds its selection slot from them before the echo lands
            // (logcat-verified miss 2026-07-19).
            if (st.del && st.sel0 != null) {
                host.execute(EngineCommand.SetSelection(st.sel0.first, st.sel0.last))
                selStart = st.sel0.first
                selEnd = st.sel0.last
            }
            if (dx < 0) {
                if (st.sel0 != null || selStart != selEnd) {
                    host.execute(EngineCommand.Copy)
                    host.haptic(HapticKind.CONFIRM)
                } else {
                    host.haptic(HapticKind.CANCEL)      // nothing selected: no
                }                                       // surprise whole-text copy
            } else {
                // Flick right toggles the clipboard layer: in from anywhere,
                // out from within (same gesture both ways).
                setLayer(if (layer == Layer.CLIP) Layer.ALPHA else Layer.CLIP)
            }
            return
        }

        // Slow drag released: the selection persists as it stands (deletion is
        // the DELETE key only — nothing destructive lives on a swipe).
        // Haptics: selection release = CONFIRM (state changed, events toggle);
        // cursor-drag release = silent (2026-07-19: gesture motion never buzzes).
        if (st.del) {
            if (selStart != selEnd) host.haptic(HapticKind.CONFIRM)
            return
        }

        if (st.maxTravel <= tapPx) {                        // tap
            val k = st.key ?: return
            when {
                k.fn != null -> fnTap(k, t)
                layer == Layer.CLIP -> clipTap(k)
                st.long -> commitChar(altOf(k) ?: k.pri)    // hold fired: commit the alt
                else -> commitChar(k.pri)
            }
            return
        }
        if (st.maxTravel < gesturePx) {                     // slow flick band: forgiving sloppy tap
            val k = st.key ?: return
            when {
                k.fn != null -> fnTap(k, t)
                layer == Layer.CLIP -> clipTap(k)
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
            // (Clipboard layer opens via flick-right, not swipe-down — the
            // thirds split stole territory from sym/num and was reverted.)
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
        // TYPE_NULL = "send me raw key events" (terminals: Termux). Selection
        // and setSelection-based cursor movement are meaningless there.
        rawKeyMode = info != null && info.inputType == InputType.TYPE_NULL
        shiftState = 0
        lastSpaceTs = 0
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
        // getCursorCapsMode often reports 0 while the fresh InputConnection
        // settles, and onUpdateSelection never fires for an unchanged (0,0) —
        // a session-start eval alone misses "capitalize on entering a field".
        // Re-evaluate after the connection settles.
        host.schedule(120) { evalAutoCaps() }
    }

    override fun onSelectionUpdate(selStart: Int, selEnd: Int) {
        // Includes echoes of our own SetSelection commands — harmless: drags
        // snapshot anchor/f at engagement and never re-read these mid-drag.
        this.selStart = selStart
        this.selEnd = selEnd
        evalAutoCaps()
    }

    /** Auto-caps: arm shift at sentence starts; auto-disarm only what auto
     *  armed (manual shift/caps is never overridden). Skipped mid-gesture —
     *  drag echoes would hammer the IPC — and re-evaluated on release-echo. */
    private fun evalAutoCaps() {
        android.util.Log.d(
            "Mosaic",
            "evalAutoCaps: autoCaps=${tun.autoCaps} raw=$rawKeyMode touches=${touches.size} shift=$shiftState",
        )
        if (!tun.autoCaps || rawKeyMode || touches.isNotEmpty()) return
        if (shiftState == 0 && host.autoCapsNow()) {
            shiftState = 1
            autoShifted = true
            host.requestRender()
        } else if (autoShifted && shiftState == 1 && !host.autoCapsNow()) {
            shiftState = 0
            autoShifted = false
            host.requestRender()
        }
    }

    override fun onFinishInput() {
        clearPointers()
    }

    override fun applyTunables(t: Tunables) {
        if (t == tun) return
        tun = t
        theme = Themes.ALL[t.theme] ?: Themes.ALL.getValue(Themes.DEFAULT)
        rebuildFnRow()
        buildKeys()          // layout choice / fn row / geometry may have changed
        host.requestRender()
    }

    private fun clearPointers() {
        touches.values.forEach { it.cancelTasks() }
        touches.clear()
        keys.forEach { it.pressed = false; it.holdFlipped = false }
        fnOrder.forEach { it.pressed = false; it.holdFlipped = false }
    }
}
