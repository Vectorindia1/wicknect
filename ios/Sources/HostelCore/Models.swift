import Foundation

public let probeURL = URL(string: "http://www.msftconnecttest.com/connecttest.txt")!
public let probeBody = "Microsoft Connect Test"

public enum Outcome: String, Sendable { case success, badCredentials, configError, transient }

public struct LoginResult: Sendable, Equatable {
    public let outcome: Outcome
    public let detail: String
    public init(_ outcome: Outcome, _ detail: String = "") { self.outcome = outcome; self.detail = detail }
    public var isFatal: Bool { outcome == .badCredentials || outcome == .configError }
}

/// What the Connect screen saves after the user confirms the login page address.
public struct PortalConfig: Codable, Equatable, Sendable {
    public var origins: [Origin] = []
    public var usernameField: String?
    public var passwordField: String?
    public init(origins: [Origin] = [], usernameField: String? = nil, passwordField: String? = nil) {
        self.origins = origins; self.usernameField = usernameField; self.passwordField = passwordField
    }
    public var isConfigured: Bool { !origins.isEmpty }
}

public struct Settings: Codable, Equatable, Sendable {
    public var ssids: [String] = []        // for display; iOS decides networks via the Shortcuts automation
    public var username: String = ""
    public init(ssids: [String] = [], username: String = "") { self.ssids = ssids; self.username = username }
}

public struct FormField: Equatable, Sendable {
    public let name: String, type: String, value: String
    public init(_ name: String, _ type: String, _ value: String) { self.name = name; self.type = type; self.value = value }
}

public struct LoginForm: Sendable {
    public let action: URL
    public let method: String
    public let fields: [FormField]
    public let usernameField: String?
    public let passwordField: String
    public let warnings: [String]
    /// Non-nil when the form needs something that cannot be automated (CAPTCHA, OTP).
    public let blocked: String?
}

public struct DetectedPortal: Sendable {
    public let host: String
    public let scheme: String
    public let form: LoginForm
    public let pageURL: URL
    public var origin: Origin { Origin(host: host, scheme: scheme) }
}

public enum DetectResult: Sendable {
    case alreadyOnline
    case found(DetectedPortal)
    case problem(String)
}
