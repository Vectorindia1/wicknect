# HostelAutoLogin

Logs in to a captive-portal Wi-Fi (hostel, hotel, campus, cafe…) with **your own** stored credentials, and logs in
again when the session expires. Works with portals that use a normal HTML login form; you enter your own SSID and credentials. It only reproduces the normal login flow.
No auth bypass, no MAC spoofing, no scanning. No telemetry; everything stays on your device.

| Part | Where | Status |
|---|---|---|
| Shared spec / state machine / `portal.json` schema | `docs/SPEC.md` | done |
| HAR analyzer | `tools/har_analyzer.py` | done, tested on a synthetic HAR |
| Windows app (Python, tray) | `windows/` | done, 45 tests; `.exe` must be built on Windows |
| Android app (Kotlin, Compose) | `android/` | `:core` has 35 JVM tests; `:app` not yet built/run on a device |

**New here? Read `docs/USER_GUIDE.md` (plain-language, no technical steps).**

## 1. Set up (for any captive-portal Wi-Fi)

You do not need to know how your portal works. Automatic mode reads the portal's own login page the way a browser does.

1. Connect to your Wi-Fi (the one that shows a login page).
2. Open the app → **Connect** tab: check the Wi-Fi name(s) (several names with the same login go on separate lines), enter
   **your own** username and password, tap **Connect**.
3. If asked "Is this your Wi-Fi's login page?" tap **Yes** (this pins your password to that address).
4. Done: the app logs in again whenever the session expires.

Automatic mode cannot handle portals that need a CAPTCHA/OTP, or a JavaScript-only login page (no HTML form). For those,
build a manual profile: capture a HAR in Chrome (F12 → Network → Preserve log → log in → *Save all as HAR with content*), run
`python tools/har_analyzer.py capture.har -o portal.draft.json`, review it (secrets are replaced by placeholders; never share the raw
`.har`), and paste it in **Settings → Expert options** (Android) or `%APPDATA%\HostelAutoLogin\portal.json` (Windows).
Schema: `docs/SPEC.md` §4 and §4b; example: `docs/examples/portal.manual.example.json`.

### Wi-Fi keeps disconnecting?

The app notices when Wi-Fi drops and asks the OS to rejoin one of your listed networks (Windows: `netsh wlan connect`, so join each
network once manually so Windows saves it; Android 10+: registered as network suggestions, approve the system prompt once, and turn off
"switch to mobile data automatically"/"Wi-Fi+" in your phone's Wi-Fi settings). It never leaves a connected network, so a phone hotspot is left alone.

## 2. Windows

Requirements: Windows 10/11, Python 3.11+ on PATH.

```
cd windows
build.bat            # venv, deps, tests, PyInstaller --onefile --noconsole
```
Output: `windows\dist\HostelAutoLogin.exe`. First run opens the setup dialog (SSID, username, password, **Test Login**).
The password goes to Windows Credential Manager. Use **Detect my portal** in the dialog (advanced: tray → *Edit portal.json*). Logs: `%APPDATA%\HostelAutoLogin\logs` (tray → *Open Logs*).
`HostelAutoLogin.exe --check-config` validates the file.

Run from source: `pip install -r requirements-dev.txt && python -m pytest && python -m hostel_autologin`.

## 3. Android

Requirements: JDK 17+, Android SDK (API 34 platform + build-tools; easiest via Android Studio, or set `ANDROID_HOME`).

```
cd android
./gradlew :core:test            # pure-JVM logic tests (no SDK needed)
./gradlew assembleRelease       # debug-signed, installable
```
Output: `android/app/build/outputs/apk/release/app-release.apk` (enable "install unknown apps" to sideload).

In the app: **Setup** (SSID, username, password, *Detect my portal*, *Test Login*), then **Settings** → grant the battery-optimization exemption, and on Xiaomi/MIUI enable Autostart and lock the app in Recents.
Grant the location permission when asked: Android only reveals the Wi-Fi SSID to apps that hold it.
All requests are bound to the Wi-Fi `Network`, so they never go over mobile data. If the OS already reports the
network as validated the app does nothing. For JS-heavy portals set `"fallback": {"type": "browser", "webview": {...}}`
(see SPEC) to use the sandboxed WebView.

## 4. Building without local toolchains

`.github/workflows/windows.yml` and `android.yml` build the `.exe` and `.apk` on GitHub Actions
(Actions → run workflow → download the artifact).

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| "No portal set up yet" | Tap *Detect my portal* while on your Wi-Fi (section 1). |
| "no username/password form" / "browser-based" | Your portal is JavaScript-only; use a manual profile (section 1). |
| "asks for a CAPTCHA/OTP" | Cannot be automated. |
| FAILURE "portal rejected the credentials" | Wrong username/password. No retries happen; fix in Setup, then *Login Now*. |
| FAILURE "gave up after 5 attempts" | Retries (2s, 5s, 15s, 30s) exhausted. Check Logs, then *Login Now*. Often a wrong `success` check. |
| "refused: host ... is not the pinned portal host" | Portal redirected elsewhere or `portal_host` is wrong. This is by design. |
| Windows state stays OFFLINE | `netsh wlan show interfaces` shows no SSID (Win11 may need Location access on) → enable `login_if_ssid_unknown` in `%APPDATA%\HostelAutoLogin\settings.json`. |
| Android SSID shows "unreadable" | Grant location permission and turn Location on, or enable "Log in even if the SSID can't be read". |
| Android stops logging in after a while | Battery exemption + MIUI Autostart + lock in Recents (Settings tab). |
| Logs show `***` | Intentional masking of passwords, cookies, tokens and long random strings. |
