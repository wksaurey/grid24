# WSL2 Android Toolchain (no Android Studio) — research digest (2026-07-17)

Setup for building Grid24 from this WSL2 host, CLI-only, target = physical Pixel 10 Pro
(GrapheneOS). HIGH confidence on toolchain/adb mechanics; MEDIUM on GrapheneOS
wireless-debugging quirks (community-sourced — verify empirically).

## Version matrix (pinned)

| Component | Version | Notes |
|---|---|---|
| JDK | 17 (apt `openjdk-17-jdk`) | required by AGP 8.x and 9.x |
| AGP | 9.2.0 | current; avoids forced AGP-10 migration (legacy variant APIs removed H2 2026). Conservative alt: 8.9.x + Gradle 8.11.1 |
| Gradle | 9.4.1 | min/default for AGP 9.2; pin via wrapper |
| Kotlin | 2.1.x | no Compose compiler needed |
| build-tools | 36.0.0 | min/default for AGP 9.2 |
| compileSdk/targetSdk | 37 (or 36) | minSdk 31 per spec |

## SDK install (one-time)

```bash
sudo apt install -y openjdk-17-jdk unzip wget
export ANDROID_HOME="$HOME/android-sdk"        # + ANDROID_SDK_ROOT alias; both in ~/.zshrc
mkdir -p "$ANDROID_HOME/cmdline-tools" && cd "$ANDROID_HOME/cmdline-tools"
wget https://dl.google.com/android/repository/commandlinetools-linux-<BUILD>_latest.zip  # get current build no. from developer.android.com/studio
unzip commandlinetools-linux-*.zip && mv cmdline-tools latest   # latest/ nesting is mandatory
yes | sdkmanager --licenses
sdkmanager --install "platform-tools" "platforms;android-37" "build-tools;36.0.0"
# PATH += $ANDROID_HOME/cmdline-tools/latest/bin and $ANDROID_HOME/platform-tools
```

No NDK needed. Keep SDK and project on ext4 (never /mnt/c — 9P boundary murders Gradle's
thousands of small-file ops; the historical WSL2 Android build breakage was all cross-boundary).

## Project scaffold

`gradle init` cannot scaffold Android and the old `android create` is long gone — hand-write the
skeleton (right answer for this project anyway):

```
settings.gradle.kts
build.gradle.kts                  (root: plugins block, apply false)
gradle/libs.versions.toml         (version catalog — current idiom)
gradle/wrapper/  gradlew          (bootstrap: apt gradle → `gradle wrapper --gradle-version 9.4.1`, then apt copy is disposable)
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/kotlin/...           (Grid24Ime, BoardView, ...)
app/src/main/res/xml/method.xml   (IME metadata)
```

`gradle.properties`: `org.gradle.daemon=true`, `org.gradle.jvmargs=-Xmx2g`,
`org.gradle.caching=true`, `org.gradle.configuration-cache=true`.

## adb to the Pixel — three routes, ranked

**A (primary): adb server on Windows, client in WSL.**
Windows: `adb.exe -a -P 5037 nodaemon server`. WSL:
`export ADB_SERVER_SOCKET=tcp:<windows-host-ip>:5037` (gateway IP from `ip route show default`).
Hard rules: client/server **versions must match exactly** (easiest: invoke the same
platform-tools both sides), and add a Windows firewall inbound rule for TCP 5037 scoped to the
WSL subnet (172.16.0.0/12) — without it the connection drops silently.

**B (cable-free alt): wireless debugging** (Android 11+): `adb pair <ip>:<pair-port> <code>`
once, then `adb connect <ip>:<connect-port>`. WSL adb reaches the phone directly over LAN.
Ideal while thumb-testing (phone in hand, still connected).

**C (fussiest): usbipd-win passthrough** — works (usbipd 4.x auto-attach), but VID:PID churn
and udev fiddling make it the fallback, not the default. Not needed unless the device must be a
native Linux USB device.

## GrapheneOS specifics

- Developer options / USB / wireless debugging work as stock; no extra sideload friction.
- Wireless-debugging quirks (MEDIUM confidence): settings screen may need to stay foregrounded
  during pair/connect; enabling USB debugging too stabilizes wireless; the connect **port changes**
  every toggle and wireless debugging can reset off after reboot — re-read the port each time.
- **Signature trap:** `INSTALL_FAILED_UPDATE_INCOMPATIBLE` = key mismatch (not GrapheneOS-specific).
  Pin ONE debug keystore (`~/.android/debug.keystore` — copy it across hosts, or declare an
  explicit debug `signingConfig`); never install release-signed over debug-signed without
  `adb uninstall <pkg>` first. `adb install -r` only works when signatures match.
- Profiles: debugging is per Owner profile; adb targets the active profile — install, enable the
  IME, and test in the same profile. IME enablement survives reinstalls of the same package.

## Emulator: skip it

Emulator inside WSL2 = nested virtualization, not viable. Windows-side emulator + `adb connect`
works but is low-value for an IME: no genuine multi-touch, no real thumb dynamics, and the tuned
constants only mean anything on glass. Physical Pixel only; `keyboard-bench.html` comparisons
must run on the same device.

## Day-one time-eaters (in likelihood order)

1. adb version mismatch / missing firewall rule (Route A).
2. Forgetting to re-enable/re-select the IME after install → "the build is broken" (it isn't):
   script `./gradlew installDebug && adb shell ime set <pkg>/.Grid24Ime`.

## Sources

- https://developer.android.com/build/jdks · https://developer.android.com/build/releases/agp-9-2-0-release-notes
- https://developer.android.com/tools/sdkmanager
- https://github.com/microsoft/WSL/discussions/4692 (ADB in WSL2)
- https://learn.microsoft.com/en-us/windows/wsl/connect-usb (usbipd)
- https://discuss.grapheneos.org/d/9054 · https://minnowo.github.io/2023/graphenewifi-debugging/
- https://github.com/microsoft/WSL/issues/4197 · https://github.com/gradle/gradle/issues/14725 (fs perf)
- https://gist.github.com/stkptr/709d279212d4ee45133a8924724e2dc5 (CLI Android buildsystem structure)
