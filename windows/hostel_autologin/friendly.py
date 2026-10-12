"""Plain-English messages for ordinary users (mirrors android/core/.../Friendly.kt). Technical detail stays in the log."""
from __future__ import annotations

from .login_engine import LoginResult, Outcome


def login(r: LoginResult) -> str:
    d = r.detail.lower()
    if r.outcome is Outcome.SUCCESS:
        if "already online" in d:
            return "You already have internet - nothing to log in to right now."
        return "You're connected! The app will log you in again automatically whenever the Wi-Fi asks."
    if r.outcome is Outcome.BAD_CREDENTIALS:
        return "The Wi-Fi login page didn't accept your username or password. Please check them and try again."
    if "captcha" in d or "otp" in d:
        return "Your Wi-Fi asks for a code or CAPTCHA at login. This app can't type that for you."
    if "no portal set up" in d or "not set up" in d or "no portal host" in d:
        return "The app hasn't found your Wi-Fi's login page yet. Connect to your Wi-Fi and click Connect."
    if "refused" in d or "different host" in d:
        return "Your Wi-Fi's login page seems to have moved. Click Connect to set it up again."
    if "no login form" in d:
        return "The login page didn't show a normal username/password form, so the app can't fill it in."
    if any(k in d for k in ("network error", "couldn't reach", "unreachable", "timeout")):
        return "Couldn't reach the login page. Make sure your computer is connected to your Wi-Fi and try again."
    if "still not reachable" in d or "accepted the login twice" in d:
        return "The Wi-Fi accepted the login but internet still isn't working. Check your username and password."
    return f"Something went wrong: {r.detail}. You can send the log (tray menu > Open Logs) to whoever helps you."
