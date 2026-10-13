import Foundation
@testable import HostelCore

/// In-process URL loader so tests can simulate a captive portal without a network.
final class MockProtocol: URLProtocol {
    typealias Handler = (URLRequest, String) -> (Int, [String: String], String)
    static var handler: Handler = { _, _ in (404, [:], "") }
    static var seen: [(host: String, path: String, method: String, body: String)] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var body = ""
        if let d = request.httpBody { body = String(decoding: d, as: UTF8.self) }
        else if let s = request.httpBodyStream {
            s.open(); defer { s.close() }
            var buf = [UInt8](repeating: 0, count: 4096); var data = Data()
            while s.hasBytesAvailable { let n = s.read(&buf, maxLength: buf.count); if n <= 0 { break }; data.append(buf, count: n) }
            body = String(decoding: data, as: UTF8.self)
        }
        let url = request.url!
        Self.seen.append((url.host ?? "", url.path, request.httpMethod ?? "GET", body))
        let (code, headers, text) = Self.handler(request, body)
        let resp = HTTPURLResponse(url: url, statusCode: code, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(text.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}

    static func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [MockProtocol.self]
        return URLSession(configuration: c)
    }
    static func reset() { seen = []; handler = { _, _ in (404, [:], "") } }
}

func loginPage(_ n: Int, extra: String = "") -> String {
    """
    <html><body>\(extra)
    <form method="post" action="/portal/auth">
      <input type="hidden" name="magic" value="magic\(n)">
      <input type="text" name="reg_no"><input type="password" name="pwd">
      <input type="checkbox" name="accept_terms" value="yes">
      <input type="submit" name="go" value="Login">
    </form></body></html>
    """
}

func formDecode(_ s: String) -> [String: String] {
    var out: [String: String] = [:]
    for pair in s.split(separator: "&") {
        let kv = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
        if kv.count == 2 { out[String(kv[0]).removingPercentEncoding ?? ""] = String(kv[1]).removingPercentEncoding ?? "" }
    }
    return out
}

/// A fake captive portal at http://portal.test.
final class PortalSim {
    var loggedIn = false
    var loads = 0
    var afterLogin: String?
    var page: ((Int) -> String)?
    var posts: [String] = []

    func install() {
        MockProtocol.handler = { [self] req, body in
            let url = req.url!
            if url.host == "evil.test" { return (200, [:], "evil") }
            switch url.path {
            case "/connecttest.txt":
                return loggedIn ? (200, [:], "Microsoft Connect Test") : (302, ["Location": "/portal/login?x=1"], "")
            case "/portal/login":
                loads += 1
                return (200, [:], page?(loads) ?? loginPage(loads))
            case "/portal/auth":
                posts.append(body)
                let f = formDecode(body)
                if f["pwd"] == "pw123" && f["reg_no"] == "stud1" {
                    loggedIn = true
                    if let l = afterLogin { return (302, ["Location": l], "") }
                    return (200, [:], "Welcome")
                }
                return (200, [:], loginPage(99, extra: "<p>Invalid credentials</p>"))
            default: return (404, [:], "")
            }
        }
    }
}
