package com.hostelautologin.core

import kotlin.test.*

class FriendlyTest {
    private fun say(o: Outcome, d: String) = Friendly.login(LoginResult(o, d))

    @Test fun successAndErrorsAreInPlainEnglish() {
        assertTrue("connected" in say(Outcome.SUCCESS, "login submitted"))
        assertTrue("already have internet" in say(Outcome.SUCCESS, "already online"))
        assertTrue("username or password" in say(Outcome.BAD_CREDENTIALS, "the portal showed the login form again"))
        assertTrue("CAPTCHA" in say(Outcome.CONFIG_ERROR, "This portal asks for a CAPTCHA/OTP code"))
        assertTrue("tap Connect" in say(Outcome.CONFIG_ERROR, "Not set up yet: no portal set up yet."))
        assertTrue("moved" in say(Outcome.CONFIG_ERROR, "refused: host 'x' is not one of the confirmed portal hosts"))
        assertTrue("connected to your Wi-Fi" in say(Outcome.TRANSIENT, "network error: SocketTimeoutException"))
    }

    @Test fun noJargonLeaksToUsers() {
        val msgs = listOf(
            say(Outcome.CONFIG_ERROR, "Not set up yet: no portal set up yet."), say(Outcome.BAD_CREDENTIALS, "x"),
            say(Outcome.TRANSIENT, "network error: IOException"), say(Outcome.CONFIG_ERROR, "refused: bad host"),
        )
        for (m in msgs) assertFalse("json" in m.lowercase() || "portal.json" in m.lowercase() || "origin" in m.lowercase(), m)
    }
}
