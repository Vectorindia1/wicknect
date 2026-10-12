package com.hostelautologin.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

data class Settings(
    val ssid: String = "",
    val username: String = "",
    val autoLogin: Boolean = true,
    val loginIfSsidUnknown: Boolean = false,
)

/** Platform hooks. Android implements these on top of ConnectivityManager. */
interface Platform {
    fun settings(): Settings
    fun config(): PortalConfig                       // throws ConfigException
    fun credentials(): Pair<String, String>?
    fun currentSsid(): String?
    /** OS verdict for the Wi-Fi network (NET_CAPABILITY_VALIDATED && !CAPTIVE_PORTAL); null = unknown / no Wi-Fi. */
    fun osValidated(): Boolean?
    fun probe(): Boolean                             // HTTP probe over the Wi-Fi network
    fun login(cfg: PortalConfig, user: String, pw: String): LoginResult
    fun keepalive(cfg: PortalConfig): Boolean
    fun notifyFailure(message: String)
    fun onStateChanged(state: State, message: String) {}
}

/** Port of windows/hostel_autologin/monitor.py. All time-related behaviour is injectable for tests. */
class Controller(
    private val p: Platform,
    private val sleep: suspend (Long) -> Unit = { delay(it * 1000) },
    private val clockSeconds: () -> Long = { System.nanoTime() / 1_000_000_000 },
    private val verifyDelaySeconds: Long = 2,
) {
    private val lock = Mutex()
    @Volatile var paused = false
    @Volatile var message = ""
        private set
    @Volatile var lastLoginMillis: Long? = null
        private set
    @Volatile var internetOk: Boolean? = null
        private set
    private var lastCycle = Long.MIN_VALUE / 2
    private var lastKeepalive = clockSeconds()
    val sm = StateMachine { _, new -> p.onStateChanged(new, message) }
    val state get() = sm.state

    private fun onTarget(st: Settings, ssid: String?) = ssid == st.ssid || (ssid == null && st.loginIfSsidUnknown)

    suspend fun tick() {
        val st = p.settings()
        if (!onTarget(st, p.currentSsid())) { internetOk = null; sm.handle(Event.SSID_LOST); return }
        sm.handle(Event.SSID_MATCH)
        // "Do nothing if the network is validated" - trust the OS verdict, otherwise confirm with our own probe.
        val ok = if (p.osValidated() == true) true else p.probe()
        internetOk = ok
        if (ok) { sm.handle(Event.PROBE_OK); maybeKeepalive(); return }
        if (sm.state == State.FAILURE) return                    // wait for user action; no endless attempts
        sm.handle(Event.PROBE_FAIL)
        if (sm.state == State.LOGIN_NEEDED && st.autoLogin && !paused && clockSeconds() - lastCycle >= MIN_CYCLE_GAP) runLoginCycle(false)
    }

    private fun maybeKeepalive() {
        val cfg = try { p.config() } catch (e: Exception) { return }
        if (cfg.keepalivePath == null || sm.state != State.VALIDATED) return
        if (clockSeconds() - lastKeepalive >= maxOf(30, cfg.keepaliveIntervalSeconds)) {
            lastKeepalive = clockSeconds(); p.keepalive(cfg)
        }
    }

    suspend fun loginNow() {
        val st = p.settings()
        if (!onTarget(st, p.currentSsid())) { p.notifyFailure("Not connected to '${st.ssid}'."); return }
        sm.handle(Event.SSID_MATCH); sm.handle(Event.USER_LOGIN)
        runLoginCycle(true)
    }

    fun settingsChanged() { sm.handle(Event.RESET) }

    private fun fail(msg: String, ev: Event): Boolean {
        message = msg; sm.handle(ev); AppLog.e("login failed: $msg"); p.notifyFailure(msg); return false
    }

    suspend fun runLoginCycle(userInitiated: Boolean): Boolean {
        if (!lock.tryLock()) return false
        try {
            lastCycle = clockSeconds()
            return cycle(userInitiated)
        } finally { lock.unlock() }
    }

    private suspend fun cycle(userInitiated: Boolean): Boolean {
        val cfg = try { p.config() } catch (e: Exception) {
            sm.handle(Event.LOGIN_START); return fail(e.message ?: "config error", Event.LOGIN_FATAL)
        }
        val (user, pw) = p.credentials() ?: run {
            sm.handle(Event.LOGIN_START); return fail("No stored credentials. Open Setup to add them.", Event.LOGIN_FATAL)
        }
        Masker.global.register(pw)
        var last = ""
        var acceptedButOffline = 0
        for (attempt in 0..BACKOFF_SECONDS.size) {
            if (attempt > 0) {
                sleep(BACKOFF_SECONDS[attempt - 1])
                if (paused && !userInitiated) { sm.handle(Event.ABORT); return false }
            }
            sm.handle(Event.LOGIN_START)
            val res = p.login(cfg, user, pw)
            last = res.detail
            message = "attempt ${attempt + 1}: ${res.detail}"
            AppLog.i("login attempt ${attempt + 1} -> ${res.outcome}")
            if (res.fatal) return fail(res.detail, Event.LOGIN_FATAL)
            if (res.outcome == Outcome.SUCCESS) {
                sm.handle(Event.LOGIN_OK)
                if (verifyDelaySeconds > 0) sleep(verifyDelaySeconds)
                if (p.probe()) {
                    internetOk = true; lastLoginMillis = System.currentTimeMillis(); message = "logged in"
                    sm.handle(Event.VERIFY_OK); return true
                }
                last = "login accepted but internet still not reachable"
                sm.handle(Event.VERIFY_FAIL)
                // Two "accepted but still offline" results in a row: stop, rather than risk locking the account.
                if (++acceptedButOffline >= 2) {
                    sm.handle(Event.LOGIN_START)
                    return fail("The portal accepted the login twice but the internet is still unreachable. Check your username/password and portal setup.", Event.LOGIN_FATAL)
                }
            }
        }
        if (sm.state != State.AUTHENTICATING) sm.handle(Event.LOGIN_START)   // reach FAILURE from LOGIN_NEEDED
        return fail("gave up after ${BACKOFF_SECONDS.size + 1} attempts: $last", Event.LOGIN_EXHAUSTED)
    }

    companion object { const val MIN_CYCLE_GAP = 120L }
}
