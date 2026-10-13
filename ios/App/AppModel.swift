import Foundation
import SwiftUI
import HostelCore

@MainActor
final class AppModel: ObservableObject {
    @Published var ssidsText: String
    @Published var username: String
    @Published var password = ""
    @Published var status = ""
    @Published var success: Bool?
    @Published var busy = false
    @Published var showConfirm = false
    @Published var found: DetectedPortal?
    @Published var online: Bool?
    @Published var lastLogin: Date?
    @Published var lastMessage = ""
    @Published var logs: [String] = []

    private let store = Store.shared

    init() {
        store.installLogSink()
        ssidsText = store.settings.ssids.joined(separator: "\n")
        username = store.settings.username
        refresh()
    }

    var hasSavedPassword: Bool { store.password != nil }
    var isConfigured: Bool { store.config.isConfigured && store.password != nil }
    var ssids: [String] { ssidsText.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty } }

    func refresh() {
        lastLogin = store.lastLogin; lastMessage = store.lastMessage; logs = store.logs
    }

    func checkOnline() async {
        online = await PortalDiscovery().isOnline()
    }

    func clearLogs() { store.clearLogs(); refresh() }

    func connect() {
        let user = username.trimmingCharacters(in: .whitespaces)
        if user.isEmpty { success = false; status = "Please type your username."; return }
        if password.isEmpty && !hasSavedPassword { success = false; status = "Please type your password."; return }
        store.settings = Settings(ssids: ssids, username: user)
        if !password.isEmpty { store.savePassword(password, for: user); password = "" }
        busy = true; success = nil; status = "Looking for your Wi-Fi's login page…"
        Task {
            if !store.config.isConfigured {
                let r = await PortalDiscovery().detect()
                switch r {
                case .found(let p): found = p; showConfirm = true; status = ""
                default: success = false; status = Friendly.detect(r)
                }
            } else {
                await test(allowRedetect: true)
            }
            busy = false
        }
    }

    func confirmPortal() {
        guard let p = found else { return }
        var cfg = store.config
        if !cfg.origins.contains(p.origin) { cfg.origins.append(p.origin) }
        cfg.usernameField = p.form.usernameField; cfg.passwordField = p.form.passwordField
        store.config = cfg
        found = nil; busy = true
        Task { await test(allowRedetect: false); busy = false }
    }

    func declinePortal() { found = nil; status = "Okay, nothing was saved." }

    private func test(allowRedetect: Bool) async {
        status = "Logging in…"
        guard let pw = store.password else { success = false; status = "Please type your password."; return }
        let r = await PortalDiscovery().login(config: store.config, username: store.settings.username, password: pw)
        if allowRedetect, r.outcome == .configError, r.detail.contains("refused") || r.detail.lowercased().contains("not set up") {
            let d = await PortalDiscovery().detect()
            if case .found(let p) = d { found = p; showConfirm = true; status = ""; return }
        }
        success = r.outcome == .success
        status = Friendly.login(LoginResult(r.outcome, Masker.shared.mask(r.detail)))
        if success == true, !r.detail.lowercased().contains("already online") { store.lastLogin = Date() }
        store.lastMessage = status
        refresh()
    }

    func loginNow() {
        busy = true; status = "Logging in…"; success = nil
        Task {
            let r = await Runner.runStored(quick: false)
            success = r.outcome == .success
            status = Friendly.login(r)
            refresh(); await checkOnline(); busy = false
        }
    }
}
