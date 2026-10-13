import AppIntents
import HostelCore

/// Shows up in the Shortcuts app as "Log in to Wi-Fi". Attach it to a Wi-Fi automation (see the Automate tab).
struct LoginToWiFiIntent: AppIntent {
    static var title: LocalizedStringResource = "Log in to Wi-Fi"
    static var description = IntentDescription("Logs in to your Wi-Fi's login page using the details saved in HostelAutoLogin.")
    static var openAppWhenRun: Bool = false

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let r = await Runner.runStored(quick: true)
        return .result(dialog: IntentDialog(stringLiteral: Friendly.login(r)))
    }
}

struct HostelShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: LoginToWiFiIntent(), phrases: ["Log in to Wi-Fi with \(.applicationName)", "\(.applicationName) login"],
                    shortTitle: "Log in to Wi-Fi", systemImageName: "wifi")
    }
}
