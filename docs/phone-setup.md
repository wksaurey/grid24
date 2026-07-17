# Installing Grid24 on the Pixel (GrapheneOS) — dev loop setup

One-time setup (steps 1–5), then every iteration is just step 6.

## 1. Unlock Developer Options (one-time, on the phone)

Settings → About phone → tap **Build number** seven times (asks for your PIN).

## 2. Enable Wireless debugging (on the phone)

Settings → System → **Developer options**:

- Toggle **Wireless debugging** ON (phone must be on the same network as the laptop).
- Recommended: also toggle **USB debugging** ON — it stabilizes the wireless
  connection on GrapheneOS even with no cable attached.

GrapheneOS quirks to expect:

- **Keep the Wireless debugging screen open/foregrounded** during pairing and
  connecting — backing out can drop it.
- The **connect port changes** every time wireless debugging toggles, and the
  toggle can reset itself OFF after a reboot — re-read the port each session.

## 3. Pair (one-time, phone + WSL terminal)

On the phone: tap into **Wireless debugging** → **Pair device with pairing code**.
It shows a 6-digit code and an `IP:port` (this is the *pairing* port).

In a WSL terminal (new shell, so `~/.zshrc` env is loaded):

```bash
adb pair <phone-ip>:<pairing-port>
# it prompts for the 6-digit code
```

## 4. Connect (each session)

Back on the main Wireless debugging screen, read the `IP:port` under
"IP address & Port" (different port than pairing). Then:

```bash
adb connect <phone-ip>:<connect-port>
adb devices        # should list the phone as "device"
```

## 5. First install

```bash
cd ~/kolter/code/grid24
./scripts/dev-install.sh
```

The script builds the debug APK, installs it, enables the Grid24 IME, and makes
it the active keyboard (`adb shell ime enable/set` — no Settings digging needed).

Sanity check: open any app with a text field (Messenger, browser URL bar). The
gray Grid24 board should appear; **any tap types "a"** — that's the whole M0
feature set. Haptic tick on each tap if keyboard haptics are on system-wide.

To switch keyboards on the phone: the keyboard-picker icon in the bottom-right
of the nav bar (appears while a text field is focused), or
Settings → System → Keyboard → On-screen keyboard.

## 6. Every iteration afterward

```bash
./scripts/dev-install.sh
```

That's it — rebuild, reinstall, re-activate in one shot (reinstalls sometimes
drop the active-keyboard selection; the script re-sets it every time).

## Troubleshooting

- `adb devices` empty → wireless debugging toggled itself off, or the connect
  port changed; recheck step 4. Persistent flakiness → keep the Wireless
  debugging settings screen open, confirm USB debugging is also enabled.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` → a build signed with a different key is
  installed (e.g. release over debug): `adb uninstall io.github.wksaurey.grid24`
  then reinstall.
- Keyboard installed but not appearing → it's per-profile: install, enable, and
  test in the same GrapheneOS user profile that has debugging enabled.
- USB alternative (if wireless won't cooperate): run the adb server Windows-side
  (`adb.exe -a -P 5037 nodaemon server`), open Windows firewall TCP 5037 to the
  WSL subnet, and in WSL `export ADB_SERVER_SOCKET=tcp:<windows-host-ip>:5037` —
  client and server adb versions must match exactly.
