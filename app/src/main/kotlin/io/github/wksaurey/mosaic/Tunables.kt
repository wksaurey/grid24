package io.github.wksaurey.mosaic

/**
 * The live-tunable subset of the engine's constants, plus feature toggles —
 * the "settings lab" payload. Pure Kotlin (no Android imports) so engines can
 * depend on it without touching platform APIs; persistence lives host-side in
 * TunablesStore. Defaults mirror MosaicEngine.Config, which remains the
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
    val rowHeight: Float = MosaicEngine.Config.LETTER_ROW_H,
    val fnRowHeight: Float = MosaicEngine.Config.FN_ROW_H,
    val gapH: Float = MosaicEngine.Config.GAP,   // horizontal gap between keys
    val gapV: Float = MosaicEngine.Config.GAP,   // vertical gap between rows
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
    val tapT: Float = MosaicEngine.Config.TAP_T,
    val gestureT: Float = MosaicEngine.Config.GESTURE_T,
    val dragT: Float = MosaicEngine.Config.DRAG_T,
    val holdMs: Long = MosaicEngine.Config.HOLD_MS,
    val delStep: Float = MosaicEngine.Config.DEL_STEP,
    val delBreak: Float = MosaicEngine.Config.DEL_BREAK,
    val delRateMin: Float = MosaicEngine.Config.DEL_RATE_MIN,
    val delRateMax: Float = MosaicEngine.Config.DEL_RATE_MAX,
    val deadZone: Float = MosaicEngine.Config.DEAD_ZONE,
)
