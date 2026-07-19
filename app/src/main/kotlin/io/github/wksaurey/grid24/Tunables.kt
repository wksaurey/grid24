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
