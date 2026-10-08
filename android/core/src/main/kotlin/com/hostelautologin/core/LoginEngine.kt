package com.hostelautologin.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.net.Proxy
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class Outcome { SUCCESS, BAD_CREDENTIALS, CONFIG_ERROR, TRANSIENT }

data class LoginResult(val outcome: Outcome, val detail: String = "") {
    val fatal get() = outcome == Outcome.BAD_CREDENTIALS || outcome == Outcome.CONFIG_ERROR
}

/** Pluggable fallback (e.g. WebView). Must itself respect HostPin and never log credentials. */
fun interface LoginFallback {
    fun login(config: PortalConfig, username: String, password: String): LoginResult
}

private class MemoryCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.removeAll { old -> cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path } }
        store.addAll(cookies)
    }
    @Synchronized override fun loadForRequest(url: HttpUrl) = store.filter { it.matches(url) }
}

private class ConfigProblem(msg: String) : Exception(msg)
private class Resp(val code: Int, val location: String?, val text: String, val url: HttpUrl) {
    val isRedirect get() = code in listOf(301, 302, 303, 307, 308) && location != null
}

/**
 * Reproduces the normal portal login described by portal.json.
 * [baseClient] decides which network is used (Android passes a client bound to the Wi-Fi Network).
 */
