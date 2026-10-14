import SwiftUI
import HostelCore

struct ContentView: View {
    @StateObject private var model = AppModel()

    var body: some View {
        TabView {
            HomeView().tabItem { Label("Home", systemImage: "wifi") }
            ConnectView().tabItem { Label("Connect", systemImage: "key") }
            AutomateView().tabItem { Label("Automate", systemImage: "bolt") }
            LogsView().tabItem { Label("Logs", systemImage: "list.bullet") }
        }
        .environmentObject(model)
    }
}

struct ResultCard: View {
    let text: String
    let ok: Bool?
    var body: some View {
        Text(text).frame(maxWidth: .infinity, alignment: .leading).padding()
            .background((ok == true ? Color.green : ok == false ? Color.red : Color.gray).opacity(0.18)).cornerRadius(12)
    }
}

struct HomeView: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ResultCard(text: !m.isConfigured ? "Not set up yet. Open the Connect tab and tap Connect."
                                    : m.online == true ? "You're online" : m.online == false ? "Not online yet. Tap “Log in now”." : "Checking…",
                               ok: !m.isConfigured ? nil : m.online)
                    if let d = m.lastLogin { Text("Last login: \(d.formatted(date: .abbreviated, time: .shortened))") }
                    if !m.lastMessage.isEmpty { Text(m.lastMessage).font(.footnote).foregroundColor(.secondary) }
                    Button { m.loginNow() } label: { Text(m.busy ? "Please wait…" : "Log in now").frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent).disabled(m.busy || !m.isConfigured)
                    if !m.status.isEmpty { ResultCard(text: m.status, ok: m.success) }
                    Text("iPhones don't let apps run in the background all day, so for automatic logins open the Automate tab (takes 1 minute).")
                        .font(.footnote).foregroundColor(.secondary)
                }.padding()
            }
            .navigationTitle("Wify")
            .task { await m.checkOnline() }
            .refreshable { m.refresh(); await m.checkOnline() }
        }.navigationViewStyle(.stack)
    }
}

struct ConnectView: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("1. Connect your iPhone to your Wi-Fi.\n2. Type your own Wi-Fi login details below.\n3. Tap Connect. That's it.")
                    Text("Wi-Fi name(s)").font(.footnote).foregroundColor(.secondary)
                    TextEditor(text: $m.ssidsText).frame(height: 70).overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.gray.opacity(0.4)))
                    Text("Just for your reference. Several Wi-Fi names with the same login (hostel, college…)? One per line.").font(.footnote).foregroundColor(.secondary)
                    TextField("Username", text: $m.username).textInputAutocapitalization(.never).autocorrectionDisabled().textFieldStyle(.roundedBorder)
                    SecureField(m.hasSavedPassword ? "Password (leave empty to keep the saved one)" : "Password", text: $m.password).textFieldStyle(.roundedBorder)
                    Button { m.connect() } label: { Text(m.busy ? "Please wait…" : "Connect").frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent).disabled(m.busy)
                    if !m.status.isEmpty { ResultCard(text: m.status, ok: m.success) }
                    Text("Your password is stored in the iPhone Keychain and only ever sent to the login page address you confirm.")
                        .font(.footnote).foregroundColor(.secondary)
                }.padding()
            }
            .navigationTitle("Connect")
            .alert("Is this your Wi-Fi's login page?", isPresented: $m.showConfirm) {
                Button("Yes, continue") { m.confirmPortal() }
                Button("No", role: .cancel) { m.declinePortal() }
            } message: {
                Text("We found a login page at \(m.found?.host ?? "").\n\nIf this is the page you normally log in on, tap Yes. Your username and password will only ever be sent to this address." +
                     ((m.found?.scheme == "http") ? "\n\nNote: this page is not encrypted, so other people on the same Wi-Fi could see your password. That is how your Wi-Fi works, not something this app controls." : ""))
            }
        }.navigationViewStyle(.stack)
    }
}

struct AutomateView: View {
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ResultCard(text: "iPhones don't allow apps to run all day in the background. The reliable way is an automation that runs “Log in to Wi-Fi” every time your phone joins your Wi-Fi.", ok: nil)
                    Text("Set it up once (about 1 minute)").font(.headline)
                    Text("1. Open the Shortcuts app → Automation tab → “+” → Wi-Fi.\n2. Tap Network, pick your Wi-Fi (repeat the steps for each of your Wi-Fi names). Choose “Is Connected”.\n3. Tap Next → New Blank Automation → Add Action.\n4. Search for “Log in to Wi-Fi” (Wify) and add it.\n5. Tap Next, turn on “Run Immediately”, and turn off “Notify When Run” if you like. Tap Done.")
                    Link("Open the Shortcuts app", destination: URL(string: "shortcuts://")!).buttonStyle(.borderedProminent)
                    Text("One-tap login any time").font(.headline)
                    Text("Settings → Accessibility → Touch → Back Tap → Double Tap → pick the shortcut “Log in to Wi-Fi”. Now double-tap the back of your iPhone whenever the Wi-Fi logs you out. You can also add it to the Home Screen or the Action Button.")
                    Text("Keep Wi-Fi connected").font(.headline)
                    Text("Settings → Wi-Fi → (i) next to your network → turn on “Auto-Join”. iPhones reconnect to saved networks by themselves; apps are not allowed to force it.")
                    Text("Also helpful").font(.headline)
                    Text("Settings → General → Background App Refresh → on for Wify. iOS then runs a quick check now and then (timing is up to iOS, not guaranteed).")
                }.padding()
            }.navigationTitle("Automate")
        }.navigationViewStyle(.stack)
    }
}

struct LogsView: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        NavigationView {
            List(m.logs.reversed(), id: \.self) { Text($0).font(.system(size: 11, design: .monospaced)) }
                .navigationTitle("Logs")
                .toolbar {
                    ToolbarItem(placement: .navigationBarLeading) { Button("Refresh") { m.refresh() } }
                    ToolbarItem(placement: .navigationBarTrailing) { ShareLink(item: m.logs.joined(separator: "\n")) }
                    ToolbarItem(placement: .bottomBar) { Button("Clear", role: .destructive) { m.clearLogs() } }
                }
                .onAppear { m.refresh() }
        }.navigationViewStyle(.stack)
    }
}
