# Android IME Development — research digest (2026-07-17)

Researched for the Grid24 port (Kotlin, single Canvas view, no Compose, minSdk 31).

## InputMethodService essentials

Manifest (three load-bearing pieces — `BIND_INPUT_METHOD` permission, `android.view.InputMethod`
intent-filter, meta-data `android.view.im` → xml resource; note it's `im`, not `ime`):

```xml
<service android:name=".Grid24Ime"
    android:label="@string/ime_label"
    android:permission="android.permission.BIND_INPUT_METHOD"
    android:exported="true">                  <!-- required since API 31 -->
    <intent-filter>
        <action android:name="android.view.InputMethod" />
    </intent-filter>
    <meta-data android:name="android.view.im" android:resource="@xml/method" />
</service>
```

`res/xml/method.xml`: one `<subtype>` (en-US, `imeSubtypeMode="keyboard"`);
`supportsSwitchingToNextInputMethod="true"`; omit `settingsActivity` for v1.

Lifecycle order and where things go:
1. `onCreate()` — heavy one-time setup (layout tables, Paint objects, Vibrator).
2. `onCreateInputView()` — called **once**, lazily; return the single BoardView (cached, reused).
3. `onStartInput(EditorInfo, restarting)` — session begins; read inputType/imeOptions here.
4. `onStartInputView(...)` — view about to show. **Reset transient state explicitly every time**
   (layer→alpha, gesture state cleared). This is where inputType-numeric → auto-number-layer
   and password suppression live. #1 bug source: assuming fresh state here.
5. `onFinishInputView` / `onFinishInput` — restore defaults so the next field starts clean.

Never cache `currentInputConnection` — fetch fresh per use, null-check every call.

## Edge-to-edge / insets (targetSdk 35+) — MEDIUM confidence, budget an on-device pass

targetSdk 35 edge-to-edge enforcement has caused real IME regressions (bottom row clipped
behind the nav pill). Fix: `setOnApplyWindowInsetsListener` on BoardView, pad bottom by
`WindowInsetsCompat.Type.navigationBars()` insets (+ consider `systemGestures()` sides).
This *is* the CLAUDE.md "dead-zone gap" — implement as real inset consumption so it adapts to
3-button vs gesture nav. Test both nav modes on the Pixel. Sideload distribution means targetSdk
can be held at 34 during build-out if it fights back; plan the insets pass at M6 regardless.
`windowSoftInputMode`/`adjustResize` advice is for app Activities — ignore it, doesn't apply to IMEs.

## InputConnection rules

- Commit: `ic.commitText(str, 1)` (shift applied keyboard-side first).
- Backspace: selection exists → `commitText("", 1)`; else `deleteSurroundingText(1, 0)`.
  Counts UTF-16 code units — emoji may need 2 (non-issue for v1 English; leave a comment).
- Cursor/selection: `ic.setSelection(a, b)`; wrap multi-op sequences in
  `beginBatchEdit()`/`endBatchEdit()` (delete+commit replace = one atomic change, no flicker).
- **Source of truth = `onUpdateSelection` push callback** — keep selStart/selEnd fields synced
  from it (user can touch the field directly). Use `getTextBeforeCursor(1,0)` for content peeks
  (double-space-period check); don't poll `getExtractedText` as a cursor tracker (IPC round-trip,
  badly implemented in some apps).
- **Echo hazard:** your own `setSelection` triggers an `onUpdateSelection` echo — compare against
  what you just set to avoid feedback loops in the selection-drag engine.
- Broken-app fallback (Termux, some webviews ignore setSelection/getText*): send raw
  `sendKeyEvent` — `KEYCODE_DEL`, `KEYCODE_DPAD_LEFT/RIGHT`. HeliBoard/AOSP keep exactly this
  fallback path. Expect to need it for the Termux test-matrix entry.
- Password fields: `TYPE_TEXT_VARIATION_PASSWORD`/`WEB_PASSWORD`/`VISIBLE_PASSWORD`,
  `TYPE_NUMBER_VARIATION_PASSWORD`; honor `IME_FLAG_NO_PERSONALIZED_LEARNING`. Grid24 has no
  learning store — compliant by construction; just suppress layer memory and commit plainly.

## MotionEvent ↔ web PointerEvent mapping

Per-pointer state map keyed by **pointer id** (stable), never index (renumbers when fingers lift).

| Web | Android |
|---|---|
| `pointerdown` | `ACTION_DOWN` / `ACTION_POINTER_DOWN` + `getPointerId(getActionIndex())` |
| `pointermove` | `ACTION_MOVE` — no action index; loop all `0 until pointerCount` |
| `pointerup` | `ACTION_UP` / `ACTION_POINTER_UP` |
| `pointercancel` | `ACTION_CANCEL` — **abandon all pointers, commit nothing** (gesture nav steals touches near bottom edge; skipping this = stuck-key bugs) |
| `e.pointerId` | `getPointerId(index)` |
| `e.clientX/Y` | `getX(index)`/`getY(index)` (view-local) |
| `e.timeStamp` | `event.eventTime` |

