package com.hostelautologin.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.*

class AutoDiscoveryTest {
    private lateinit var portal: MockWebServer
    private lateinit var evil: MockWebServer
    @Volatile private var loggedIn = false
    @Volatile private var pageHtml: (Int) -> String = { loginPage(it) }
    @Volatile private var afterLoginLocation: String? = null
    private var loads = 0
    private val posts = mutableListOf<String>()

    private fun loginPage(n: Int, extra: String = "") = """<html><body>$extra
        <form method="post" action="/portal/auth">
          <input type="hidden" name="magic" value="magic$n">
          <input type="text" name="reg_no">
          <input type="password" name="pwd">
          <input type="checkbox" name="accept_terms" value="yes">
          <input type="submit" name="go" value="Login">
        </form></body></html>"""

    @BeforeTest fun up() {
        portal = MockWebServer(); evil = MockWebServer()
        evil.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody("evil") }
        portal.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/connecttest.txt" ->
                    if (loggedIn) MockResponse().setBody("Microsoft Connect Test")
                    else MockResponse().setResponseCode(302).setHeader("Location", "/portal/login?x=1")
                request.path!!.startsWith("/portal/login") -> MockResponse().setBody(pageHtml(++loads))
                request.path == "/portal/auth" -> {
                    val body = request.body.readUtf8(); synchronized(posts) { posts += body }
                    if ("pwd=pw123" in body && "reg_no=stud1" in body) {
                        loggedIn = true
                        afterLoginLocation?.let { MockResponse().setResponseCode(302).setHeader("Location", it) } ?: MockResponse().setBody("Welcome")
                    } else MockResponse().setBody(loginPage(99, "<p>Invalid credentials</p>"))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        portal.start(); evil.start()
    }

    @AfterTest fun down() { portal.shutdown(); evil.shutdown() }

    private fun host() = "${portal.hostName}:${portal.port}"
    private fun probe() = portal.url("/connecttest.txt").toString()
    private fun disc() = PortalDiscovery(OkHttpClient(), settleMillis = 0, probeUrl = probe())
    private fun cfg(h: String = host()) = PortalConfig.parse(PortalConfig.autoJson(listOf(Origin(h, "http")), "reg_no", "pwd"))

    @Test fun detectFindsFormAndWarns() {
        val r = disc().detect()
        val p = (r as DetectResult.Found).portal
        assertEquals(host(), p.host); assertEquals("reg_no", p.form.usernameField); assertEquals("pwd", p.form.passwordField)
        assertTrue(p.form.warnings.any { "terms" in it }); assertTrue(p.form.warnings.any { "plain HTTP" in it })
        assertTrue("magic" in p.form.fields.map { it.name })
    }

    @Test fun autoLoginSendsLiveHiddenFieldsAndSucceeds() {
        val res = LoginEngine(cfg(), OkHttpClient()).let { PortalDiscovery(OkHttpClient(), 0, probe()).loginAuto(cfg(), "stud1", "pw123") }
        assertEquals(Outcome.SUCCESS, res.outcome)
        val body = posts.single()
        assertTrue("magic=magic1" in body && "accept_terms=yes" in body && "go=Login" in body && "reg_no=stud1" in body && "pwd=pw123" in body, body)
    }

    @Test fun wrongPasswordIsBadCredentialsNotRetried() {
        val res = disc().loginAuto(cfg(), "stud1", "nope")
        assertEquals(Outcome.BAD_CREDENTIALS, res.outcome)
        assertEquals(1, posts.size)
    }

    @Test fun alreadyOnline() { loggedIn = true; assertEquals(Outcome.SUCCESS, disc().loginAuto(cfg(), "u", "p").outcome); assertTrue(posts.isEmpty()) }

    @Test fun notDetectedYetIsConfigError() {
        val c = PortalConfig.parse("""{"schema_version":1,"mode":"auto"}""")
        assertEquals(Outcome.CONFIG_ERROR, disc().loginAuto(c, "u", "p").outcome)
        assertEquals(0, portal.requestCount)
    }

    @Test fun postLoginRedirectToForeignHostIsNotFollowed() {
        afterLoginLocation = evil.url("/landing").toString()
        assertEquals(Outcome.SUCCESS, disc().loginAuto(cfg(), "stud1", "pw123").outcome)
        assertEquals(0, evil.requestCount)
    }

    @Test fun portalRedirectingToForeignHostIsRefusedWithoutSendingCredentials() {
        portal.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(302).setHeader("Location", evil.url("/login").toString())
        }
        val res = disc().loginAuto(cfg(), "stud1", "pw123")
        assertEquals(Outcome.CONFIG_ERROR, res.outcome); assertEquals(0, evil.requestCount)
    }

    @Test fun pinnedToDifferentHostThanDetected() {
        assertEquals(Outcome.CONFIG_ERROR, disc().loginAuto(cfg("10.9.9.9:81"), "stud1", "pw123").outcome)
        assertTrue(posts.isEmpty())
    }

    @Test fun captchaFormIsBlocked() {
        pageHtml = { """<form action="/portal/auth" method="post"><input name="u"><input type="password" name="p"><input name="captcha"></form>""" }
        assertTrue((disc().detect() as DetectResult.Problem).message.contains("CAPTCHA"))
        assertEquals(Outcome.CONFIG_ERROR, disc().loginAuto(cfg(), "u", "p").outcome)
    }

    @Test fun pageWithoutFormIsExplained() {
        pageHtml = { "<html><script>render()</script></html>" }
        assertTrue((disc().detect() as DetectResult.Problem).message.contains("browser-based"))
    }

    @Test fun jsAndMetaRedirectsAreFollowed() {
        portal.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/connecttest.txt" -> MockResponse().setBody("<script>window.location.href='/hop'</script>")
                "/hop" -> MockResponse().setBody("""<meta http-equiv="refresh" content="0; url=/portal/login">""")
                else -> MockResponse().setBody(loginPage(1))
            }
        }
        assertTrue(disc().detect() is DetectResult.Found)
    }

    @Test fun credentialsNeverLogged() {
        val lines = mutableListOf<String>(); AppLog.sink = { lines += it }
        disc().loginAuto(cfg(), "stud1", "pw123"); down(); up()
        disc().loginAuto(cfg(), "stud1", "wrongPw-zzz")
        assertTrue(lines.isNotEmpty() && lines.none { "pw123" in it || "wrongPw-zzz" in it })
    }

    @Test fun twoConfirmedPortalsBothAllowed() {
        val two = PortalConfig.parse(PortalConfig.autoJson(listOf(Origin("10.9.9.9:81", "http"), Origin(host(), "http")), "reg_no", "pwd"))
        assertEquals(Outcome.SUCCESS, disc().loginAuto(two, "stud1", "pw123").outcome)
        assertEquals(listOf(Origin("10.9.9.9:81", "http"), Origin(host(), "http")), two.origins)
    }
}
