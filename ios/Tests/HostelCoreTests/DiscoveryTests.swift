import XCTest
@testable import HostelCore

final class DiscoveryTests: XCTestCase {
    var sim = PortalSim()

    override func setUp() { MockProtocol.reset(); sim = PortalSim(); sim.install() }

    func disc() -> PortalDiscovery {
        PortalDiscovery(session: MockProtocol.session(), probeURL: URL(string: "http://portal.test/connecttest.txt")!, settle: 0)
    }
    func cfg(_ hosts: String...) -> PortalConfig {
        PortalConfig(origins: hosts.map { Origin(host: $0, scheme: "http") }, usernameField: "reg_no", passwordField: "pwd")
    }

    func testDetectFindsFormAndWarns() async {
        guard case .found(let p) = await disc().detect() else { return XCTFail("not found") }
        XCTAssertEqual(p.host, "portal.test")
        XCTAssertEqual(p.form.usernameField, "reg_no"); XCTAssertEqual(p.form.passwordField, "pwd")
        XCTAssertTrue(p.form.warnings.contains { $0.contains("terms") })
        XCTAssertTrue(p.form.warnings.contains { $0.contains("isn't encrypted") })
        XCTAssertTrue(p.form.fields.contains { $0.name == "magic" })
    }

    func testLoginSendsLiveHiddenFields() async {
        let r = await disc().login(config: cfg("portal.test"), username: "stud1", password: "pw123")
        XCTAssertEqual(r.outcome, .success)
        let f = formDecode(sim.posts[0])
        XCTAssertEqual(f["magic"], "magic1"); XCTAssertEqual(f["accept_terms"], "yes"); XCTAssertEqual(f["go"], "Login")
        XCTAssertEqual(f["reg_no"], "stud1"); XCTAssertEqual(f["pwd"], "pw123")
    }

    func testWrongPasswordIsBadCredentialsOnce() async {
        let r = await disc().login(config: cfg("portal.test"), username: "stud1", password: "nope")
        XCTAssertEqual(r.outcome, .badCredentials); XCTAssertEqual(sim.posts.count, 1)
    }

    func testAlreadyOnline() async {
        sim.loggedIn = true
        let r = await disc().login(config: cfg("portal.test"), username: "u", password: "p")
        XCTAssertEqual(r.outcome, .success); XCTAssertTrue(sim.posts.isEmpty)
    }

    func testNotConfiguredMakesNoRequest() async {
        let r = await disc().login(config: PortalConfig(), username: "u", password: "p")
        XCTAssertEqual(r.outcome, .configError); XCTAssertTrue(MockProtocol.seen.isEmpty)
    }

    func testPostLoginRedirectToForeignHostNotFollowed() async {
        sim.afterLogin = "http://evil.test/landing"
        let r = await disc().login(config: cfg("portal.test"), username: "stud1", password: "pw123")
        XCTAssertEqual(r.outcome, .success)
        XCTAssertFalse(MockProtocol.seen.contains { $0.host == "evil.test" })
    }

    func testPortalRedirectingToForeignHostRefused() async {
        MockProtocol.handler = { req, _ in
            req.url?.host == "evil.test" ? (200, [:], "evil") : (302, ["Location": "http://evil.test/login"], "")
        }
        let r = await disc().login(config: cfg("portal.test"), username: "stud1", password: "pw123")
        XCTAssertEqual(r.outcome, .configError)
        XCTAssertFalse(MockProtocol.seen.contains { $0.host == "evil.test" })
    }

    func testPinnedToDifferentHost() async {
        let r = await disc().login(config: cfg("10.9.9.9:81"), username: "stud1", password: "pw123")
        XCTAssertEqual(r.outcome, .configError); XCTAssertTrue(sim.posts.isEmpty)
    }

    func testTwoConfirmedPortalsBothAllowed() async {
        let r = await disc().login(config: cfg("10.9.9.9:81", "portal.test"), username: "stud1", password: "pw123")
        XCTAssertEqual(r.outcome, .success)
    }

    func testCaptchaBlockedAndNoFormExplained() async {
        sim.page = { _ in #"<form action="/portal/auth" method="post"><input name="u"><input type="password" name="p"><input name="captcha"></form>"# }
        guard case .problem(let m) = await disc().detect() else { return XCTFail() }
        XCTAssertTrue(m.contains("CAPTCHA"))
        sim.page = { _ in "<html><script>render()</script></html>" }
        guard case .problem(let m2) = await disc().detect() else { return XCTFail() }
        XCTAssertTrue(m2.contains("browser-based"))
    }

    func testJsAndMetaRedirectsFollowed() async {
        MockProtocol.handler = { req, _ in
            switch req.url!.path {
            case "/connecttest.txt": return (200, [:], "<script>window.location.href='/hop'</script>")
            case "/hop": return (200, [:], #"<meta http-equiv="refresh" content="0; url=/portal/login">"#)
            default: return (200, [:], loginPage(1))
            }
        }
        guard case .found = await disc().detect() else { return XCTFail("not found") }
    }

    func testPasswordNeverLogged() async {
        var lines: [String] = []; AppLog.sink = { lines.append($0) }
        _ = await disc().login(config: cfg("portal.test"), username: "stud1", password: "pw123")
        _ = await disc().login(config: cfg("portal.test"), username: "stud1", password: "wrongPw-zzz")
        XCTAssertFalse(lines.isEmpty)
        XCTAssertTrue(lines.allSatisfy { !$0.contains("pw123") && !$0.contains("wrongPw-zzz") })
    }
}
