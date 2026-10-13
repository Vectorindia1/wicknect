import Foundation

/// Secret masking for logs and diagnostics. Nothing sensitive may reach a log line unmasked.
public final class Masker: @unchecked Sendable {
    public static let shared = Masker()
    private var secrets = Set<String>()
    private let lock = NSLock()
    public init() {}

    public func register(_ secret: String?) {
        guard let secret, secret.count >= 3 else { return }
        lock.lock(); secrets.insert(secret); lock.unlock()
    }

    private static let key = "(?:pass(?:word|wd)?|pwd|secret|token|cookie|session(?:id)?|auth(?:orization)?|csrf|xsrf|sid|nonce|key)"
    private static let kv = try! NSRegularExpression(pattern: #"(\b[\w-]*"# + key + #"[\w-]*"?\s*[=:]\s*"?)([^&\s"',;]+)"#, options: .caseInsensitive)
    private static let header = try! NSRegularExpression(pattern: #"\b(cookie|set-cookie|authorization|proxy-authorization)\b(\s*[:=]\s*)([^\r\n]+)"#, options: .caseInsensitive)
    private static let long = try! NSRegularExpression(pattern: #"(?<![A-Za-z0-9+/=_-])[A-Za-z0-9+/=_-]{24,}(?![A-Za-z0-9+/=_-])"#)

    private static func apply(_ re: NSRegularExpression, _ s: String, _ template: String) -> String {
        re.stringByReplacingMatches(in: s, options: [], range: NSRange(s.startIndex..., in: s), withTemplate: template)
    }

    public func mask(_ text: String) -> String {
        var s = text
        lock.lock(); let all = secrets.sorted { $0.count > $1.count }; lock.unlock()
        for x in all { s = s.replacingOccurrences(of: x, with: "***") }
        s = Masker.apply(Masker.header, s, "$1$2***")
        s = Masker.apply(Masker.kv, s, "$1***")
        s = Masker.apply(Masker.long, s, "***")
        return s
    }
}

/// Every line is masked before it reaches any sink. Never log request bodies.
public enum AppLog {
    public static var sink: (String) -> Void = { _ in }
    public static func log(_ level: String, _ msg: String) {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        sink("\(f.string(from: Date())) \(level) \(Masker.shared.mask(msg))")
    }
    public static func i(_ m: String) { log("I", m) }
    public static func w(_ m: String) { log("W", m) }
    public static func e(_ m: String) { log("E", m) }
}
