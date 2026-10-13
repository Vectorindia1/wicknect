import XCTest
@testable import HostelCore

final class CoreTests: XCTestCase {
    func testMasking() {
        let m = Masker(); m.register("hunter2pw")
        let out = m.mask("pw=hunter2pw\ncookie: JSESSIONID=abc123\ntoken=xyz\nAuthorization: Bearer abcdef\nPOST /a?password=foo&x=1")
        for bad in ["hunter2pw", "abc123", "xyz", "Bearer abcdef", "foo"] { XCTAssertFalse(out.contains(bad), bad) }
        XCTAssertTrue(out.contains("x=1"))
        XCTAssertFalse(m.mask("id " + String(repeating: "A", count: 30)).contains(String(repeating: "A", count: 30)))
    }

    func testAppLogMasks() {
        Masker.shared.register("zzSecretPw")
        var lines: [String] = []
        AppLog.sink = { lines.append($0) }
        AppLog.i("trying zzSecretPw now")
        XCTAssertEqual(lines.count, 1)
        XCTAssertFalse(lines[0].contains("zzSecretPw"))
    }

    func testPinAccepts() throws {
        try HostPin.check(URL(string: "http://172.16.16.16/login")!, against: Origin(host: "172.16.16.16", scheme: "http"))
        try HostPin.check(URL(string: "http://HOST.lan:8080/x")!, against: Origin(host: "host.lan:8080", scheme: "http"))
    }

    func testPinRejects() {
        let o = Origin(host: "172.16.16.16", scheme: "http")
        for u in ["http://evil.com/login", "http://172.16.16.16.evil.com/", "http://172.16.16.16@evil.com/", "http://evil.com@172.16.16.16/",
                  "https://172.16.16.16/", "http://172.16.16.16:8080/", "http://172.16.16.17/", "ftp://172.16.16.16/"] {
            XCTAssertThrowsError(try HostPin.check(URL(string: u)!, against: o), u)
        }
        let cur = URL(string: "http://172.16.16.16/login")!
        XCTAssertEqual(HostPin.resolve("/done", relativeTo: cur)?.absoluteString, "http://172.16.16.16/done")
        XCTAssertThrowsError(try HostPin.check(HostPin.resolve("//evil.com/x", relativeTo: cur)!, against: o))
    }

    func testPinAnyOrigin() throws {
        let os = [Origin(host: "172.16.16.16", scheme: "http"), Origin(host: "10.1.1.1:8090", scheme: "http")]
        try HostPin.checkAny(URL(string: "http://10.1.1.1:8090/x")!, origins: os)
        XCTAssertThrowsError(try HostPin.checkAny(URL(string: "http://10.1.1.1/x")!, origins: os))
        XCTAssertThrowsError(try HostPin.checkAny(URL(string: "http://172.16.16.16/x")!, origins: []))
    }

    func testFriendlyIsPlainEnglish() {
        XCTAssertTrue(Friendly.login(LoginResult(.success, "login submitted")).contains("connected"))
        XCTAssertTrue(Friendly.login(LoginResult(.badCredentials, "x")).contains("username or password"))
        XCTAssertTrue(Friendly.login(LoginResult(.configError, "This portal asks for a CAPTCHA/OTP")).contains("CAPTCHA"))
        XCTAssertTrue(Friendly.login(LoginResult(.configError, "Not set up yet: no portal set up yet.")).contains("tap Connect"))
        XCTAssertTrue(Friendly.login(LoginResult(.configError, "refused: host x")).contains("moved"))
        XCTAssertTrue(Friendly.login(LoginResult(.transient, "network error: x")).contains("connected to your Wi-Fi"))
        for r in [LoginResult(.badCredentials, "x"), LoginResult(.transient, "network error"), LoginResult(.configError, "refused")] {
            XCTAssertFalse(Friendly.login(r).lowercased().contains("json"))
        }
    }

    func testFormParserBasics() {
        let html = #"<form method="GET" action="a"><select name="dom"><option value="x" selected>X<option value="y">Y</select><input name="user"><input type="password" name="pass"></form>"#
        let f = FormParser.parse(html: html, base: URL(string: "http://h/p/")!)
        XCTAssertEqual(f?.method, "GET")
        XCTAssertEqual(f?.action.absoluteString, "http://h/p/a")
        XCTAssertEqual(f?.usernameField, "user")
        XCTAssertTrue(f?.fields.contains(FormField("dom", "select", "x")) ?? false)
    }

    // MARK: retries
    func testRetryBackoffThenGiveUp() async {
        var waits: [TimeInterval] = []; var tries = 0
        let r = await Retry.run(sleep: { waits.append($0) }, attempt: { tries += 1; return LoginResult(.transient, "timeout") }, verify: { true })
        XCTAssertEqual(tries, 5); XCTAssertEqual(waits, [2, 5, 15, 30]); XCTAssertEqual(r.outcome, .transient)
    }

    func testBadCredentialsNoRetry() async {
        var tries = 0
        let r = await Retry.run(sleep: { _ in }, attempt: { tries += 1; return LoginResult(.badCredentials, "no") }, verify: { true })
        XCTAssertEqual(tries, 1); XCTAssertEqual(r.outcome, .badCredentials)
    }

    func testAcceptedButOfflineStopsAfterTwo() async {
        var tries = 0
        let r = await Retry.run(sleep: { _ in }, attempt: { tries += 1; return LoginResult(.success, "login submitted") }, verify: { false })
        XCTAssertEqual(tries, 2); XCTAssertEqual(r.outcome, .configError)
    }

    func testTransientThenSuccess() async {
        var n = 0
        let r = await Retry.run(sleep: { _ in }, attempt: { n += 1; return n == 1 ? LoginResult(.transient, "t") : LoginResult(.success, "ok") }, verify: { true })
        XCTAssertEqual(r.outcome, .success)
    }
}
