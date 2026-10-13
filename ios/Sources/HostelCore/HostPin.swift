import Foundation

/// A user-confirmed portal address: host[:port] + scheme.
public struct Origin: Codable, Hashable, Sendable {
    public let host: String
    public let scheme: String
    public init(host: String, scheme: String) { self.host = host.lowercased(); self.scheme = scheme.lowercased() }
}

public struct HostPinError: Error, LocalizedError, Equatable {
    public let message: String
    public init(_ m: String) { message = m }
    public var errorDescription: String? { message }
}

/// Credentials may only go to exactly (scheme, host, port) of a user-confirmed origin.
public enum HostPin {
    static func defaultPort(_ scheme: String) -> Int { scheme == "https" ? 443 : 80 }

    static func split(_ hostPort: String) -> (String, Int?) {
        if let i = hostPort.lastIndex(of: ":"), let p = Int(hostPort[hostPort.index(after: i)...]) {
            return (String(hostPort[..<i]).lowercased(), p)
        }
        return (hostPort.lowercased(), nil)
    }

    /// "172.16.16.16" or "10.1.1.1:8090" (port omitted when it is the scheme's default).
    public static func hostPort(_ url: URL) -> String {
        let host = (url.host ?? "").lowercased()
        let scheme = (url.scheme ?? "http").lowercased()
        if let p = url.port, p != defaultPort(scheme) { return "\(host):\(p)" }
        return host
    }

    public static func check(_ url: URL, against o: Origin) throws {
        guard let c = URLComponents(url: url, resolvingAgainstBaseURL: true) else { throw HostPinError("invalid URL") }
        if c.user != nil || c.password != nil { throw HostPinError("URL with userinfo refused") }
        guard (c.scheme ?? "").lowercased() == o.scheme else { throw HostPinError("scheme '\(c.scheme ?? "")' not allowed (expected '\(o.scheme)')") }
        let (host, port) = split(o.host)
        guard (c.host ?? "").lowercased() == host else { throw HostPinError("host '\(c.host ?? "")' is not the pinned portal host") }
        if (c.port ?? defaultPort(o.scheme)) != (port ?? defaultPort(o.scheme)) { throw HostPinError("port does not match pinned portal host") }
    }

    public static func checkAny(_ url: URL, origins: [Origin]) throws {
        if origins.isEmpty { throw HostPinError("no portal host has been confirmed yet") }
        for o in origins { if (try? check(url, against: o)) != nil { return } }
        throw HostPinError("host '\(url.host ?? "")' is not one of the confirmed portal hosts")
    }

    public static func resolve(_ location: String, relativeTo current: URL) -> URL? {
        URL(string: location, relativeTo: current)?.absoluteURL
    }
}
