import Foundation

/// Plain-English messages for ordinary users. Technical detail stays in the Logs tab.
public enum Friendly {
    public static func login(_ r: LoginResult) -> String {
        let d = r.detail.lowercased()
        switch r.outcome {
        case .success where d.contains("already online"): return "You already have internet - nothing to log in to right now."
        case .success: return "You're connected! Your Wi-Fi's login page accepted your details."
        case .badCredentials: return "The Wi-Fi login page didn't accept your username or password. Please check them and try again."
        default: break
        }
        if d.contains("captcha") || d.contains("otp") { return "Your Wi-Fi asks for a code or CAPTCHA at login. This app can't type that for you." }
        if d.contains("not set up") || d.contains("no portal") { return "The app hasn't found your Wi-Fi's login page yet. Connect to your Wi-Fi and tap Connect." }
        if d.contains("refused") || d.contains("different host") { return "Your Wi-Fi's login page seems to have moved. Tap Connect to set it up again." }
        if d.contains("no login form") { return "The login page didn't show a normal username/password form, so the app can't fill it in." }
        if d.contains("network error") || d.contains("couldn't reach") || d.contains("timeout") || d.contains("offline") {
            return "Couldn't reach the login page. Make sure your iPhone is connected to your Wi-Fi (not mobile data) and try again."
        }
        if d.contains("still not reachable") || d.contains("accepted the login twice") {
            return "The Wi-Fi accepted the login but internet still isn't working. Check your username and password."
        }
        return "Something went wrong: \(r.detail). You can share the Logs tab with whoever helps you."
    }

    public static func detect(_ r: DetectResult) -> String {
        switch r {
        case .alreadyOnline:
            return "You already have internet, so there is no login page to find right now. Turn Wi-Fi off and on (or wait until the Wi-Fi logs you out), then tap Connect again."
        case .problem(let m): return m
        case .found: return "Found your Wi-Fi's login page."
        }
    }
}
