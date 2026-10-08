# HostelAutoLogin

Logs in to a captive-portal hostel Wi-Fi (SSID `University_Hostel`, portal `172.16.16.16`) with **your own**
stored credentials, and logs in again when the session expires. It only reproduces the normal login flow.
No auth bypass, no MAC spoofing, no scanning. No telemetry; everything stays on your device.

| Part | Where | Status |
|---|---|---|
| Shared spec / state machine / `portal.json` schema | `docs/SPEC.md` | done |
| HAR analyzer | `tools/har_analyzer.py` | done, tested on a synthetic HAR |
| Windows app (Python, tray) | `windows/` | done, 45 tests; `.exe` must be built on Windows |
| Android app (Kotlin, Compose) | `android/` | `:core` has 35 JVM tests; `:app` not yet built/run on a device |

## 1. Capture your login and create `portal.json`

The app never guesses the portal's login request; you supply it.

1. On `University_Hostel`, open Chrome and log out / wait for the session to expire so the login page shows.
2. F12 → **Network** → tick **Preserve log** (and optionally Disable cache) → click 🚫 to clear.
3. Log in normally; wait a few seconds for the post-login pages.
4. Right-click a request → **Save all as HAR with content**.
5. Run `python tools/har_analyzer.py capture.har --list`, then `python tools/har_analyzer.py capture.har -o portal.draft.json`
   (`--pick N` to choose another request).
6. Review the draft: passwords/cookies/tokens are replaced by `{{password}}`, `{{username}}`, `{{REVIEW:name}}`.
   Resolve each `{{REVIEW:...}}`, fill `success` / `bad_credentials` (a second HAR with a deliberately wrong password
   shows the portal's error text), delete the `_analysis` block, and save as `portal.json`.
   Do not share or commit the raw `.har` (it contains your password and cookies).

Schema and semantics: `docs/SPEC.md` §4. Until the file is filled in, both apps refuse to send anything
(`portal.json is still the placeholder`).

## 2. Windows

Requirements: Windows 10/11, Python 3.11+ on PATH.

```
cd windows
build.bat            # venv, deps, tests, PyInstaller --onefile --noconsole
```
Output: `windows\dist\HostelAutoLogin.exe`. First run opens the setup dialog (SSID, username, password, **Test Login**).
The password goes to Windows Credential Manager. Put your `portal.json` at `%APPDATA%\HostelAutoLogin\portal.json`
(tray → *Edit portal.json*; changes apply on the next cycle). Logs: `%APPDATA%\HostelAutoLogin\logs` (tray → *Open Logs*).
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

In the app: **Setup** (SSID, username, password, *Test Login*), then **Settings** → paste your `portal.json`,
grant the battery-optimization exemption, and on Xiaomi/MIUI enable Autostart and lock the app in Recents.
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
| "portal.json is still the placeholder" | Fill in `portal.json` (section 1). |
| FAILURE "portal rejected the credentials" | Wrong username/password. No retries happen; fix in Setup, then *Login Now*. |
| FAILURE "gave up after 5 attempts" | Retries (2s, 5s, 15s, 30s) exhausted. Check Logs, then *Login Now*. Often a wrong `success` check. |
| "refused: host ... is not the pinned portal host" | Portal redirected elsewhere or `portal_host` is wrong. This is by design. |
| Windows state stays OFFLINE | `netsh wlan show interfaces` shows no SSID (Win11 may need Location access on) → enable `login_if_ssid_unknown` in `%APPDATA%\HostelAutoLogin\settings.json`. |
| Android SSID shows "unreadable" | Grant location permission and turn Location on, or enable "Log in even if the SSID can't be read". |
| Android stops logging in after a while | Battery exemption + MIUI Autostart + lock in Recents (Settings tab). |
| Logs show `***` | Intentional masking of passwords, cookies, tokens and long random strings. |
