package com.hostelautologin.core

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class FormField(val name: String, val type: String, val value: String)

data class LoginForm(
    val action: HttpUrl,
    val method: String,
    val fields: List<FormField>,          // everything the browser would submit, except credentials
    val usernameField: String?,
    val passwordField: String,
    val warnings: List<String>,
    /** Non-null when the form needs something that cannot be automated (CAPTCHA, OTP). */
    val blocked: String?,
)

/** Reads the login form a captive portal serves, the same way a browser would. */
object FormParser {
    private val USER = Regex("user|login|uname|email|account|acct|phone|mobile|reg|roll|(^|[^a-z])id($|[^a-z])", RegexOption.IGNORE_CASE)
    private val CAPTCHA = Regex("captcha|otp|verif|security.?code|sms", RegexOption.IGNORE_CASE)
    private val TERMS = Regex("accept|agree|terms|tos|policy|consent", RegexOption.IGNORE_CASE)
    private val TEXTLIKE = setOf("", "text", "email", "tel", "number")

    fun hasPasswordField(html: String) = Regex("""type\s*=\s*["']?password""", RegexOption.IGNORE_CASE).containsMatchIn(html)

    fun parse(html: String, base: HttpUrl, usernameHint: String? = null, passwordHint: String? = null): LoginForm? {
        val doc = Jsoup.parse(html, base.toString())
        for (form in doc.select("form")) {
            val controls = form.select("input, select, textarea").filter { it.hasAttr("name") && !it.hasAttr("disabled") }
            val pw = controls.firstOrNull { it.tagName() == "input" && it.attr("type").equals("password", true) && it.attr("name") == passwordHint }
                ?: controls.firstOrNull { it.tagName() == "input" && it.attr("type").equals("password", true) }
                ?: continue
            val warnings = mutableListOf<String>()
            val textInputs = controls.filter { it.tagName() == "input" && it.attr("type").lowercase() in TEXTLIKE }
            val captcha = textInputs.firstOrNull { CAPTCHA.containsMatchIn(it.attr("name")) }
            val pwIndex = controls.indexOf(pw)
            val userEl = textInputs.firstOrNull { it.attr("name") == usernameHint && usernameHint != null }
                ?: textInputs.firstOrNull { it !== captcha && USER.containsMatchIn(it.attr("name")) }
                ?: textInputs.lastOrNull { it !== captcha && controls.indexOf(it) < pwIndex }
                ?: textInputs.firstOrNull { it !== captcha }
            val fields = mutableListOf<FormField>()
            var submitAdded = false
            for (el in controls) {
                val name = el.attr("name")
                if (el === pw || el === userEl) continue
                when (el.tagName()) {
                    "select" -> {
                        val opt = el.select("option[selected]").firstOrNull() ?: el.select("option").firstOrNull()
                        if (opt != null) fields += FormField(name, "select", opt.attr("value").ifEmpty { opt.text() })
                    }
                    "textarea" -> fields += FormField(name, "textarea", el.text())
                    else -> when (val type = el.attr("type").lowercase()) {
                        "submit" -> if (!submitAdded) { submitAdded = true; fields += FormField(name, type, el.attr("value")) }
                        "button", "image", "reset", "file" -> {}
                        "checkbox", "radio" -> {
                            val force = type == "checkbox" && TERMS.containsMatchIn(name) && !el.hasAttr("checked")
                            if (el.hasAttr("checked") || force) {
                                fields += FormField(name, type, el.attr("value").ifEmpty { "on" })
                                if (force) warnings += "Ticks the '$name' checkbox (terms of use) for you."
                            }
                        }
                        else -> {
                            if (type in TEXTLIKE && el !== captcha && el.attr("value").isEmpty())
                                warnings += "Extra empty field '$name' will be sent blank."
                            fields += FormField(name, type, el.attr("value"))
                        }
                    }
                }
            }
            val action = base.resolve(form.attr("action").trim().ifEmpty { base.toString() }) ?: continue
            if (action.scheme == "http") warnings += "This portal uses plain HTTP: others on the network could see your password."
            return LoginForm(
                action, form.attr("method").ifEmpty { "get" }.uppercase(), fields, userEl?.attr("name"), pw.attr("name"), warnings,
                captcha?.let { "This portal asks for a CAPTCHA/OTP code ('${it.attr("name")}'), which can't be automated." },
            )
        }
        return null
    }
}

