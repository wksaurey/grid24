package io.github.wksaurey.grid24

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/**
 * The settings LAB — deliberately hand-rolled (zero dependencies; the androidx
 * preference library would break the empty dependency graph). Feature toggles
 * and live sliders for the tuned constants; every change persists immediately
 * and applies the next time the keyboard opens (Grid24Ime re-reads on
 * onStartInputView). The polished v2 settings replaces this skin and keeps the
 * TunablesStore plumbing. Reachable from the system keyboard settings gear
 * (method.xml settingsActivity) and the launcher.
 */
class SettingsActivity : Activity() {

    private object Ui {
        val BG = Color.parseColor("#191c22")
        val SURFACE = Color.parseColor("#22262e")
        val LINE = Color.parseColor("#363c48")
        val INK = Color.parseColor("#ece7d9")
        val DIM = Color.parseColor("#9a958a")
        val ACCENT = Color.parseColor("#e8873a")
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TunablesStore.prefs(this)
        buildUi()
    }

    private fun buildUi() {
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20)
            setPadding(p, p, p, dp(48))
        }
        // Anonymous subclass: when the dead-zone slider resizes the IME window,
        // the activity relayouts and a stock ScrollView auto-scrolls to its
        // focused child (the test fields, at the top). Returning 0 here disables
        // ALL auto-scroll-to-focus; manual scrolling is unaffected. Lab tradeoff:
        // tapping a field hidden behind the keyboard won't auto-reveal it.
        val scroll = object : ScrollView(this) {
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect): Int = 0
        }
        setContentView(scroll.apply {
            setBackgroundColor(Ui.BG)
            addView(list)
            // targetSdk 35+ edge-to-edge: without this the content starts under
            // the status bar / cutout. Pad by the system bars, keep our bg behind.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })

        text("GRID24 · TUNING LAB", 20f, Ui.ACCENT, bold = true)
        text("Changes apply the next time the keyboard opens. Defaults shown as (n).", 13f, Ui.DIM)

        header("Test fields — try changes right here")
        testField("Text — full grammar", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        testField("Number — should auto-open the calculator", InputType.TYPE_CLASS_NUMBER)
        testField("Password — no layer memory, no clipboard stash", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        header("Layout")
        dropdown(TunablesStore.K_LAYOUT, Layouts.DEFAULT, Layouts.ALPHA.keys.toList())

        header("Theme")
        dropdown(TunablesStore.K_THEME, Themes.DEFAULT, Themes.ALL.keys.toList())

        header("Key corners")
        dropdown(TunablesStore.K_CORNER_STYLE, Tunables().cornerStyle, listOf("round", "chamfer"))

        header("Function row")
        toggle("4-key row: SHIFT · DELETE · SPACE · ENTER", TunablesStore.K_FN_ROW, Tunables().fnRowKeys)
        desc("Off = classic two-key row (DELETE / SPACE). The swipe-up-shift and hold-space-enter gestures work either way.")

        header("Typing")
        toggle("Auto-capitalize sentences", TunablesStore.K_AUTO_CAPS, Tunables().autoCaps)
        desc("Arms shift at the start of a field and after . ! ? + space. Only auto-armed shift auto-disarms — your manual shift/caps is never touched. Off in password fields and terminals.")

        header("Tap & gesture thresholds — dp")
        slider("Tap travel limit (TAP_T)", TunablesStore.K_TAP_T, 8, 32, 1, Tunables().tapT.toInt(),
            "How far a finger may wobble and still count as a tap.")
        slider("Gesture threshold (GESTURE_T)", TunablesStore.K_GESTURE_T, 60, 160, 5, Tunables().gestureT.toInt(),
            "Travel needed before a slow move becomes a gesture (shift, layers). Sloppy taps under this still type their letter.")
        slider("Drag engagement (DRAG_T)", TunablesStore.K_DRAG_T, 30, 120, 5, Tunables().dragT.toInt(),
            "Travel before a horizontal slide starts moving the cursor (letter keys) or selecting (bottom row). Lower = drags catch sooner.")
        slider("Hold tier — ms (HOLD_MS)", TunablesStore.K_HOLD_MS, 120, 400, 10, Tunables().holdMs.toInt(),
            "How long to hold a key before its corner character arms. Also when delete-repeat starts.")

        header("Drag physics")
        slider("Positional dp per char (DEL_STEP)", TunablesStore.K_DEL_STEP, 10, 34, 1, Tunables().delStep.toInt(),
            "Finger distance per character in the 1:1 tracking zone. Lower = faster cursor/selection per inch of thumb.")
        slider("Velocity zone distance (DEL_BREAK)", TunablesStore.K_DEL_BREAK, 120, 340, 10, Tunables().delBreak.toInt(),
            "How far from touch-down the 1:1 zone ends and auto-scroll speed takes over (screen edges force it too).")
        slider("Velocity floor chars/s (DEL_RATE_MIN)", TunablesStore.K_DEL_RATE_MIN, 2, 24, 1, Tunables().delRateMin.toInt(),
            "Speed the moment the velocity zone engages.")
        slider("Velocity cap chars/s (DEL_RATE_MAX)", TunablesStore.K_DEL_RATE_MAX, 30, 90, 5, Tunables().delRateMax.toInt(),
            "Top speed when pushed deep into the zone or pinned at a screen edge.")

        header("Board")
        slider("Key row height — dp (LETTER_ROW_H)", TunablesStore.K_ROW_H, 40, 64, 1, Tunables().rowHeight.toInt(),
            "Height of the four letter rows.")
        slider("Function row height — dp (FN_ROW_H)", TunablesStore.K_FN_ROW_H, 44, 84, 2, Tunables().fnRowHeight.toInt(),
            "Height of the SHIFT/DELETE/SPACE/ENTER row.")
        slider("Dead zone / board lift (DEAD_ZONE)", TunablesStore.K_DEAD_ZONE, 0, 40, 2, Tunables().deadZone.toInt(),
            "Empty gap below the function row — lifts the whole board off the bottom edge. Taps landing in it still count as bottom-row keys.")

        resetButton()
    }

    /* ---------------- widgets ---------------- */

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
            list.addView(this)
        }

    private fun header(s: String) {
        text(s.uppercase(), 12f, Ui.ACCENT, bold = true).setPadding(0, dp(20), 0, dp(2))
    }

    private fun desc(s: String) {
        text(s, 12.5f, Ui.DIM).setPadding(0, 0, 0, dp(6))
    }

    private fun testField(hintText: String, type: Int) {
        list.addView(EditText(this).apply {
            hint = hintText
            inputType = type
            setTextColor(Ui.INK)
            setHintTextColor(Ui.DIM)
            textSize = 15f
        })
    }

    private fun dropdown(key: String, default: String, options: List<String>) {
        val current = prefs.getString(key, default)
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(Ui.INK)
                    textSize = 16f
                }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(Ui.INK)
                    setBackgroundColor(Ui.SURFACE)
                    textSize = 16f
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                }
        }
        list.addView(Spinner(this, Spinner.MODE_DROPDOWN).apply {
            this.adapter = adapter
            setSelection(options.indexOf(current).coerceAtLeast(0), false)
            setPopupBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Ui.SURFACE)
                setStroke(dp(1), Ui.LINE)
            })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit().putString(key, options[pos]).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
    }

    private fun toggle(label: String, key: String, default: Boolean) {
        @Suppress("UseSwitchCompatOrMaterialCode") // zero-dep lab: platform Switch is fine
        val sw = Switch(this).apply {
            text = label
            setTextColor(Ui.INK)
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(key, on).apply()
            }
            setPadding(0, dp(8), 0, dp(8))
        }
        list.addView(sw)
    }

    /** Slider + numeric text box, two-way synced (sliders are fiddly sometimes). */
    private fun slider(label: String, key: String, min: Int, max: Int, step: Int, default: Int, help: String? = null) {
        val value = prefs.getInt(key, default)

        val caption = TextView(this).apply {
            text = "$label ($default)"
            textSize = 14f
            setTextColor(Ui.INK)
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            setTextColor(Ui.ACCENT)
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            minWidth = dp(72)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Ui.SURFACE)
                setStroke(dp(1), Ui.LINE)
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(caption, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(input)
        })
        if (help != null) desc(help)

        val bar = SeekBar(this).apply {
            this.min = min / step
            this.max = max / step
            progress = value / step
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = p * step
                prefs.edit().putInt(key, v).apply()
                if (input.text.toString() != v.toString()) input.setText(v.toString())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toIntOrNull() ?: return
                val clamped = v.coerceIn(min, max)
                prefs.edit().putInt(key, clamped).apply()
                val p = clamped / step
                if (bar.progress != p) bar.progress = p   // fromUser=false: no echo loop
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        list.addView(bar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun resetButton() {
        list.addView(Button(this).apply {
            text = "Reset everything to defaults"
            setOnClickListener {
                prefs.edit().clear().apply()
                list.removeAllViews()
                buildUi()
            }
        })
        text("Defaults live in Grid24Engine.Config (see CLAUDE.md tuned-constants table).", 12f, Ui.DIM)
    }
}
