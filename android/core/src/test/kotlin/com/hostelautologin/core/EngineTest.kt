package com.hostelautologin.core

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.*

class EngineTest {
    private lateinit var portal: MockWebServer
    private lateinit var evil: MockWebServer
    @Volatile private var mode = "ok"

    @BeforeTest fun up() {
        portal = MockWebServer(); evil = MockWebServer()
        evil.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody("evil") }
        portal.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.clone().readUtf8()
                return when {
                    request.path!!.startsWith("/login") -> when {
                        "password=pw123" !in body || mode == "badcreds" -> MockResponse().setBody("Invalid username or password")
                        mode == "500" -> MockResponse().setResponseCode(500)
                        mode == "redirect_foreign" -> MockResponse().setResponseCode(302).setHeader("Location", evil.url("/steal").toString())
                        mode == "redirect_ok" -> MockResponse().setResponseCode(302).setHeader("Location", "/done")
                        else -> MockResponse().setBody("Welcome, you are now connected")
                    }
                    request.path == "/done" -> MockResponse().setBody("logged in")
                    request.path == "/ka" -> MockResponse().setBody("alive")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        portal.start(); evil.start()
    }

    @AfterTest fun down() { portal.shutdown(); evil.shutdown() }

    private fun host(s: MockWebServer) = "${s.hostName}:${s.port}"

    private fun cfg(h: String = host(portal), login: String = """{"path":"/login","method":"POST","fields":{"username":"{{username}}","password":"{{password}}","mode":"191"}}""",
                    extra: String = "") = PortalConfig.parse("""{
        "schema_version":1,"portal_host":"$h","scheme":"http","login":$login,
        "success":{"status_in":[200],"body_contains":["connected","logged in"],"body_not_contains":["Invalid"]},
        "bad_credentials":{"body_contains":["Invalid username"]},
        "keepalive":{"path":"/ka","interval_seconds":30} $extra }""")

    @Test fun success() {
        assertEquals(Outcome.SUCCESS, LoginEngine(cfg()).login("stud1", "pw123").outcome)
        val r = portal.takeRequest()
        assertEquals("POST", r.method)
        val b = r.body.readUtf8(); assertTrue("username=stud1" in b && "mode=191" in b)
    }

    @Test fun badCredentials() = assertEquals(Outcome.BAD_CREDENTIALS, LoginEngine(cfg()).login("stud1", "nope").outcome)

    @Test fun serverErrorTransient() { mode = "500"; assertEquals(Outcome.TRANSIENT, LoginEngine(cfg()).login("u", "pw123").outcome) }

    @Test fun connectionRefusedTransient() {
        assertEquals(Outcome.TRANSIENT, LoginEngine(cfg("127.0.0.1:1")).login("u", "pw123").outcome)
    }

    @Test fun sameHostRedirectFollowed() {
        mode = "redirect_ok"
        val c = PortalConfig.parse("""{"schema_version":1,"portal_host":"${host(portal)}","login":{"path":"/login","fields":{"password":"{{password}}"}},
            "success":{"status_in":[302],"body_contains":["logged in"]}}""")
        assertEquals(Outcome.SUCCESS, LoginEngine(c).login("u", "pw123").outcome)
    }

    @Test fun foreignRedirectRefused() {
        mode = "redirect_foreign"
        assertEquals(Outcome.CONFIG_ERROR, LoginEngine(cfg()).login("u", "pw123").outcome)
        assertEquals(0, evil.requestCount)
    }

    @Test fun absoluteForeignPathRefused() {
        val c = cfg("172.16.16.16", """{"path":"http://${host(evil)}/login","fields":{"p":"{{password}}"}}""")
        assertEquals(Outcome.CONFIG_ERROR, LoginEngine(c).login("u", "pw123").outcome)
        assertEquals(0, evil.requestCount)
    }

    @Test fun placeholderMakesNoRequest() {
        val c = cfg(login = """{"path":"/REPLACE_ME","fields":{"p":"{{password}}"}}""")
        assertEquals(Outcome.CONFIG_ERROR, LoginEngine(c).login("u", "pw123").outcome)
        assertEquals(0, portal.requestCount)
    }

    @Test fun unknownPlaceholderIsConfigError() {
        val c = cfg(login = """{"path":"/login","fields":{"x":"{{nope}}"}}""")
        assertEquals(Outcome.CONFIG_ERROR, LoginEngine(c).login("u", "pw123").outcome)
        assertEquals(0, portal.requestCount)
    }

    @Test fun keepalive() { assertTrue(LoginEngine(cfg()).keepalive()); assertEquals("/ka", portal.takeRequest().path) }

    @Test fun fallbackUsedOnTransient() {
        mode = "500"
        val c = cfg(extra = ""","fallback":{"type":"browser"}""")
        val r = LoginEngine(c, fallback = LoginFallback { _, _, _ -> LoginResult(Outcome.SUCCESS, "via webview") }).login("u", "pw123")
        assertEquals("via webview", r.detail)
    }

    @Test fun passwordNeverLogged() {
        val lines = mutableListOf<String>(); AppLog.sink = { lines += it }
        LoginEngine(cfg()).login("stud1", "pw123"); LoginEngine(cfg()).login("stud1", "wrongPw-xyz")
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.none { "pw123" in it || "wrongPw-xyz" in it })
    }
}