data class DetectedPortal(val host: String, val scheme: String, val form: LoginForm, val pageUrl: HttpUrl) {
    val summary: String
        get() = "Login page: $scheme://$host\nUsername field: ${form.usernameField ?: "(none found)"}\nPassword field: ${form.passwordField}\n" +
            "Other fields sent as the browser would: ${form.fields.size}" +
            (if (form.warnings.isNotEmpty()) "\n\n" + form.warnings.joinToString("\n") { "• $it" } else "")
}

sealed class DetectResult {
    object AlreadyOnline : DetectResult()
    class Found(val portal: DetectedPortal) : DetectResult()
    class Problem(val message: String) : DetectResult()
}

internal sealed class Landing {
    object Online : Landing()
    class Page(val url: HttpUrl, val html: String) : Landing()
    class Failed(val reason: String, val transient: Boolean) : Landing()
}

/**
 * Generic login without a hand-made portal.json: follows the captive-portal redirect from the standard
 * connectivity probe, reads the portal's own login form, fills in the user's credentials and submits it.
 * Credentials are only sent to the pinned portal host chosen by the user in [detect].
 */
class PortalDiscovery(base: OkHttpClient, private val settleMillis: Long = 1500, private val probeUrl: String = PROBE_URL) {
    private val http = base.newBuilder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .cookieJar(MemoryCookieJar()).connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()