class LoginEngine(
    private val cfg: PortalConfig,
    private val baseClient: OkHttpClient = OkHttpClient(),
    private val fallback: LoginFallback? = null,
) {
    private val placeholder = Regex("""\{\{\s*([^{}]+?)\s*\}\}""")

    private fun newClient(): OkHttpClient = baseClient.newBuilder()
        .proxy(Proxy.NO_PROXY)                 // never route credentials via a system proxy
        .followRedirects(false).followSslRedirects(false)
        .cookieJar(MemoryCookieJar())
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun url(path: String): HttpUrl {
        val full = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(path)) path else "${cfg.scheme}://${cfg.portalHost}$path"
        return HostPin.check(full, cfg.portalHost, cfg.scheme)
    }

    private fun render(value: String, user: String, pw: String, dyn: Map<String, String>, encode: Boolean = false): String =
        placeholder.replace(value) { m ->
            val key = m.groupValues[1]
            val v = when {
                key == "username" -> user
                key == "password" -> pw
                key == "timestamp_ms" -> System.currentTimeMillis().toString()
                key.startsWith("dyn:") && key.substring(4) in dyn -> dyn.getValue(key.substring(4))
                else -> throw ConfigProblem("unresolved placeholder '{{${key.substringBefore(':')}}}' in portal.json")
            }
            if (encode) URLEncoder.encode(v, "UTF-8").replace("+", "%20") else v
        }

    private fun fetch(client: OkHttpClient, req: Request): Resp = client.newCall(req).execute().use { r ->
        Resp(r.code, r.header("Location"), r.peekBody(MAX_BODY).string(), r.request.url)
    }

    private fun follow(client: OkHttpClient, start: Resp): Resp {
        var resp = start
        var hops = 0
        while (resp.isRedirect && hops < MAX_HOPS) {
            val next = HostPin.resolveRedirect(resp.url, resp.location!!, cfg.portalHost, cfg.scheme)
            AppLog.i("redirect -> ${next.encodedPath}")
            resp = fetch(client, Request.Builder().url(next).get().build())
            hops++
        }
        return resp
    }

    fun login(username: String, password: String): LoginResult {
        cfg.unconfiguredReason?.let { return LoginResult(Outcome.CONFIG_ERROR, it) }   // no network call
        if (username.isEmpty() || password.isEmpty()) return LoginResult(Outcome.CONFIG_ERROR, "username/password not set")
        Masker.global.register(password)
        val r = direct(username, password)
        if (r.outcome == Outcome.TRANSIENT && cfg.fallbackType == "browser") {
            if (fallback != null) { AppLog.i("direct login failed; trying WebView fallback"); return fallback.login(cfg, username, password) }
            AppLog.w("fallback.type is 'browser' but no fallback is available")
        }
        return r
    }

    private fun direct(user: String, pw: String): LoginResult {
        val client = newClient()
        return try {
            val dyn = mutableMapOf<String, String>()
            cfg.preFetch?.let { pre ->
                val u = url(pre.path)
                val r = follow(client, fetch(client, Request.Builder().url(u).get().build()))
                for ((name, rx) in pre.extract) {
                    val m = Regex(rx).find(r.text) ?: return LoginResult(Outcome.TRANSIENT, "pre_fetch: '$name' not found")
                    dyn[name] = m.groupValues.getOrElse(1) { m.value }
                }
            }
            val target = url(render(cfg.loginPath, user, pw, dyn, encode = true))
            val fields = cfg.fields.mapValues { render(it.value, user, pw, dyn) }
            val headers = cfg.headers.mapValues { render(it.value, user, pw, dyn) }
            val rb = Request.Builder()
            if (cfg.loginMethod == "GET") {
                val b = target.newBuilder(); fields.forEach { (k, v) -> b.addQueryParameter(k, v) }
                rb.url(b.build()).get()
            } else {
                rb.url(target)
                if (cfg.contentType == "json") {
                    val body = JsonObject(fields.mapValues { JsonPrimitive(it.value) }).toString()
                    rb.post(body.toRequestBody("application/json".toMediaType()))
                } else {
                    val fb = FormBody.Builder(); fields.forEach { (k, v) -> fb.add(k, v) }
                    rb.post(fb.build())
                }
            }
            headers.forEach { (k, v) -> rb.header(k, v) }
            AppLog.i("login ${cfg.loginMethod} ${cfg.scheme}://${cfg.portalHost}${target.encodedPath}")
            val first = fetch(client, rb.build())
            val final = if (first.isRedirect) follow(client, first) else first
            AppLog.i("login response status=${first.code}")
            val all = listOf(first, final).distinct()
            if (isBad(first, all)) return LoginResult(Outcome.BAD_CREDENTIALS, "portal rejected the credentials")
            if (first.code >= 500 || final.code >= 500) return LoginResult(Outcome.TRANSIENT, "portal error ${first.code}")
            if (isSuccess(first, final)) LoginResult(Outcome.SUCCESS, "login accepted")
            else LoginResult(Outcome.TRANSIENT, "success check did not match")
        } catch (e: ConfigProblem) {
            LoginResult(Outcome.CONFIG_ERROR, e.message ?: "config error")
        } catch (e: HostPinException) {
            LoginResult(Outcome.CONFIG_ERROR, "refused: ${e.message}")
        } catch (e: IOException) {
            LoginResult(Outcome.TRANSIENT, "network error: ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            LoginResult(Outcome.CONFIG_ERROR, "invalid request in portal.json")
        }
    }

    private fun isBad(first: Resp, all: List<Resp>): Boolean {
        if (first.code in cfg.badStatusIn) return true
        val needles = cfg.badBodyContains.map { it.lowercase() }
        return all.any { r -> needles.any { it in r.text.lowercase() } }
    }

    private fun isSuccess(first: Resp, final: Resp): Boolean {
        var checks = 0
        if (cfg.successStatusIn.isNotEmpty()) { checks++; if (first.code !in cfg.successStatusIn) return false }
        val body = final.text.lowercase()
        if (cfg.successBodyContains.isNotEmpty()) { checks++; if (cfg.successBodyContains.none { it.lowercase() in body }) return false }
        if (cfg.successBodyNotContains.isNotEmpty()) { checks++; if (cfg.successBodyNotContains.any { it.lowercase() in body }) return false }
        cfg.successRedirectContains?.let { checks++; if (it !in (first.location ?: "")) return false }
        cfg.successJsonPath?.let { (path, want) ->
            checks++
            val got = try { jsonPath(Json.parseToJsonElement(final.text), path) } catch (e: Exception) { null }
            if (got != want) return false
        }
        return checks > 0 || first.code in 200..399
    }

    private fun jsonPath(root: JsonElement, path: String): JsonElement? {
        var cur: JsonElement? = root
        for (p in path.split('.')) {
            cur = when (cur) {
                is JsonObject -> cur[p]
                is JsonArray -> p.toIntOrNull()?.let { cur.getOrNull(it) }
                else -> null
            }
        }
        return cur
    }

    /** Optional keep-alive ping; true if the portal answered < 400. */
    fun keepalive(): Boolean {
        val path = cfg.keepalivePath ?: return false
        return try {
            val rb = Request.Builder().url(url(path))
            if (cfg.keepaliveMethod == "POST") rb.post(ByteArray(0).toRequestBody(null)) else rb.get()
            val r = fetch(newClient(), rb.build())
            AppLog.i("keepalive status=${r.code}")
            r.code < 400
        } catch (e: Exception) {
            AppLog.i("keepalive failed: ${e.javaClass.simpleName}"); false
        }
    }

    companion object {
        private const val MAX_HOPS = 5
        private const val MAX_BODY = 200_000L
    }
}

/** Internet probe (SPEC section 3). Carries no credentials; no redirects; no proxy. */
fun probeInternet(base: OkHttpClient, url: String = PROBE_URL, expected: String = PROBE_BODY): Boolean = try {
    val c = base.newBuilder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
    c.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
        r.code == 200 && r.peekBody(1024).string().trim() == expected
    }
} catch (e: Exception) { false }
