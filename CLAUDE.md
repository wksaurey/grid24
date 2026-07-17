# CLAUDE.md — Grid24 Keyboard: HTML Prototype → Android IME Port

## What this project is

Grid24 is a custom Android keyboard developed through extensive prototyping in a single-file HTML test bench (`grid24-proto.html`, included in this repo — **it is the executable spec**; when this document and the prototype disagree, the prototype wins). The design premise: 24 large keys in a 6×4 grid (26 letters via two hold-merged keys), no autocorrect dependency, a rich gesture layer for cursor/selection control, and three keyboard layers. Everything about the interaction model has already been designed, tuned, and validated in the prototype. **This port's job is plumbing, not design.** Do not "improve" gesture semantics or timing constants without being asked.

## v1 scope — get it typing on the phone

**In scope:** InputMethodService, the full board rendering, the complete gesture grammar, all four alpha layouts (hardcoded), symbol + number layers, merged-key/digit holds, shift/caps, double-space period, space-hold enter, delete hold-repeat, haptics, portrait only.

**Explicitly deferred to v2+ (do not build now):** settings UI, the tap-tap layout editor, persistence of layout edits, themes, landscape, one-handed mode, any prediction/autocorrect, clipboard features, Play Store anything.

**Target device:** Pixel 10 Pro on GrapheneOS. `minSdk 31`, `targetSdk` current. Portrait-only is acceptable and preferred for v1. Distribution: locally signed APK, sideloaded. License: AGPL-3.0.

## Architecture

- **Kotlin. One `InputMethodService` subclass** (`Grid24Ime`) whose `onCreateInputView()` returns one custom View.
- **One custom Canvas-drawn View** (`BoardView`) handling all rendering and all touch. Do NOT use Compose and do NOT build a View-per-key hierarchy — the prototype is a single pointer-event state machine over a single surface, and the port should preserve that shape 1:1. Multi-touch matters: track state per pointer ID exactly as the prototype's `touches` map does.
- Rendering is trivial: rounded-rect keys, primary glyph centered, secondary glyph small in the bottom-right corner, press/hold highlight states, layer-dependent grid (6 columns for alpha/symbols, 4 for the calculator). Colors in the prototype's CSS `:root` block; reuse them.
- **Hit-testing must measure real geometry, not assume equal rows** — the function row is taller than letter rows. See `keyAt()` in the prototype: letter rows subdivide the space above the function row's measured top.

### Pluggable engine boundary (added 2026-07-17)

Grid24 is the first of several keyboard prototypes; the Android bootstrapping must be written once. Split along this seam:

