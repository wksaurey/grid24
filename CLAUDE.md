# CLAUDE.md — Grid24 Keyboard: HTML Prototype → Android IME Port

## What this project is

Grid24 is a custom Android keyboard developed through extensive prototyping in a single-file HTML test bench (`grid24-proto.html`, included in this repo — **it is the executable spec**; when this document and the prototype disagree, the prototype wins). The design premise: 24 large keys in a 6×4 grid (26 letters via two hold-merged keys), no autocorrect dependency, a rich gesture layer for cursor/selection control, and three keyboard layers. Everything about the interaction model has already been designed, tuned, and validated in the prototype. **This port's job is plumbing, not design.** Do not "improve" gesture semantics or timing constants without being asked.

## v1 scope — get it typing on the phone

**In scope:** InputMethodService, the full board rendering, the complete gesture grammar, all four alpha layouts (hardcoded), symbol + number layers, merged-key/digit holds, shift/caps, double-space period, space-hold enter, delete hold-repeat, haptics, portrait only.

**Explicitly deferred to v2+ (do not build now):** settings UI (including runtime layout selection — moved out of M6 2026-07-18; v1 ships with the build-time constant — plus a fn-row swap or fully editable function keys, and an adjustable dead-zone/board-lift value), the tap-tap layout editor, persistence of layout edits, themes, landscape, one-handed mode, any prediction/autocorrect, clipboard features, Play Store anything.

**Future wishlist (v3+, roadmap only — added 2026-07-17):** autocorrect, word prediction, swipe-to-type (glide typing), voice input, emoji support. These are opt-in additions layered on top, never dependencies — the "no autocorrect dependency" design premise stands, and the no-INTERNET / minimal-permission posture must survive them (on-device models only; voice input via the system speech IME hand-off, not a mic permission, unless deliberately re-decided).

