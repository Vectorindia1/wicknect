package com.hostelautologin.core

import kotlinx.coroutines.runBlocking
import kotlin.test.*

private class Fake(results: List<LoginResult>, probes: List<Boolean> = emptyList(), var ssid: String? = "University_Hostel") : Platform {
    val results = results.toMutableList(); val probes = probes.toMutableList()
    var defaultProbe = false; var logins = 0; val notes = mutableListOf<String>(); var ka = 0; var os: Boolean? = null
    var creds: Pair<String, String>? = "u" to "p"
    override fun settings() = Settings(username = "u")
    override fun config() = PortalConfig.parse("""{"schema_version":1,"portal_host":"172.16.16.16","login":{"path":"/login"},"keepalive":{"path":"/ka","interval_seconds":30}}""")
    override fun credentials() = creds
    override fun currentSsid() = ssid
    override fun osValidated() = os
    override fun probe() = if (probes.isNotEmpty()) probes.removeAt(0) else defaultProbe
    override fun login(cfg: PortalConfig, user: String, pw: String): LoginResult { logins++; return results.removeAt(0) }
    override fun keepalive(cfg: PortalConfig): Boolean { ka++; return true }
    override fun notifyFailure(message: String) { notes += message }
}

class ControllerTest {
    private val T = LoginResult(Outcome.TRANSIENT, "timeout")
    private val S = LoginResult(Outcome.SUCCESS, "ok")
    private var now = 0L
    private val waits = mutableListOf<Long>()
    private fun ctl(f: Fake) = Controller(f, sleep = { waits += it }, clockSeconds = { now }, verifyDelaySeconds = 0)

    @Test fun retriesFollowBackoffThenFailure() = runBlocking<Unit> {
        val f = Fake(List(10) { T }); val c = ctl(f)
        c.tick()
        assertEquals(State.FAILURE, c.state); assertEquals(5, f.logins); assertEquals(BACKOFF_SECONDS, waits); assertEquals(1, f.notes.size)
    }

    @Test fun noMoreAttemptsAfterFailure() = runBlocking<Unit> {
        val f = Fake(List(20) { T }); val c = ctl(f)
        c.tick(); val n = f.logins
        repeat(5) { now += 1000; c.tick() }
        assertEquals(n, f.logins); assertEquals(State.FAILURE, c.state)
    }

    @Test fun badCredentialsSingleAttempt() = runBlocking<Unit> {
        val f = Fake(List(5) { LoginResult(Outcome.BAD_CREDENTIALS, "rejected") }); val c = ctl(f)
        c.tick()
        assertEquals(1, f.logins); assertTrue(waits.isEmpty()); assertEquals(State.FAILURE, c.state)
    }

    @Test fun successThenVerified() = runBlocking<Unit> {
        val f = Fake(listOf(S), listOf(false, true)); val c = ctl(f)
        c.tick(); assertEquals(State.VALIDATED, c.state); assertNotNull(c.lastLoginMillis)
    }

    @Test fun transientThenSuccess() = runBlocking<Unit> {
        val f = Fake(listOf(T, S), listOf(false, true)); val c = ctl(f)
        c.tick(); assertEquals(State.VALIDATED, c.state); assertEquals(listOf(2L), waits)
    }

    @Test fun verifyNeverOkIsBounded() = runBlocking<Unit> {
        val f = Fake(List(10) { S }); val c = ctl(f)
        c.tick(); assertEquals(State.FAILURE, c.state); assertEquals(5, f.logins)
    }

    @Test fun otherSsidDoesNothing() = runBlocking<Unit> {
        val f = Fake(listOf(S), ssid = "Other"); val c = ctl(f)
        c.tick(); assertEquals(State.OFFLINE, c.state); assertEquals(0, f.logins)
    }

    @Test fun osValidatedSkipsProbeAndLogin() = runBlocking<Unit> {
        val f = Fake(listOf(S)); f.os = true; val c = ctl(f)
        c.tick(); assertEquals(State.VALIDATED, c.state); assertEquals(0, f.logins)
    }

    @Test fun keepaliveWhenValidated() = runBlocking<Unit> {
        val f = Fake(emptyList()); f.defaultProbe = true; val c = ctl(f)
        c.tick(); now += 60; c.tick(); assertEquals(1, f.ka)
    }

    @Test fun pausedBlocksAutoButNotLoginNow() = runBlocking<Unit> {
        val f = Fake(listOf(S), listOf(false, true)); val c = ctl(f); c.paused = true
        c.tick(); assertEquals(0, f.logins); assertEquals(State.LOGIN_NEEDED, c.state)
        c.loginNow(); assertEquals(1, f.logins); assertEquals(State.VALIDATED, c.state)
    }

    @Test fun rateGuard() = runBlocking<Unit> {
        val f = Fake(List(3) { S }, listOf(false, true)); val c = ctl(f)
        c.tick(); assertEquals(1, f.logins)
        now += 10; c.tick(); assertEquals(1, f.logins)
        now += 200; f.probes += listOf(false, true); c.tick(); assertEquals(2, f.logins)
    }

    @Test fun loginNowRecoversFromFailure() = runBlocking<Unit> {
        val f = Fake(listOf(LoginResult(Outcome.BAD_CREDENTIALS, "x"), S), listOf(false, true)); val c = ctl(f)
        c.tick(); assertEquals(State.FAILURE, c.state)
        c.loginNow(); assertEquals(State.VALIDATED, c.state)
    }

    @Test fun missingCredentialsFails() = runBlocking<Unit> {
        val f = Fake(listOf(S)); f.creds = null; val c = ctl(f)
        c.tick(); assertEquals(0, f.logins); assertEquals(State.FAILURE, c.state)
    }
}
