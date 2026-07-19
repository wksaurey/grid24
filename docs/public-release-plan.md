# Grid24 — Public-Facing Release Plan

Drafted 2026-07-19, at v1 feature-complete. Ordered so each phase is useful even
if the next never happens. Distribution research backing this: `docs/research/fdroid-distribution.md`.

## Phase 0 — repo & docs hygiene (prerequisite for everything)

- [ ] **Name decision comes FIRST** — "grid24" is a working title, not settled
      (Kolter, 2026-07-19). It gates the README, the logo, and critically the
      `applicationId` (`io.github.wksaurey.grid24`), which becomes permanent the
      moment the first public APK ships. Decide before anything below.
- [ ] README — first draft written and rejected 2026-07-19 (didn't land; redo
      after the name is settled, in Kolter's voice). **Required before F-Droid**
      (and effectively before any public repo promotion).
- [ ] Logo/launcher icon — after the name. Three throwaway SVG concepts were
      drafted and removed 2026-07-19 (git history has them if ever useful).
      **Required before F-Droid** (listing needs an icon; the app currently
      ships with the default Android icon).
- [ ] Recover missing reference files into `reference/`: `keyboard-bench.html`
      (needed for the bench regression anyway), `optimize_layout.py` (layout
      provenance), `dyad-design-doc.md` (historical). They live in the original
      claude.ai prototype session — export from there.
- [ ] Screenshots: the board on-device (alpha/sym/num layers, selection drag in
      action). Needed for the README and later for fastlane. `adb exec-out screencap`
      is enough.
- [ ] Decide the public tone of CLAUDE.md: it's a working port-spec (fine to keep
      public — it documents design intent unusually well), but skim for anything
      personal before promoting the repo anywhere.

## Phase 1 — v1 sign-off (quality gate before any public eyes)

- [ ] Test matrix (CLAUDE.md): Fossify Messages, browser URL bar, Termux
      (expect to need an `EngineCommand.SendKey` fallback), password field.
- [ ] Bench regression: `keyboard-bench.html` in Vanadium vs native, same
      phrases, WPM/error comparison.
- [ ] Dogfood period: Kolter daily-drives for at least a week; tuning deltas
      land as constant changes.

## Phase 2 — release engineering

- [ ] **Keystore day**: generate `grid24-release.jks` (4096-bit RSA, ~27yr,
      offline backup, git-ignored `keystore.properties`). The key is permanent
      identity — do this deliberately. Command is in docs/research/fdroid-distribution.md §4.
- [ ] Release `signingConfig` in `app/build.gradle.kts` reading `keystore.properties`.
- [ ] Version discipline: bump `versionCode`/`versionName` per release; tag
      releases `v0.x.y` (F-Droid `AutoUpdateMode: Version v%v` expects tags).
- [ ] `./gradlew assembleRelease` + `apksigner verify` sanity check.
- [ ] Optional but cheap now, valuable later: reproducible-build hygiene
      (already mostly true: pure Kotlin, no R8, pinned versions, zero deps).

## Phase 3 — distribution ladder (in order, each step optional)

1. [ ] **GitHub Releases**: tagged release with the signed APK attached.
       Publishes to anyone; Obtainium users get auto-updates immediately.
2. [ ] **Fastlane metadata** in-repo (`fastlane/metadata/android/en-US/`):
       short/full description, screenshots, changelogs. Feeds IzzyOnDroid and
       F-Droid both.
3. [ ] **IzzyOnDroid**: submission MR (developer-signed APKs pulled from GitHub
       releases; screening incl. permission scan — our zero-permission manifest
       sails through). Rolling ~3-version window.
4. [ ] **Main F-Droid**: metadata YAML MR to fdroiddata (`io.github.wksaurey.grid24.yml`),
       `Builds:` block, `AGPL-3.0-only` SPDX consistency (LICENSE file ✓).
       Re-verify current buildserver JDK/AGP support right before submitting —
       AGP 9.2 may be ahead of their image; be ready to hold an older AGP on a
       release branch if their build fails.
5. [ ] **Google developer-verification decree watch** (Sept 2026 rollout): only
       matters for stock-Android users installing after enforcement reaches
       their region. Decision point, not work, until the rules solidify.

## Non-negotiables that survive every phase

- Zero permissions (not even VIBRATE), zero INTERNET forever, zero third-party
  dependencies. This is the trust story for a keyboard — it is the marketing.
- AGPL-3.0. Local-only everything.
- The prototype + CLAUDE.md deviations list stay in-repo: the design provenance
  is part of what makes the project credible.

## Explicitly not in this plan

Play Store (never), paid anything, telemetry of any kind, CI release automation
(revisit when releases become frequent enough to be annoying).
