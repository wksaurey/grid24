package io.github.wksaurey.grid24

/**
 * The live-tunable subset of the engine's constants, plus feature toggles —
 * the "settings lab" payload. Pure Kotlin (no Android imports) so engines can
 * depend on it without touching platform APIs; persistence lives host-side in
 * TunablesStore. Defaults mirror Grid24Engine.Config, which remains the
 * greppable source of truth for tuned values (CLAUDE.md table).
 */
data class Tunables(
    val layout: String = Layouts.DEFAULT,
    val theme: String = Themes.DEFAULT,
    /** Key corner treatment: "round" or "chamfer" (45° bevel). Sizes are fixed
     *  by design: Config.KEY_RADIUS / Config.CHAMFER_CUT. */
    val cornerStyle: String = "round",
    /** 4-key fn row experiment (SHIFT/DELETE/SPACE/ENTER) vs classic 2-key. */
    val fnRowKeys: Boolean = true,
    /** Auto-arm shift at sentence starts (field start, after . ! ? + space). */
    val autoCaps: Boolean = true,
    /** Append a space after sentence punctuation (, . ? ! ; :) typed from the
     *  alpha holds or symbol layer. Never in the calculator or terminals;
     *  apostrophe/quotes are deliberately excluded (mid-word characters). */
    val autoSpacePunct: Boolean = true,
    /** One-shot symbol layer: typing any symbol returns to alpha. */
    val symOneShot: Boolean = true,
    val rowHeight: Float = Grid24Engine.Config.LETTER_ROW_H,
    val fnRowHeight: Float = Grid24Engine.Config.FN_ROW_H,
    val gapH: Float = Grid24Engine.Config.GAP,   // horizontal gap between keys
    val gapV: Float = Grid24Engine.Config.GAP,   // vertical gap between rows
    /** Side margins (dp). Spacing, not tap traps: margin touches forgive to
     *  the nearest edge key, like the bottom dead zone forgives to the fn row. */
    val sideZone: Float = 0f,
    /** Haptic intensity 0-100 (%). 0 = haptics off. Maps to VibrationEffect amplitude. */
    val hapticPct: Int = 35,
    /** Haptic duration scale 50-300 (%): multiplies each event's base ms,
     *  preserving the prototype's relative pattern (tick < tap < hold < confirm). */
    val hapticDurPct: Int = 100,
    val hapticTaps: Boolean = true,      // key commits
    val hapticHolds: Boolean = true,     // hold-flip, caps lock
    val hapticTicks: Boolean = true,     // one tick per CHARACTER the cursor/selection
                                         // crosses in drags/flicks (delete-repeat
                                         // buzzes under hapticTaps)
    val hapticEvents: Boolean = true,    // layer switches, double-space period
    val tapT: Float = Grid24Engine.Config.TAP_T,
    val gestureT: Float = Grid24Engine.Config.GESTURE_T,
    val dragT: Float = Grid24Engine.Config.DRAG_T,
    val holdMs: Long = Grid24Engine.Config.HOLD_MS,
    val delStep: Float = Grid24Engine.Config.DEL_STEP,
    val delBreak: Float = Grid24Engine.Config.DEL_BREAK,
    val delRateMin: Float = Grid24Engine.Config.DEL_RATE_MIN,
    val delRateMax: Float = Grid24Engine.Config.DEL_RATE_MAX,
    val deadZone: Float = Grid24Engine.Config.DEAD_ZONE,
)