    private fun get(url: HttpUrl): Resp = http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
        Resp(r.code, r.header("Location"), r.peekBody(MAX_BODY).string(), r.request.url)
    }

    private val metaRefresh = Regex("""<meta[^>]+http-equiv\s*=\s*["']?refresh[^>]+url\s*=\s*['"]?([^'">\s]+)""", RegexOption.IGNORE_CASE)
    private val jsRedirect = Regex("""(?:window\.|document\.|top\.)?location(?:\.href)?\s*(?:=|\.replace\(|\.assign\()\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private fun redirectTarget(r: Resp): String? {
        if (r.isRedirect) return r.location
        if (r.code !in 200..299 || FormParser.hasPasswordField(r.text)) return null
        return (metaRefresh.find(r.text) ?: jsRedirect.find(r.text))?.groupValues?.get(1)
    }

    /** Follows the probe's redirect chain; [pin] is called for every redirect target before it is requested. */
    internal fun land(pin: (HttpUrl) -> Unit): Landing {
        var url = probeUrl.toHttpUrl()
        repeat(MAX_HOPS + 1) { hop ->
            val r = try { get(url) } catch (e: IOException) { return Landing.Failed("network error: ${e.javaClass.simpleName}", true) }
            if (hop == 0 && r.code == 200 && r.text.trim() == PROBE_BODY) return Landing.Online
            val next = redirectTarget(r) ?: return if (r.code in 200..299) Landing.Page(url, r.text)
                else Landing.Failed("portal page returned ${r.code}", r.code >= 500)
            val resolved = url.resolve(next) ?: return Landing.Failed("bad redirect from portal", false)
            pin(resolved)
            url = resolved
        }
        return Landing.Failed("too many redirects", false)
    }

    private fun hostOf(u: HttpUrl) = if (u.port == HttpUrl.defaultPort(u.scheme)) u.host else "${u.host}:${u.port}"

    /** User-initiated: find the portal and its login form so the user can confirm the host. */
    fun detect(): DetectResult {
        return when (val l = land { }) {
            Landing.Online -> DetectResult.AlreadyOnline
            is Landing.Failed -> DetectResult.Problem("Couldn't reach a login page: ${l.reason}. Are you on the Wi-Fi that shows a login page?")
            is Landing.Page -> {
                val form = FormParser.parse(l.html, l.url)
                    ?: return DetectResult.Problem("Found a page at ${hostOf(l.url)} but no username/password form. " +
                        "This portal probably needs a browser-based login, which isn't supported in automatic mode.")
                if (hostOf(form.action) != hostOf(l.url))
                    return DetectResult.Problem("The login form submits to a different host (${hostOf(form.action)}) than the page (${hostOf(l.url)}). Refusing automatic setup.")
                form.blocked?.let { return DetectResult.Problem(it) }
                DetectResult.Found(DetectedPortal(hostOf(l.url), l.url.scheme, form, l.url))
            }
        }
    }

    fun loginAuto(cfg: PortalConfig, user: String, pw: String): LoginResult {
        cfg.unconfiguredReason?.let { return LoginResult(Outcome.CONFIG_ERROR, it) }
        val pin = { u: HttpUrl -> HostPin.checkAny(u, cfg.origins); Unit }
        return try {
            when (val l = land(pin)) {
                Landing.Online -> LoginResult(Outcome.SUCCESS, "already online")
                is Landing.Failed -> LoginResult(Outcome.TRANSIENT, l.reason)
                is Landing.Page -> submit(cfg, l, user, pw, pin)
            }
        } catch (e: HostPinException) {
            LoginResult(Outcome.CONFIG_ERROR, "refused: ${e.message}. If your portal changed, run \"Detect my portal\" again.")
        } catch (e: IOException) {
            LoginResult(Outcome.TRANSIENT, "network error: ${e.javaClass.simpleName}")
        }
    }

    private fun submit(cfg: PortalConfig, page: Landing.Page, user: String, pw: String, pin: (HttpUrl) -> Unit): LoginResult {
        pin(page.url)
        val form = FormParser.parse(page.html, page.url, cfg.autoUsernameField, cfg.autoPasswordField)
            ?: return LoginResult(Outcome.CONFIG_ERROR, "no login form found on the portal page")
        form.blocked?.let { return LoginResult(Outcome.CONFIG_ERROR, it) }
        HostPin.checkAny(form.action, cfg.origins)
        if (hostOf(form.action) != hostOf(page.url)) throw HostPinException("login form submits to a different host than its page")
        val pairs = form.fields.map { it.name to it.value } +
            listOfNotNull(form.usernameField?.let { it to user }, form.passwordField to pw)
        val req = Request.Builder().header("Referer", page.url.toString())
        if (form.method == "GET") {
            val b = form.action.newBuilder(); pairs.forEach { (k, v) -> b.addQueryParameter(k, v) }
            req.url(b.build()).get()
        } else {
            val fb = FormBody.Builder(); pairs.forEach { (k, v) -> fb.add(k, v) }
            req.url(form.action).post(fb.build())
        }
        AppLog.i("auto login ${form.method} ${form.action.scheme}://${hostOf(form.action)}${form.action.encodedPath}")
        var resp = http.newCall(req.build()).execute().use { r -> Resp(r.code, r.header("Location"), r.peekBody(MAX_BODY).string(), r.request.url) }
        var hops = 0
        while (resp.isRedirect && hops++ < MAX_HOPS) {
            val next = resp.url.resolve(resp.location!!) ?: break
            try { pin(next) } catch (e: HostPinException) { break }      // never follow off the portal host after sending credentials
            resp = get(next)
        }
        AppLog.i("auto login response status=${resp.code}")
        if (resp.code >= 500) return LoginResult(Outcome.TRANSIENT, "portal error ${resp.code}")
        if (FormParser.hasPasswordField(resp.text)) {
            // The login form is back: wrong credentials, unless the internet is actually up already.
            if (settleMillis > 0) Thread.sleep(settleMillis)
            return if (probeInternet(http, probeUrl)) LoginResult(Outcome.SUCCESS, "logged in")
            else LoginResult(Outcome.BAD_CREDENTIALS, "the portal showed the login form again - username or password probably wrong")
        }
        return LoginResult(Outcome.SUCCESS, "login submitted")
    }

    companion object {
        private const val MAX_HOPS = 6
        private const val MAX_BODY = 200_000L
    }
}