**Clipboard design direction (decided 2026-07-19, build at v2):** an IN-KEYBOARD clipboard — internal local history store fed by (a) a `ClipboardManager.OnPrimaryClipChangedListener` (the default IME is exempt from Android 10+'s background clipboard-read restriction, so it captures copies made anywhere) and (b) Grid24's own stash-on-destroy, which then redirects here instead of clobbering the system clipboard. UI: clips rendered as a dynamic **layer** (same mechanism as sym/num — tap a clip = commit it, universal swipe-down exit). Conventions to keep: auto-expiry of unpinned clips, pinning, skip sensitive-flagged clips, local-only forever. FOSS references: FlorisBoard (most featureful), HeliBoard (simpler, closer to our v2 target).

**Gesture backlog (2026-07-19 — approved for prototyping, NOT yet implemented).** Strategy: build the v2 settings page first as a *feature lab* — each of these ships behind a live toggle so variants can be flipped on/off on-device and judged by feel, instead of committing to any of them blind. **The lab exists as of 2026-07-19** (`SettingsActivity` + `Tunables`/`TunablesStore`, live pref-listener refresh): layout/theme/corner dropdowns, fn-row toggle, described sliders with numeric input for the tuned constants, in-page test fields. Backlog gestures land there as toggles. The backlog:
- **Hold SHIFT = caps lock** (the keyboard's own hold-idiom applied to the new SHIFT key; double-tap stays too).
- **Hold ENTER = literal newline** (escape hatch now that tap-Enter performs the field's send/search action).
- **Word-wise cursor flicks on the function row** (char-wise flicks stay on the letter rows — mirrors the letters-vs-fn-row drag split; needs a text peek to find word boundaries).
- **Word-delete: quick flick left on DELETE** (destruction is DELETE's identity so the no-destructive-swipes pillar doesn't apply; the clipboard stash is the safety net). Possibly the Gboard-style hold-then-slide accelerating variant instead — decide by feel.
- **Momentary vs latched layers** (swipe-down-and-hold = one-shot, plain swipe = latched as today) — Kolter specifically wants this for the future clipboard/copy-paste layer.
- **Vertical drags** — in raw-key hosts: DPAD_UP/DOWN (terminal command history); in normal fields: line-jumping within paragraphs (no more looping the cursor all the way around). Line semantics through InputConnection are the hard part; the terminal half is nearly free.
- **Rejected: diagonal swipes** — they'd tax the sloppiness forgiveness that makes fast typing survivable. Leave that space unclaimed permanently.

**Macros (idea captured 2026-07-19 — mechanism deliberately undecided, iterate later):** user-defined text snippets committed as one action — email address, home address, etc. Candidate triggers, none chosen: 8vim-style swipe gestures, a dedicated macros layer (same layer mechanism as clipboard), or additional secondary "keys" on existing keys (a third hold tier or gesture-on-key). Constraints when designed: snippets stored local-only; suppressed in password fields; per-field-type sanity (don't offer the home address in a URL bar) is optional polish, not a dependency.

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

Five rows: four key rows + a taller function row (**DELETE spanning the left half, SPACE the right** — the prototype's `fnOrder` default, confirmed as Kolter's preference 2026-07-18; a swap toggle — or fully editable function keys — goes to the v2 settings page). Row heights: letter rows clamp(40px, 5.6vh, 52px) equivalent; function row ~25% taller.

**Dead zone below the board (18dp + nav inset):** exists for comfort — typing at the very bottom edge of the glass is awkward, and raising the board noticeably improved feel — and because the system's IME strip (globe/hide-keyboard) lives in the nav-inset band beneath it. Hit-testing forgives low fn-row taps through the 18dp strip (they count as SPACE/DELETE, prototype behavior); the nav-inset band below is strictly the system's. The 18dp value is a candidate for the v2 settings page (adjustable board lift).

### Layouts (hardcoded for v1, exactly these)

Format: string = plain key; `[primary, holdSecondary]` = merged key. Hold = secondary.

```
qwerty:   q w e r t y | x⁄z c u⁄v i o p | a s d j k l | f g h b n m
optimal:  x⁄z p j v m c | k⁄q t y b r h | g o e l u s | d i a f n w
vowels:   x⁄z v s u p j | k⁄q m i l c g | b h o e t w | d r a n f y
vbottom:  x⁄z c r y p j | g m t h d v | w l a o n s | f u e k⁄q i b
```

(Written row-by-row, 6 per row; `⁄` marks merged keys.) Default layout: `optimal` (build-time constant `Layouts.DEFAULT`; was qwerty until Kolter switched daily-driving 2026-07-17). A layout cycle action exists in the prototype's settings row; for v1 expose it any minimal way (long-press on a corner of the board, or just a build-time constant) — a real settings screen is v2.

`optimal` was produced by simulated annealing for two-thumb hand alternation (74.9% of English bigrams cross hands vs 53.7% for folded QWERTY), then hand-tuned. The optimizer script (`optimize_layout.py`) is in the repo for reference; not needed for the port.

### Alpha-layer digit holds (positional, not per-letter)

On the alpha layer only, the right-half keys carry hold-digits in a phone-keypad shape, **assigned by grid position** regardless of which letter sits there:

```
cols 3-5, row 0: 1 2 3 · row 1: 4 5 6 · row 2: 7 8 9 · row 3: , 0 .
punctuation (2026-07-17 addition): row 3 col 1: ? · col 2: ' · col 3: ,
```

Hold resolution priority: a key's merged secondary beats its positional hold (`sec || num`). Known shadow: vbottom's `k⁄q` sits at row3-col3 and eats the comma hold there — acceptable while vbottom isn't the daily layout.

### Symbol layer (6×4) and number layer (4-column calculator)

```
symbols:  ! @ # $ % ^⁄&  |  ( ) [ ] { }  |  '⁄` "⁄~ ; : - _  |  ,⁄< .⁄> ? /⁄\ =⁄| +⁄*
numbers:  /⁄( 7 8 9  |  *⁄) 4 5 6  |  -⁄% 1 2 3  |  +⁄^ .⁄: 0⁄, =⁄$
```

The number layer renders at **4 columns** (wider keys) with the operator column on the left, keypad on the right; the function row's two keys each span half. 32 symbol glyphs total (24 primaries + 8 holds) — this set was verified complete against common usage; don't add or move glyphs.

## Gesture grammar — port exactly

**Approved deviations from the prototype (Kolter, 2026-07-17/18)** — the prototype remains the spec everywhere else: (1) key glyphs render lowercase and flip uppercase with shift/caps (prototype drew uppercase always); (2) Enter resolves to the field's IME action (search/go/send) before falling back to `"\n"`; (3) slow horizontal drags on **letter keys move the cursor**; selection drags live on the **SPACE/DELETE row** only; (4) alpha bottom row carries positional punctuation holds (`?` `'` `,`); (5) **typing over a selection replaces it** (Android convention; the prototype collapsed-and-inserted, never destroying by typing) — as a recovery net, any selection destroyed by typing or DELETE is **stashed to the system clipboard first** (suppressed in password fields; groundwork for the planned clipboard features); (6) a running delete-repeat cannot co-engage a selection drag (the prototype allowed both simultaneously — treated as a prototype bug); (7) **caps lock = hold the SHIFT key** (gesture-backlog item promoted 2026-07-19); the 450ms double-shift window is gone everywhere — note the classic 2-key fn row therefore has no caps path while toggled on.

All discrimination is by travel distance and duration. **There is deliberately zero timing coordination between pointers** and nothing destructive lives on a swipe.

| Gesture | Detection | Action |
|---|---|---|
| Tap | travel ≤ TAP_T | commit key primary (or hold-secondary if hold fired) |
| Hold | ≥ HOLD_MS stationary on a key with an alt | key label flips to alt + haptic; release commits alt |
| Quick swipe ⇠/⇢ | travel > TAP_T, duration < FLICK_MS, horizontal-dominant | move cursor ±1 char; **if a selection existed at touch-down, collapse cursor to that end instead** |
| Slow drag ⇠/⇢ **on letter keys** | travel > GESTURE_T, slow, horizontal | **move the cursor continuously** (same hybrid physics, no selection) — 2026-07-17 deviation |
| Slow drag ⇠ **on SPACE/DELETE row** | travel > GESTURE_T, slow, leftward | select backward from cursor (extends an existing selection — the ratchet) |
| Slow drag ⇢ on fn row, no selection | same, rightward | select forward from cursor |
| Slow drag ⇢ on fn row, selection exists | same | slide the whole selection window through the text (reversible) |
| Swipe ↑ | slow, > GESTURE_T, vertical | shift toggle (caps lock = **hold the SHIFT key**, 2026-07-19 deviation — the 450ms double-shift window is removed; shift/swipe while locked ⇒ off) |
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
| DRAG_T | 60 | horizontal drag engagement — split from GESTURE_T 2026-07-17 so slides register sooner; vertical gestures + tap forgiveness still use GESTURE_T |
| DEL_STEP | 20 px/char | positional zone resolution (prototype had 22; retuned on-device 2026-07-17) |
| DEL_BREAK | 220 px from touch | ~160px (~8 chars) of positional runway past DRAG_T (prototype: 280 past GESTURE_T=100 — same runway, retuned 2026-07-17) |
| DEL_REV_BREAK | 120 px | reverse-velocity breakpoint right of touch origin |
| DEL_EDGE | 60 px | either screen edge forces the corresponding velocity zone |
| DEL_RATE_MIN/MAX | 10 / 60 chars-sec | quadratic: MIN + (over/60)² × 18 (MIN was 2 in prototype; retuned on-device 2026-07-17) |
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
7. **M6:** inputType auto-number, polish pass against the prototype side-by-side. (Layout switching moved to the v2 settings menu, 2026-07-18 — v1 keeps the build-time constant in `Layouts.DEFAULT`.)

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
