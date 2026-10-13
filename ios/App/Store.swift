import Foundation
import Security
import HostelCore

/// Password lives in the iOS Keychain (available after first unlock so Shortcuts runs work while the phone is locked).
enum Keychain {
    static let service = "com.hostelautologin.password"

    static func save(_ password: String, account: String) {
        delete(account: account)
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: account, kSecValueData as String: Data(password.utf8),
                                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock]
        SecItemAdd(q as CFDictionary, nil)
    }

    static func load(account: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: account, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func delete(account: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        SecItemDelete(q as CFDictionary)
    }
}

/// Settings, portal addresses and the masked log. No secrets except the Keychain password.
final class Store {
    static let shared = Store()
    private let d = UserDefaults.standard
    private let lock = NSLock()
    private var sinkInstalled = false

    var settings: Settings {
        get { (d.data(forKey: "settings").flatMap { try? JSONDecoder().decode(Settings.self, from: $0) }) ?? Settings() }
        set { d.set(try? JSONEncoder().encode(newValue), forKey: "settings") }
    }

    var config: PortalConfig {
        get { (d.data(forKey: "config").flatMap { try? JSONDecoder().decode(PortalConfig.self, from: $0) }) ?? PortalConfig() }
        set { d.set(try? JSONEncoder().encode(newValue), forKey: "config") }
    }

    var password: String? {
        let u = settings.username
        return u.isEmpty ? nil : Keychain.load(account: u)
    }

    func savePassword(_ p: String, for username: String) {
        Keychain.save(p, account: username)
        Masker.shared.register(p)
    }

    var lastLogin: Date? {
        get { d.object(forKey: "lastLogin") as? Date }
        set { d.set(newValue, forKey: "lastLogin") }
    }

    var lastMessage: String {
        get { d.string(forKey: "lastMessage") ?? "" }
        set { d.set(newValue, forKey: "lastMessage") }
    }

    var logs: [String] { d.stringArray(forKey: "logs") ?? [] }
    func clearLogs() { d.set([String](), forKey: "logs") }

    /// Idempotent. Also called from Shortcuts runs, which may start the process without the App struct.
    func installLogSink() {
        lock.lock(); defer { lock.unlock() }
        guard !sinkInstalled else { return }
        sinkInstalled = true
        if let pw = password { Masker.shared.register(pw) }
        AppLog.sink = { [weak self] line in
            guard let self else { return }
            self.lock.lock(); defer { self.lock.unlock() }
            var all = self.d.stringArray(forKey: "logs") ?? []
            all.append(line)
            self.d.set(Array(all.suffix(300)), forKey: "logs")
        }
    }
}
