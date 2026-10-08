package com.hostelautologin.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

class ConfigException(message: String) : Exception(message)

const val PROBE_URL = "http://www.msftconnecttest.com/connecttest.txt"
const val PROBE_BODY = "Microsoft Connect Test"
val BACKOFF_SECONDS = listOf(2L, 5L, 15L, 30L)           // SPEC 1.5

private val FORBIDDEN_HEADERS = setOf("cookie", "authorization", "proxy-authorization", "host", "content-length")

data class PreFetch(val path: String, val extract: List<Pair<String, String>>)   // name to regex

data class WebViewSpec(
    val path: String, val usernameSelector: String, val passwordSelector: String,
    val submitSelector: String, val successUrlContains: String?, val successTextContains: String?,
)

data class PortalConfig(
    val ssid: String,
    val portalHost: String,
    val scheme: String,
    val loginPath: String,
    val loginMethod: String,
    val contentType: String,
    val headers: Map<String, String>,
    val fields: Map<String, String>,
    val preFetch: PreFetch?,
    val successStatusIn: List<Int>,
    val successBodyContains: List<String>,
    val successBodyNotContains: List<String>,
    val successRedirectContains: String?,
    val successJsonPath: Pair<String, JsonElement>?,
    val badStatusIn: List<Int>,
    val badBodyContains: List<String>,
    val keepalivePath: String?,
    val keepaliveMethod: String,
    val keepaliveIntervalSeconds: Int,
    val fallbackType: String,
    val webView: WebViewSpec?,
) {
    /** Non-null when the file is still the placeholder / draft; no network call may be made. */
    val unconfiguredReason: String?
        get() {
            val blob = listOf(loginPath, headers.toString(), fields.toString(), preFetch?.path.orEmpty()).joinToString(" ")
            if ("REPLACE_ME" in blob) return "portal.json is still the placeholder (contains REPLACE_ME). Edit it in Settings."
            if (Regex("""\{\{\s*REVIEW""").containsMatchIn(blob)) return "portal.json contains unresolved {{REVIEW:...}} fields."
            return null
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = false }

        fun parse(text: String): PortalConfig {
            val root = try { json.parseToJsonElement(text.removePrefix("﻿")) } catch (e: Exception) {
                throw ConfigException("portal.json is not valid JSON: ${e.message?.take(120)}")
            }
            val d = root as? JsonObject ?: throw ConfigException("portal.json must be a JSON object")
            if (d.int("schema_version") != 1) throw ConfigException("unsupported or missing schema_version (expected 1)")
            val host = d.str("portal_host").trim()
            if (host.isEmpty() || '/' in host || '@' in host || "://" in host)
                throw ConfigException("portal_host must be a bare host or host:port")
            val scheme = d.str("scheme", "http").lowercase()
            if (scheme != "http" && scheme != "https") throw ConfigException("scheme must be http or https")
            val login = d.obj("login")
            val path = login.str("path")
            if (path.isEmpty()) throw ConfigException("login.path is required")
            val method = login.str("method", "POST").uppercase()
            if (method != "GET" && method != "POST") throw ConfigException("login.method must be GET or POST")
            val ctype = login.str("content_type", "form")
            if (ctype != "form" && ctype != "json") throw ConfigException("login.content_type must be form or json")
            val pf = login["pre_fetch"] as? JsonObject
            val success = d.obj("success")
            val bad = d.obj("bad_credentials")
            val ka = (d["keepalive"] as? JsonObject)?.takeIf { it.str("path").isNotEmpty() }
            val fb = d.obj("fallback")
            val wv = fb["webview"] as? JsonObject
            val jp = success["json_path_equals"] as? JsonObject
            return PortalConfig(
                ssid = d.str("ssid", "University_Hostel"), portalHost = host, scheme = scheme,
                loginPath = path, loginMethod = method, contentType = ctype,
                headers = login.strMap("headers").filterKeys { it.lowercase() !in FORBIDDEN_HEADERS },
                fields = login.strMap("fields"),
                preFetch = pf?.let { p ->
                    PreFetch(p.str("path"), (p["extract"] as? JsonArray).orEmpty().map { (it as JsonObject).let { o -> o.str("name") to o.str("regex") } })
                },
                successStatusIn = success.ints("status_in"), successBodyContains = success.strs("body_contains"),
                successBodyNotContains = success.strs("body_not_contains"),
                successRedirectContains = success.str("redirect_location_contains").ifEmpty { null },
                successJsonPath = jp?.let { it.str("path") to (it["value"] ?: JsonNull) },
                badStatusIn = bad.ints("status_in"), badBodyContains = bad.strs("body_contains"),
                keepalivePath = ka?.str("path"), keepaliveMethod = ka?.str("method", "GET")?.uppercase() ?: "GET",
                keepaliveIntervalSeconds = ka?.int("interval_seconds") ?: 300,
                fallbackType = fb.str("type", "none"),
                webView = wv?.let {
                    WebViewSpec(it.str("path", "/"), it.str("username_selector"), it.str("password_selector"),
                        it.str("submit_selector"), it.str("success_url_contains").ifEmpty { null },
                        it.str("success_text_contains").ifEmpty { null })
                },
            )
        }

        // --- tiny JSON helpers (lenient about missing keys, strict about types we use)
        private fun JsonObject.str(k: String, def: String = ""): String = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull ?: def
        private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.obj(k: String): JsonObject = (this[k] as? JsonObject) ?: JsonObject(emptyMap())
        private fun JsonObject.strs(k: String): List<String> = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        private fun JsonObject.ints(k: String): List<Int> = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        private fun JsonObject.strMap(k: String): Map<String, String> =
            obj(k).entries.mapNotNull { (key, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { key to it } }.toMap(LinkedHashMap())
        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}