Always `getActionMasked()` (not `getAction()`); return `true` from consumed `onTouchEvent`.
`ACTION_MOVE` carries historical batched samples (`getHistoricalX`) — use them in the
selection-drag velocity engine for smoother DEL_RATE physics; optional for taps.
Timers (hold, repeat): use a `Handler` with `sendEmptyMessageDelayed`, mirroring
Unexpected Keyboard's `Pointers.java` — not ad-hoc threads.

## Haptics

Default to **`view.performHapticFeedback(...)`** — no VIBRATE permission, auto-respects system
settings, hardware-tuned on Pixels. Mapping for prototype `buzz()` calls:
`KEYBOARD_TAP` = commit · `LONG_PRESS` = hold flips to alt · `CLOCK_TICK`/`SEGMENT_TICK` =
per-char pulse in drags/repeat · `CONFIRM`/`REJECT` = double-space period / cancel.
Reach for `Vibrator`/`VibrationEffect` (+ permission) only if amplitude tiers demand it.
Never `FLAG_IGNORE_GLOBAL_SETTING` in a keyboard.

## Prior art (all FOSS)

- **Unexpected Keyboard (Julow/Unexpected-Keyboard)** — the closest analog; small enough to read
  whole (Java, ~two dozen files). **Read `Pointers.java` (~825 lines) before writing
  BoardView.onTouchEvent.** Also `Keyboard2View.java` (single Canvas view), `Config.java`
  (all constants one place), `KeyValue.java` (tagged union for what a key emits — good model for
  a Kotlin sealed type), `KeyEventHandler.java` (value → InputConnection routing). Its gesture
  *semantics* differ (8-way directional swipes vs Grid24's hold/duration grammar) — steal
  structure, not semantics.
- **HeliBoard** — large; value: JSON layout schema (borrowed from FlorisBoard) and battle-tested
  key-event fallbacks. Don't adopt its View-hierarchy rendering.
- **FlorisBoard** — Compose (forbidden here), but the architectural boundary is the reference:
  `KeyboardManager` (state) / `InputEventDispatcher` (gesture routing) / renderer, layouts as
  data files. Watch `k3lp` (their standalone layout-parser lib) for later.

## Pluggable-engine architecture (the "many keyboards, one bootstrap" answer)

- **Gesture grammar is behavior → code behind an interface. Letter arrangement is data → files
  (later).** A config-file DSL for gestures would be over-engineering.
- Stable core, written once: `Grid24Ime` (service, sessions, insets), `BoardView` (Canvas +
  raw-touch demux), InputConnection executor, haptics.
- Swappable: a `KeyboardEngine` interface — receives pointer events + selection updates, renders
  to Canvas via provided geometry, emits high-level intents (`CommitText`, `Backspace`,
  `MoveCursor`, `SetSelection`, `Haptic`) the core executes.
- v1: one module, one implementation (`Grid24Engine` = faithful prototype port), engine selected
  by build-time constant. Layouts stay hardcoded Kotlin data per CLAUDE.md.
- Later: more engine implementations in-module; factor shared-interaction-model layouts into
  JSON assets (adopt the FlorisBoard/HeliBoard schema, don't invent). Multi-module Gradle only
  if/when the core freezes and variants ship — premature for a solo dev now.

## Iteration loop

- No hot-reload for IMEs. Fast loop: `./gradlew installDebug && adb shell ime set <pkg>/.Grid24Ime`
  (script it — reinstall sometimes drops the active-IME selection).
  Enable once: `adb shell ime enable <pkg>/.Grid24Ime` (`ime list -a` to find ids).
- Emulator: M0/M1 sanity only. No genuine multi-touch, no real thumb dynamics — all gesture/hold/
  drag tuning happens on the physical Pixel.
- scrcpy: excellent as mirror/recorder for gesture bugs — run `--no-control` so it can't hijack
  input; its keyboard-injection modes bypass the IME under test.
- Real tuning lever: every constant in one `object Config` (greppable, one-line change per rebuild).

## Sources

- https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method
- https://developer.android.com/reference/android/inputmethodservice/InputMethodService
- https://developer.android.com/reference/android/view/inputmethod/InputConnection
- https://developer.android.com/reference/android/view/MotionEvent
- https://developer.android.com/develop/ui/views/haptics/haptic-feedback
- https://github.com/Julow/Unexpected-Keyboard (esp. srcs/juloo.keyboard2/Pointers.java)
- https://github.com/Helium314/HeliBoard · https://github.com/florisboard/florisboard
- https://github.com/gazlaws-dev/codeboard/issues/137 (Android 15 IME insets regression)
- https://medium.com/androiddevelopers/insets-handling-tips-for-android-15s-edge-to-edge-enforcement-872774e8839b
