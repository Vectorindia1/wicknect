package com.hostelautologin.core

/** Plain-English messages for ordinary users. Technical detail stays in the Logs tab. */
object Friendly {
    fun login(r: LoginResult): String {
        val d = r.detail.lowercase()
        return when {
            r.outcome == Outcome.SUCCESS && "already online" in d -> "You already have internet - nothing to log in to right now."
            r.outcome == Outcome.SUCCESS -> "You're connected! The app will log you in again automatically whenever the Wi-Fi asks."
            r.outcome == Outcome.BAD_CREDENTIALS -> "The Wi-Fi login page didn't accept your username or password. Please check them and try again."
            "captcha" in d || "otp" in d -> "Your Wi-Fi asks for a code or CAPTCHA at login. This app can't type that for you."
            "service is not running" in d -> "The app is still starting up. Please try again in a few seconds."
            "no portal set up" in d || "not set up" in d || "no portal host" in d ->
                "The app hasn't found your Wi-Fi's login page yet. Connect to your Wi-Fi and tap Connect."
            "refused" in d || "different host" in d -> "Your Wi-Fi's login page seems to have moved. Tap Connect to set it up again."
            "no login form" in d -> "The login page didn't show a normal username/password form, so the app can't fill it in."
            "not connected to wi-fi" in d || "network error" in d || "couldn't reach" in d || "unreachable" in d || "timeout" in d ->
                "Couldn't reach the login page. Make sure your phone is connected to your Wi-Fi and try again."
            "still not reachable" in d || "accepted the login twice" in d ->
                "The Wi-Fi accepted the login but internet still isn't working. Check your username and password."
            else -> "Something went wrong: ${r.detail}. You can share the Logs tab with whoever helps you."
        }
    }

    fun detect(r: DetectResult): String = when (r) {
        is DetectResult.AlreadyOnline -> "You already have internet, so there is no login page to find right now. " +
            "Turn Wi-Fi off and on (or wait until the Wi-Fi logs you out), then tap Connect again."
        is DetectResult.Problem -> r.message
        is DetectResult.Found -> "Found your Wi-Fi's login page."
    }
}
