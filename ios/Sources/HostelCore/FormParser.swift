import Foundation
import SwiftSoup

/// Reads the login form a captive portal serves, the same way a browser would.
public enum FormParser {
    private static func rx(_ p: String) -> NSRegularExpression { try! NSRegularExpression(pattern: p, options: .caseInsensitive) }
    private static let userRx = rx(#"user|login|uname|email|account|acct|phone|mobile|reg|roll|(^|[^a-z])id($|[^a-z])"#)
    private static let captchaRx = rx(#"captcha|otp|verif|security.?code|sms"#)
    private static let termsRx = rx(#"accept|agree|terms|tos|policy|consent"#)
    private static let passRx = rx(#"type\s*=\s*["']?password"#)
    private static let textlike: Set<String> = ["", "text", "email", "tel", "number"]

    private static func hit(_ re: NSRegularExpression, _ s: String) -> Bool { re.firstMatch(in: s, options: [], range: NSRange(s.startIndex..., in: s)) != nil }
    private static func a(_ e: Element, _ k: String) -> String { (try? e.attr(k)) ?? "" }

    public static func hasPasswordField(_ html: String) -> Bool { hit(passRx, html) }

    public static func parse(html: String, base: URL, usernameHint: String? = nil, passwordHint: String? = nil) -> LoginForm? {
        guard let doc = try? SwiftSoup.parse(html, base.absoluteString), let forms = try? doc.select("form") else { return nil }
        for form in forms.array() {
            let all = (try? form.select("input, select, textarea").array()) ?? []
            let controls = all.filter { $0.hasAttr("name") && !$0.hasAttr("disabled") }
            let pws = controls.filter { $0.tagName() == "input" && a($0, "type").lowercased() == "password" }
            guard let pw = pws.first(where: { a($0, "name") == passwordHint }) ?? pws.first else { continue }

            let texts = controls.filter { $0.tagName() == "input" && textlike.contains(a($0, "type").lowercased()) }
            let captcha = texts.first { hit(captchaRx, a($0, "name")) }
            let pwIndex = controls.firstIndex { $0 === pw } ?? 0
            let userEl: Element? =
                texts.first(where: { usernameHint != nil && a($0, "name") == usernameHint })
                ?? texts.first(where: { $0 !== captcha && hit(userRx, a($0, "name")) })
                ?? texts.last(where: { t in t !== captcha && (controls.firstIndex { $0 === t } ?? Int.max) < pwIndex })
                ?? texts.first(where: { $0 !== captcha })

            var warnings: [String] = []
            var fields: [FormField] = []
            var submitAdded = false
            for el in controls where el !== pw && el !== userEl {
                let name = a(el, "name")
                switch el.tagName() {
                case "select":
                    let opts = (try? el.select("option").array()) ?? []
                    let sel = opts.first(where: { $0.hasAttr("selected") }) ?? opts.first
                    if let o = sel { let v = a(o, "value"); fields.append(FormField(name, "select", v.isEmpty ? ((try? o.text()) ?? "") : v)) }
                case "textarea":
                    fields.append(FormField(name, "textarea", (try? el.text()) ?? ""))
                default:
                    let type = a(el, "type").lowercased()
                    switch type {
                    case "submit":
                        if !submitAdded { submitAdded = true; fields.append(FormField(name, type, a(el, "value"))) }
                    case "button", "image", "reset", "file": break
                    case "checkbox", "radio":
                        let force = type == "checkbox" && hit(termsRx, name) && !el.hasAttr("checked")
                        if el.hasAttr("checked") || force {
                            let v = a(el, "value")
                            fields.append(FormField(name, type, v.isEmpty ? "on" : v))
                            if force { warnings.append("Ticks the '\(name)' checkbox (terms of use) for you.") }
                        }
                    default:
                        if textlike.contains(type) && el !== captcha && a(el, "value").isEmpty { warnings.append("Extra empty field '\(name)' will be sent blank.") }
                        fields.append(FormField(name, type, a(el, "value")))
                    }
                }
            }
            let actionRaw = a(form, "action").trimmingCharacters(in: .whitespaces)
            guard let action = HostPin.resolve(actionRaw.isEmpty ? base.absoluteString : actionRaw, relativeTo: base) else { continue }
            if action.scheme == "http" { warnings.append("This page isn't encrypted: others on the same Wi-Fi could see your password.") }
            let method = a(form, "method").isEmpty ? "GET" : a(form, "method").uppercased()
            return LoginForm(action: action, method: method, fields: fields,
                             usernameField: userEl.map { a($0, "name") }, passwordField: a(pw, "name"), warnings: warnings,
                             blocked: captcha.map { "This portal asks for a CAPTCHA/OTP code ('\(a($0, "name"))'), which can't be automated." })
        }
        return nil
    }
}
