import Foundation
import HostelCore

/// One login cycle with the saved details. Used by the Home button, Shortcuts and background refresh.
enum Runner {
    static func runStored(quick: Bool) async -> LoginResult {
        let store = Store.shared
        store.installLogSink()
        guard let pw = store.password, !store.settings.username.isEmpty else {
            return LoginResult(.configError, "Not set up yet: no username and password saved.")
        }
        let cfg = store.config
        let user = store.settings.username
        let d = PortalDiscovery()
        let r = await Retry.run(
            delays: quick ? Retry.quickDelays : Retry.standardDelays,
            attempt: { await d.login(config: cfg, username: user, password: pw) },
            verify: { try? await Task.sleep(nanoseconds: 2_000_000_000); return await d.isOnline() })
        if r.outcome == .success, !r.detail.lowercased().contains("already online") { store.lastLogin = Date() }
        store.lastMessage = Friendly.login(r)
        AppLog.i("run finished: \(r.outcome.rawValue)")
        return r
    }
}
