package com.hostelautologin.core

import kotlin.test.*

class CoreTest {
    private fun run(vararg ev: Event): State { val sm = StateMachine(); ev.forEach { sm.handle(it) }; return sm.state }

    @Test fun happyPath() = assertEquals(State.VALIDATED,
        run(Event.SSID_MATCH, Event.PROBE_FAIL, Event.LOGIN_START, Event.LOGIN_OK, Event.VERIFY_OK))

    @Test fun sessionExpiry() = assertEquals(State.LOGIN_NEEDED, run(Event.SSID_MATCH, Event.PROBE_OK, Event.PROBE_FAIL))

    @Test fun failureIsSticky() {
        val base = arrayOf(Event.SSID_MATCH, Event.PROBE_FAIL, Event.LOGIN_START, Event.LOGIN_FATAL)
        assertEquals(State.FAILURE, run(*base))
        assertEquals(State.FAILURE, run(*base, Event.PROBE_FAIL))
        assertEquals(State.FAILURE, run(*base, Event.LOGIN_START))
        assertEquals(State.LOGIN_NEEDED, run(*base, Event.USER_LOGIN))
        assertEquals(State.CONNECTED, run(*base, Event.SSID_LOST, Event.SSID_MATCH))
    }

    @Test fun illegalIgnored() {
        val sm = StateMachine()
        assertFalse(sm.handle(Event.LOGIN_OK)); assertEquals(State.OFFLINE, sm.state)
    }

    @Test fun backoff() = assertEquals(listOf(2L, 5L, 15L, 30L), BACKOFF_SECONDS)

    @Test fun masking() {
        val m = Masker(); m.register("hunter2pw")
        val out = m.mask("pw=hunter2pw\ncookie: JSESSIONID=abc123\ntoken=xyz\nAuthorization: Bearer abcdef\nPOST /a?password=foo&x=1")
        for (bad in listOf("hunter2pw", "abc123", "xyz", "Bearer abcdef", "foo")) assertFalse(bad in out, bad)
        assertTrue("x=1" in out)
        assertFalse("A".repeat(30) in m.mask("id " + "A".repeat(30)))
    }

    @Test fun appLogMasks() {
        Masker.global.register("zzSecretPw")
        val lines = mutableListOf<String>(); AppLog.sink = { lines += it }
        AppLog.i("trying zzSecretPw now")
        assertFalse(lines.single().contains("zzSecretPw"))
    }

    @Test fun pinAccepts() {
        HostPin.check("http://172.16.16.16/login", "172.16.16.16", "http")
        HostPin.check("http://HOST.lan:8080/x", "host.lan:8080", "http")
    }

    @Test fun pinRejects() {
        for (u in listOf("http://evil.com/login", "http://172.16.16.16.evil.com/", "http://172.16.16.16@evil.com/",
            "http://evil.com@172.16.16.16/", "https://172.16.16.16/", "http://172.16.16.16:8080/", "http://172.16.16.17/",
            "//evil.com/x", "ftp://172.16.16.16/")) {
            assertFailsWith<HostPinException>(u) { HostPin.check(u, "172.16.16.16", "http") }
        }
        val cur = HostPin.check("http://172.16.16.16/login", "172.16.16.16", "http")
        assertEquals("/done", HostPin.resolveRedirect(cur, "/done", "172.16.16.16", "http").encodedPath)
        assertFailsWith<HostPinException> { HostPin.resolveRedirect(cur, "http://evil.com/", "172.16.16.16", "http") }
        assertFailsWith<HostPinException> { HostPin.resolveRedirect(cur, "//evil.com/", "172.16.16.16", "http") }
    }

    @Test fun placeholderConfig() {
        val text = """{"schema_version":1,"portal_host":"172.16.16.16","login":{"path":"/REPLACE_ME","fields":{"p":"{{password}}"}}}"""
        assertTrue(PortalConfig.parse(text).unconfiguredReason!!.contains("REPLACE_ME"))
        assertFailsWith<ConfigException> { PortalConfig.parse("""{"schema_version":1,"portal_host":"http://x/","login":{"path":"/a"}}""") }
        assertFailsWith<ConfigException> { PortalConfig.parse("not json") }
        val c = PortalConfig.parse("""{"schema_version":1,"portal_host":"x","login":{"path":"/a","headers":{"Cookie":"a=b","X":"1"}}}""")
        assertEquals(mapOf("X" to "1"), c.headers)
    }
}
