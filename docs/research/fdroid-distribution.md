# F-Droid & Sideload Distribution — research digest (2026-07-17)

Researched for Grid24 (Kotlin IME, AGPL-3.0, minSdk 31, never Google Play). Full-confidence
claims are grounded in official F-Droid docs; flagged items are community-sourced.

## Landscape context (mid-2026)

- **Google Developer Verification decree** (enforcement starts Sept 2026): certified Android
  devices will require apps from *verified* developers regardless of channel. **GrapheneOS and
  other AOSP builds are exempt** — the Pixel 10 Pro target is unaffected. Matters only for
  eventual public reach onto stock-Android devices.
- F-Droid publicly opposes the decree; landscape is contested but F-Droid inclusion remains viable.

## F-Droid inclusion requirements

1. Public git repo + FOSS license file (AGPL-3.0 qualifies; use SPDX id `AGPL-3.0-only` consistently
   in LICENSE, manifest metadata, and fdroiddata YAML — mismatches cause rejections).
2. **Only FOSS dependencies** — no GMS, no Firebase, no proprietary analytics, no prebuilt blobs.
3. Fastlane/Triple-T metadata folder structure in the repo (description, screenshots).
4. Submission: fork `fdroiddata`, write `metadata/<applicationId>.yml` yourself, open an MR
   (faster than the RFP issue route). Key fields: `License`, `SourceCode`, `Builds:` blocks
   (versionName/versionCode/commit/gradle flavor), `AutoUpdateMode: Version v%v` + `UpdateCheckMode: Tags`.
5. F-Droid's buildserver has **no network access at build time** — any plugin that phones home fails.
6. After metadata merge: ~24–48h to appear. Review latency itself is queue-dependent (days–weeks).

## Reproducible builds

- **Optional.** Default: F-Droid builds from source and signs with its own per-app key.
- Opt-in "developer-signed + reproducible verification" lets the *same* dev-signed APK install
  from F-Droid, GitHub, and IzzyOnDroid without signature-mismatch reinstalls.
- Pure-Kotlin, no-NDK, no-native apps (Grid24's exact shape) are the easy case: pin JDK/AGP/
  build-tools, avoid timestamp embedding, and it usually reproduces.

## Distribution ladder (recommended order)

1. **Now (dev loop):** self-signed APK via Gradle `signingConfig`, `adb install`. GrapheneOS
   imposes no restrictions on user-installed apps.
2. **OTA updates for self/testers:** GitHub Releases APK + Obtainium.
3. **Public release, low friction:** IzzyOnDroid (distributes *your* signed binaries from GitHub
   releases; screens permissions, rejects debuggable/testOnly, VirusTotal, tracker scan; keeps a
   rolling ~3-version window).
4. **Public release, max trust:** main F-Droid.

## Signing

- Generate once, guard forever: `keytool -genkey -v -keystore grid24-release.jks -alias grid24
  -keyalg RSA -keysize 4096 -validity 10000`.
- Wire via Gradle `signingConfigs` reading a git-ignored `keystore.properties`; never commit the
  keystore or passwords; back up offline. Losing the key = no more updates over installed copies.
- AGP default v2+v3 schemes are correct. **Don't rely on v4-only APKs** — v4/incremental is a
  system-path install on Android 15+ and can fail file-manager sideloads on GrapheneOS.
- If pursuing developer-signed reproducible F-Droid later, this key becomes the published
  signature — pick it deliberately now.

## Buildserver compatibility

- JDK 17 baseline (AGP 8+ requires it). Pin `jvmToolchain(17)`.
- Pin a stable ~6-month-old AGP rather than week-old releases (buildserver image lags).
- No dynamic versions (`+`, `latest.release`); keep the dep graph tiny and auditable —
  Grid24 can plausibly ship with just `androidx.core`/`androidx.annotation`.

## IME category expectations (keyboards are sensitive)

- **Zero `INTERNET` permission** is the gold standard (HeliBoard, FlorisBoard both market this).
  Adding it later to a keyboard triggers formal scrutiny (Unexpected Keyboard v2.0.4 precedent).
  Grid24 needs no network — keep the manifest provably exfiltration-free.
- `VIBRATE` is benign/expected (and avoidable entirely via `performHapticFeedback`).
- Target **zero anti-feature flags**: no Tracking, NonFreeNet, NonFreeDep, NonFreeAssets, Ads.
- Model metadata on the fdroiddata YAML of: HeliBoard (`helium314.keyboard`), FlorisBoard
  (`dev.patrickgold.florisboard`), Unexpected Keyboard, Simple Keyboard.

## Sources

- https://f-droid.org/docs/Inclusion_How-To/
- https://f-droid.org/docs/Reproducible_Builds/
- https://f-droid.org/docs/Anti-Features/
- https://github.com/f-droid/fdroiddata/blob/master/CONTRIBUTING.md
- https://apt.izzysoft.de/fdroid/index/info · https://izzyondroid.org/quickstart/
- https://developer.android.com/studio/publish/app-signing
- https://grapheneos.org/usage · https://github.com/GrapheneOS/os-issue-tracker/issues/6860
- https://f-droid.org/2025/09/29/google-developer-registration-decree.html
- https://forum.f-droid.org/t/unexpected-keyboard-newly-added-internet-permission-need-a-protocol-for-this/34542
