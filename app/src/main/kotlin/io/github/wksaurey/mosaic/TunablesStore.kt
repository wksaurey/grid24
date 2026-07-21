package io.github.wksaurey.mosaic

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences persistence for Tunables. Written by SettingsActivity,
 * re-read by MosaicIme on every onStartInputView — so a changed setting
 * applies the next time the keyboard opens, no restart.
 */
object TunablesStore {
    private const val PREFS = "mosaic"

    const val K_LAYOUT = "layout"
    const val K_THEME = "theme"
    const val K_CORNER_STYLE = "corner_style"
    const val K_FN_ROW = "fn_row_keys"
    const val K_AUTO_CAPS = "auto_caps"
    const val K_AUTO_SPACE_PUNCT = "auto_space_punct"
    const val K_SYM_ONE_SHOT = "sym_one_shot"
    const val K_ROW_H = "row_height"
    const val K_FN_ROW_H = "fn_row_height"
    const val K_GAP_H = "gap_h"
    const val K_GAP_V = "gap_v"
    const val K_SIDE_ZONE = "side_zone"
    const val K_HAPTIC_PCT = "haptic_pct"
    const val K_HAPTIC_DUR_PCT = "haptic_dur_pct"
    const val K_HAPTIC_TAPS = "haptic_taps"
    const val K_HAPTIC_HOLDS = "haptic_holds"
    const val K_HAPTIC_TICKS = "haptic_ticks"
    const val K_HAPTIC_EVENTS = "haptic_events"
    const val K_TAP_T = "tap_t"
    const val K_GESTURE_T = "gesture_t"
    const val K_DRAG_T = "drag_t"
    const val K_HOLD_MS = "hold_ms"
    const val K_DEL_STEP = "del_step"
    const val K_DEL_BREAK = "del_break"
    const val K_DEL_RATE_MIN = "del_rate_min"
    const val K_DEL_RATE_MAX = "del_rate_max"
    const val K_DEAD_ZONE = "dead_zone"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): Tunables {
        val d = Tunables() // defaults from MosaicEngine.Config
        val p = prefs(ctx)
        return Tunables(
            layout = p.getString(K_LAYOUT, d.layout) ?: d.layout,
            theme = p.getString(K_THEME, d.theme) ?: d.theme,
            cornerStyle = p.getString(K_CORNER_STYLE, d.cornerStyle) ?: d.cornerStyle,
            fnRowKeys = p.getBoolean(K_FN_ROW, d.fnRowKeys),
            autoCaps = p.getBoolean(K_AUTO_CAPS, d.autoCaps),
            autoSpacePunct = p.getBoolean(K_AUTO_SPACE_PUNCT, d.autoSpacePunct),
            symOneShot = p.getBoolean(K_SYM_ONE_SHOT, d.symOneShot),
            rowHeight = p.getInt(K_ROW_H, d.rowHeight.toInt()).toFloat(),
            fnRowHeight = p.getInt(K_FN_ROW_H, d.fnRowHeight.toInt()).toFloat(),
            gapH = p.getInt(K_GAP_H, d.gapH.toInt()).toFloat(),
            gapV = p.getInt(K_GAP_V, d.gapV.toInt()).toFloat(),
            sideZone = p.getInt(K_SIDE_ZONE, d.sideZone.toInt()).toFloat(),
            hapticPct = p.getInt(K_HAPTIC_PCT, d.hapticPct),
            hapticDurPct = p.getInt(K_HAPTIC_DUR_PCT, d.hapticDurPct),
            hapticTaps = p.getBoolean(K_HAPTIC_TAPS, d.hapticTaps),
            hapticHolds = p.getBoolean(K_HAPTIC_HOLDS, d.hapticHolds),
            hapticTicks = p.getBoolean(K_HAPTIC_TICKS, d.hapticTicks),
            hapticEvents = p.getBoolean(K_HAPTIC_EVENTS, d.hapticEvents),
            tapT = p.getInt(K_TAP_T, d.tapT.toInt()).toFloat(),
            gestureT = p.getInt(K_GESTURE_T, d.gestureT.toInt()).toFloat(),
            dragT = p.getInt(K_DRAG_T, d.dragT.toInt()).toFloat(),
            holdMs = p.getInt(K_HOLD_MS, d.holdMs.toInt()).toLong(),
            delStep = p.getInt(K_DEL_STEP, d.delStep.toInt()).toFloat(),
            delBreak = p.getInt(K_DEL_BREAK, d.delBreak.toInt()).toFloat(),
            delRateMin = p.getInt(K_DEL_RATE_MIN, d.delRateMin.toInt()).toFloat(),
            delRateMax = p.getInt(K_DEL_RATE_MAX, d.delRateMax.toInt()).toFloat(),
            deadZone = p.getInt(K_DEAD_ZONE, d.deadZone.toInt()).toFloat(),
        )
    }
}
