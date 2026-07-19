package io.github.wksaurey.grid24

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences persistence for Tunables. Written by SettingsActivity,
 * re-read by Grid24Ime on every onStartInputView — so a changed setting
 * applies the next time the keyboard opens, no restart.
 */
object TunablesStore {
    private const val PREFS = "grid24"

    const val K_LAYOUT = "layout"
    const val K_THEME = "theme"
    const val K_CORNER_STYLE = "corner_style"
    const val K_FN_ROW = "fn_row_keys"
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
        val d = Tunables() // defaults from Grid24Engine.Config
        val p = prefs(ctx)
        return Tunables(
            layout = p.getString(K_LAYOUT, d.layout) ?: d.layout,
            theme = p.getString(K_THEME, d.theme) ?: d.theme,
            cornerStyle = p.getString(K_CORNER_STYLE, d.cornerStyle) ?: d.cornerStyle,
            fnRowKeys = p.getBoolean(K_FN_ROW, d.fnRowKeys),
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