- **Core (stable, written once):** `Grid24Ime` (service lifecycle, sessions, insets), `BoardView` (Canvas surface + raw multi-touch demux keyed by pointer id), the InputConnection executor, haptics. Core knows nothing about any specific keyboard's grammar.
- **Engine (swappable):** a `KeyboardEngine` interface — receives pointer events, selection updates, and geometry; renders to the provided Canvas; emits high-level intents (`CommitText`, `Backspace`, `MoveCursor`, `SetSelection`, `Haptic`) that core executes. Gesture grammar, layout tables, and tuned constants all live engine-side.
- v1 ships one implementation, `Grid24Engine` — the faithful prototype port — selected by a build-time constant. Future prototypes are new `KeyboardEngine` implementations in the same module; no manifest, service, or plumbing changes. Layouts stay hardcoded Kotlin data for v1; factor into JSON assets only when multiple engines share an interaction model (adopt the FlorisBoard/HeliBoard schema then, don't invent one). Multi-module Gradle is deferred until the core freezes.

Rationale: gesture grammar is behavior (code behind an interface); letter arrangement is data. Research digests backing this and the toolchain/distribution choices: `docs/research/`.

## The board

Five rows: four key rows + a taller function row (SPACE spanning the left half, DELETE the right half by default — see `fnOrder`; a swap toggle exists in the prototype but defer its UI). Row heights: letter rows clamp(40px, 5.6vh, 52px) equivalent; function row ~25% taller; keep a dead-zone gap below the board so fast bottom-row typing doesn't graze Android gesture navigation.

### Layouts (hardcoded for v1, exactly these)

Format: string = plain key; `[primary, holdSecondary]` = merged key. Hold = secondary.

```
qwerty:   q w e r t y | x⁄z c u⁄v i o p | a s d j k l | f g h b n m
optimal:  x⁄z p j v m c | k⁄q t y b r h | g o e l u s | d i a f n w
vowels:   x⁄z v s u p j | k⁄q m i l c g | b h o e t w | d r a n f y
vbottom:  x⁄z c r y p j | g m t h d v | w l a o n s | f u e k⁄q i b
```

(Written row-by-row, 6 per row; `⁄` marks merged keys.) Default layout: `qwerty`. A layout cycle action exists in the prototype's settings row; for v1 expose it any minimal way (long-press on a corner of the board, or just a build-time constant) — a real settings screen is v2.

`optimal` was produced by simulated annealing for two-thumb hand alternation (74.9% of English bigrams cross hands vs 53.7% for folded QWERTY), then hand-tuned. The optimizer script (`optimize_layout.py`) is in the repo for reference; not needed for the port.

### Alpha-layer digit holds (positional, not per-letter)

On the alpha layer only, the right-half keys carry hold-digits in a phone-keypad shape, **assigned by grid position** regardless of which letter sits there:

```
cols 3-5, row 0: 1 2 3 · row 1: 4 5 6 · row 2: 7 8 9 · row 3: (none) 0 .
```

Hold resolution priority: a key's merged secondary beats its positional digit (`sec || num`). Merged keys are always placed in the left half in all four layouts precisely so this conflict never occurs — preserve that invariant if layouts are ever edited.

### Symbol layer (6×4) and number layer (4-column calculator)

```
symbols:  ! @ # $ % ^⁄&  |  ( ) [ ] { }  |  '⁄` "⁄~ ; : - _  |  ,⁄< .⁄> ? /⁄\ =⁄| +⁄*
numbers:  /⁄( 7 8 9  |  *⁄) 4 5 6  |  -⁄% 1 2 3  |  +⁄^ .⁄: 0⁄, =⁄$
```

The number layer renders at **4 columns** (wider keys) with the operator column on the left, keypad on the right; the function row's two keys each span half. 32 symbol glyphs total (24 primaries + 8 holds) — this set was verified complete against common usage; don't add or move glyphs.

## Gesture grammar — port exactly

All discrimination is by travel distance and duration. **There is deliberately zero timing coordination between pointers** and nothing destructive lives on a swipe.

| Gesture | Detection | Action |
|---|---|---|
| Tap | travel ≤ TAP_T | commit key primary (or hold-secondary if hold fired) |
| Hold | ≥ HOLD_MS stationary on a key with an alt | key label flips to alt + haptic; release commits alt |
| Quick swipe ⇠/⇢ | travel > TAP_T, duration < FLICK_MS, horizontal-dominant | move cursor ±1 char; **if a selection existed at touch-down, collapse cursor to that end instead** |
| Slow drag ⇠ | travel > GESTURE_T, slow, leftward | select backward from cursor (extends an existing selection — the ratchet) |
| Slow drag ⇢, no selection | same, rightward | select forward from cursor |
| Slow drag ⇢, selection exists | same | slide the whole selection window through the text (reversible) |
| Swipe ↑ | slow, > GESTURE_T, vertical | shift (tap-again within 450ms ⇒ caps lock; tap while locked ⇒ off) |
| Swipe ↓ on alpha | same, start-x in left half / right half | open symbol layer / open number layer |
| Swipe ↓ on sym or num | anywhere | return to alpha (universal exit) |
| SPACE tap | — | space; **double-space within 600ms ⇒ delete the space, insert ". ", auto-shift next** |
| SPACE hold | ≥ SPACE_HOLD_MS | commit newline (single fire) |
| DELETE tap | — | backspace (deletes selection if one exists) |
| DELETE hold | ≥ HOLD_MS | repeat backspace at REPEAT_RATE |

### Selection-drag physics (the hybrid engine — port `pointermove` faithfully)

The drag has a **neutral band** (1:1 positional tracking, DEL_STEP px per character, fully reversible to zero = cancel) bracketed by two **velocity zones**: past DEL_BREAK px of travel, or within DEL_EDGE px of either screen edge, the selection count grows/shrinks at a rate that ramps **quadratically** with overshoot (DEL_RATE_MIN at the boundary → DEL_RATE_MAX cap). Crossing a zone boundary **freezes the count for that event** (the `prevNeutral` guard) so retreating from a velocity burst never mass-reverses. Release persists the selection (highlight stays); it does not delete. A later quick-left-flick... does not delete either in the final model — deletion is the DELETE key only. Selection visual: the host field's own selection highlight (see InputConnection notes).

### Tuned constants — these numbers encode real debugging; keep them

| Constant | Value | Why |
|---|---|---|
| TAP_T | 18 css-px | thumbs wobble more than you think |
| GESTURE_T | 100 | below this, sloppy taps are forgiven as taps |
| FLICK_MS | 280 | fast-vs-slow is the real tap/drag discriminator; distance alone failed |
| HOLD_MS | 200 | 350 felt laggy; 200 verified comfortable |
| SPACE_HOLD_MS | 550 | must be far above HOLD_MS so the two hold tiers can't blur |
| DEL_STEP | 22 px/char | positional zone resolution |
| DEL_BREAK | 280 px from touch | leaves ~180px (~8 chars) of true positional runway past GESTURE_T |
| DEL_REV_BREAK | 120 px | reverse-velocity breakpoint right of touch origin |
| DEL_EDGE | 60 px | either screen edge forces the corresponding velocity zone |
| DEL_RATE_MIN/MAX | 2 / 60 chars-sec | quadratic: MIN + (over/60)² × 18 |
| repeat delay/rate | 200ms / 45ms | delete hold-repeat |

Convert css-px thresholds using density (`dp` ≈ css-px is close enough to start; expose as constants in one file for tuning).

## InputConnection mapping (the one conceptually new part)

The keyboard no longer owns the text buffer. Route everything through `currentInputConnection`:

- Letter/symbol/digit commit → `commitText(ch, 1)` (apply shift before committing; shift state is keyboard-side).
- Backspace → if a selection exists, `commitText("", 1)` clears it; else `deleteSurroundingText(1, 0)`.
- Cursor quick-swipes → read current selection via `getExtractedText`/`onUpdateSelection`, then `setSelection(pos, pos)`.
- Selection drags → track the anchor keyboard-side during the drag, apply with `setSelection(a, b)` live as the count changes.
- Selection state must be **read from the field** (`onUpdateSelection` callback keeps you synced), never assumed — the user can touch the text directly. The prototype's tap-to-place-cursor feature is **dropped**: the host text field already does that.
- Double-space period: check the char before the cursor via `getTextBeforeCursor(1, 0)`.
- Newline: `commitText("\n", 1)` is fine for v1 (proper `performEditorAction` handling for send/search fields is v2).
- Free win to include if trivial: `EditorInfo.inputType` numeric ⇒ auto-open the number layer for that field, restore on finish.

Known reality: some apps implement InputConnection badly. Test in at least: Fossify Messages, a browser URL bar, Termux, and a password field (suppress layer memory + never expose hold-digit popups oddly there).

## Milestones — build in this order, `adb install` and hand-test each

1. **M0:** IME skeleton registers, shows a gray view, commits "a" on any tap. (Settings → System → Keyboard → enable.)
2. **M1:** Board renders (qwerty layout), taps type, function row works, geometry-correct hit-testing.
3. **M2:** Holds — merged letters, positional digits, delete repeat, space-hold enter. Haptics via `VibrationEffect` (tick on commit, distinct patterns per event as in prototype `buzz()` calls).
4. **M3:** Quick swipes = cursor movement; shift/caps; double-space period.
5. **M4:** Selection drag engine, all three modes + hybrid physics, wired to `setSelection`.
6. **M5:** Layers — symbol + number, half-aware entry, universal exit, 4-column calculator rendering.
7. **M6:** All four layouts switchable (minimal mechanism), inputType auto-number, polish pass against the prototype side-by-side.

Regression instrument: `keyboard-bench.html` (also in repo) — type the same phrases on the native build vs the prototype in Vanadium and compare WPM/error.

## Repo layout suggestion

```
app/                    standard Android app module (Kotlin, no Compose)
reference/grid24-proto.html      ← executable spec
reference/keyboard-bench.html    ← measurement rig
reference/optimize_layout.py     ← layout provenance
reference/dyad-design-doc.md     ← historical: the rejected predecessor concept
docs/research/          distribution / IME / toolchain research digests (2026-07-17)
.claude/agents/android-specialist.md   ← Android platform specialist agent
CLAUDE.md               this file
```

## Style notes for the agent

Keep the state machine in one file, mirroring the prototype's structure (`pointerdown`/`move`/`up` handlers, per-pointer state objects) so the two stay diffable. Prefer boring code over clever code. When a behavior is ambiguous, open the prototype and do what it does. The human's background is QA engineering — leave the constants greppable, the states nameable, and the logs meaningful, because he will absolutely instrument this.
