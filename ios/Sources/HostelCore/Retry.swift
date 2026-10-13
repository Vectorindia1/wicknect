import Foundation

/// Bounded retries (SPEC 1.5): first try, then waits of 2s, 5s, 15s, 30s. Wrong credentials never cause retries.
public enum Retry {
    public static let standardDelays: [TimeInterval] = [0, 2, 5, 15, 30]
    /// Shorter schedule for Shortcuts/background runs where iOS only gives us a few seconds.
    public static let quickDelays: [TimeInterval] = [0, 2, 5]

    public static func run(delays: [TimeInterval] = standardDelays,
                           sleep: (TimeInterval) async -> Void = { try? await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) },
                           attempt: () async -> LoginResult,
                           verify: () async -> Bool) async -> LoginResult {
        var last = ""
        var acceptedButOffline = 0
        for (i, d) in delays.enumerated() {
            if d > 0 { await sleep(d) }
            let r = await attempt()
            last = r.detail
            AppLog.i("login attempt \(i + 1) -> \(r.outcome.rawValue)")
            if r.isFatal { return r }
            if r.outcome == .success {
                if r.detail.lowercased().contains("already online") { return r }
                if await verify() { return r }
                last = "login accepted but internet still not reachable"
                acceptedButOffline += 1
                if acceptedButOffline >= 2 {      // stop rather than risk locking the account
                    return LoginResult(.configError, "The portal accepted the login twice but the internet is still unreachable. Check your username and password.")
                }
            }
        }
        return LoginResult(.transient, "gave up after \(delays.count) attempts: \(last)")
    }
}
