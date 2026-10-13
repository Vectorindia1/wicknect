import Foundation

struct Resp {
    let code: Int
    let location: String?
    let text: String
    let url: URL
    var isRedirect: Bool { [301, 302, 303, 307, 308].contains(code) && location != nil }
}

/// Stops URLSession from following redirects so every hop can be pin-checked by us.
final class NoRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

enum Landing {
    case online
    case page(URL, String)
    case failed(String)
}

/// Generic login without hand-made config: follows the captive-portal redirect from the standard connectivity probe,
/// reads the portal's own login form, fills in the user's credentials and submits it.
/// Credentials are only ever sent to an origin the user confirmed in `detect()`.
public final class PortalDiscovery: @unchecked Sendable {
    private let session: URLSession
    private let probe: URL
    private let settle: TimeInterval
    private let maxHops = 6

    /// Wi-Fi only (never cellular), no system proxy, no cache, own cookie jar.
    public static func makeSession() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.allowsCellularAccess = false
        c.connectionProxyDictionary = [:]
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.timeoutIntervalForRequest = 10
        c.timeoutIntervalForResource = 25
        c.httpCookieAcceptPolicy = .always
        return URLSession(configuration: c)
    }

    public init(session: URLSession = PortalDiscovery.makeSession(), probeURL: URL = HostelCore.probeURL, settle: TimeInterval = 1.5) {
        self.session = session; self.probe = probeURL; self.settle = settle
    }

    private func fetch(_ req: URLRequest) async throws -> Resp {
        let (data, response) = try await session.data(for: req, delegate: NoRedirect())
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        let text = String(decoding: data.prefix(200_000), as: UTF8.self)
        return Resp(code: http.statusCode, location: http.value(forHTTPHeaderField: "Location"), text: text, url: http.url ?? req.url!)
    }

    private func get(_ url: URL) async throws -> Resp {
        var r = URLRequest(url: url); r.httpMethod = "GET"
        return try await fetch(r)
    }

    public func isOnline() async -> Bool {
        var r = URLRequest(url: probe); r.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        guard let resp = try? await fetch(r) else { return false }
        return resp.code == 200 && resp.text.trimmingCharacters(in: .whitespacesAndNewlines) == probeBody
    }

    private static let metaRefresh = try! NSRegularExpression(pattern: #"<meta[^>]+http-equiv\s*=\s*["']?refresh[^>]+url\s*=\s*['"]?([^'">\s]+)"#, options: .caseInsensitive)
    private static let jsRedirect = try! NSRegularExpression(pattern: #"(?:window\.|document\.|top\.)?location(?:\.href)?\s*(?:=|\.replace\(|\.assign\()\s*["']([^"']+)["']"#, options: .caseInsensitive)

    private func firstGroup(_ re: NSRegularExpression, _ s: String) -> String? {
        guard let m = re.firstMatch(in: s, options: [], range: NSRange(s.startIndex..., in: s)), m.numberOfRanges > 1,
              let r = Range(m.range(at: 1), in: s) else { return nil }
        return String(s[r])
    }

    private func redirectTarget(_ r: Resp) -> String? {
        if r.isRedirect { return r.location }
        guard (200..<300).contains(r.code), !FormParser.hasPasswordField(r.text) else { return nil }
        return firstGroup(Self.metaRefresh, r.text) ?? firstGroup(Self.jsRedirect, r.text)
    }

    /// Follows the probe's redirect chain; `pin` is called for every redirect target before it is requested.
    func land(pin: (URL) throws -> Void) async throws -> Landing {
        var url = probe
        for hop in 0...maxHops {
            let r: Resp
            do { r = try await get(url) } catch { return .failed("network error: \((error as? URLError).map { "\($0.code.rawValue)" } ?? "unknown")") }
            if hop == 0, r.code == 200, r.text.trimmingCharacters(in: .whitespacesAndNewlines) == probeBody { return .online }
            guard let next = redirectTarget(r) else {
                return (200..<300).contains(r.code) ? .page(url, r.text) : .failed("portal page returned \(r.code)")
            }
            guard let resolved = HostPin.resolve(next, relativeTo: url) else { return .failed("bad redirect from portal") }
            try pin(resolved)
            url = resolved
        }
        return .failed("too many redirects")
    }

    /// User-initiated: find the portal and its login form so the user can confirm the address.
    public func detect() async -> DetectResult {
        do {
            switch try await land(pin: { _ in }) {
            case .online: return .alreadyOnline
            case .failed(let why): return .problem("Couldn't reach a login page: \(why). Are you connected to the Wi-Fi that shows a login page?")
            case .page(let url, let html):
                guard let form = FormParser.parse(html: html, base: url) else {
                    return .problem("Found a page at \(HostPin.hostPort(url)) but no username/password form. This portal probably needs a browser-based login, which isn't supported in automatic mode.")
                }
                if HostPin.hostPort(form.action) != HostPin.hostPort(url) {
                    return .problem("The login form submits to a different host (\(HostPin.hostPort(form.action))) than the page (\(HostPin.hostPort(url))). Refusing automatic setup.")
                }
                if let b = form.blocked { return .problem(b) }
                return .found(DetectedPortal(host: HostPin.hostPort(url), scheme: (url.scheme ?? "http").lowercased(), form: form, pageURL: url))
            }
        } catch { return .problem("Couldn't reach a login page: \(error.localizedDescription)") }
    }

    private static func enc(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }

    public func login(config: PortalConfig, username: String, password: String) async -> LoginResult {
        if !config.isConfigured { return LoginResult(.configError, "Not set up yet: no portal set up yet.") }
        Masker.shared.register(password)
        let pin: (URL) throws -> Void = { try HostPin.checkAny($0, origins: config.origins) }
        do {
            switch try await land(pin: pin) {
            case .online: return LoginResult(.success, "already online")
            case .failed(let why): return LoginResult(.transient, why)
            case .page(let url, let html): return try await submit(config, url, html, username, password, pin)
            }
        } catch let e as HostPinError {
            return LoginResult(.configError, "refused: \(e.message). If your portal changed, run Connect again.")
        } catch { return LoginResult(.transient, "network error: \(error.localizedDescription)") }
    }

    private func submit(_ cfg: PortalConfig, _ pageURL: URL, _ html: String, _ user: String, _ pw: String,
                        _ pin: (URL) throws -> Void) async throws -> LoginResult {
        try pin(pageURL)
        guard let form = FormParser.parse(html: html, base: pageURL, usernameHint: cfg.usernameField, passwordHint: cfg.passwordField) else {
            return LoginResult(.configError, "no login form found on the portal page")
        }
        if let b = form.blocked { return LoginResult(.configError, b) }
        try pin(form.action)
        if HostPin.hostPort(form.action) != HostPin.hostPort(pageURL) { throw HostPinError("login form submits to a different host than its page") }

        var pairs = form.fields.map { ($0.name, $0.value) }
        if let u = form.usernameField { pairs.append((u, user)) }
        pairs.append((form.passwordField, pw))
        let body = pairs.map { "\(Self.enc($0.0))=\(Self.enc($0.1))" }.joined(separator: "&")

        var req: URLRequest
        if form.method == "GET" {
            let sep = form.action.absoluteString.contains("?") ? "&" : "?"
            req = URLRequest(url: URL(string: form.action.absoluteString + sep + body)!)
            req.httpMethod = "GET"
        } else {
            req = URLRequest(url: form.action)
            req.httpMethod = "POST"
            req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            req.httpBody = Data(body.utf8)
        }
        req.setValue(pageURL.absoluteString, forHTTPHeaderField: "Referer")
        AppLog.i("auto login \(form.method) \(form.action.scheme ?? "http")://\(HostPin.hostPort(form.action))\(form.action.path)")

        var resp = try await fetch(req)
        var hops = 0
        while resp.isRedirect, hops < maxHops {
            hops += 1
            guard let next = HostPin.resolve(resp.location ?? "", relativeTo: resp.url) else { break }
            do { try pin(next) } catch { break }       // never follow off the portal host after sending credentials
            resp = try await get(next)
        }
        AppLog.i("auto login response status=\(resp.code)")
        if resp.code >= 500 { return LoginResult(.transient, "portal error \(resp.code)") }
        if FormParser.hasPasswordField(resp.text) {
            // The login form is back: wrong credentials, unless the internet is actually up already.
            if settle > 0 { try? await Task.sleep(nanoseconds: UInt64(settle * 1_000_000_000)) }
            if await isOnline() { return LoginResult(.success, "logged in") }
            return LoginResult(.badCredentials, "the portal showed the login form again - username or password probably wrong")
        }
        return LoginResult(.success, "login submitted")
    }
}
