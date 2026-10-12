package com.hostelautologin.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class HostPinException(message: String) : Exception(message)

/** A user-confirmed portal address: host[:port] + scheme. */
data class Origin(val host: String, val scheme: String)

/** Credentials may only go to exactly (scheme, host, port) of the configured portal. */
object HostPin {
    private fun split(portalHost: String): Pair<String, Int?> {
        val i = portalHost.lastIndexOf(':')
        return if (i > 0 && !portalHost.endsWith("]") && portalHost.substring(i + 1).all { it.isDigit() } && i < portalHost.length - 1)
            portalHost.substring(0, i).lowercase() to portalHost.substring(i + 1).toInt()
        else portalHost.lowercase() to null
    }

    fun check(url: String, portalHost: String, scheme: String): HttpUrl {
        if (url.contains('@')) throw HostPinException("URL with userinfo refused")
        val u = url.toHttpUrlOrNull() ?: throw HostPinException("invalid URL")
        return check(u, portalHost, scheme)
    }

    fun check(u: HttpUrl, portalHost: String, scheme: String): HttpUrl {
        val (host, port) = split(portalHost)
        if (host.isEmpty()) throw HostPinException("portal_host is empty")
        if (!u.scheme.equals(scheme, true)) throw HostPinException("scheme '${u.scheme}' not allowed (expected '$scheme')")
        if (u.username.isNotEmpty() || u.password.isNotEmpty()) throw HostPinException("URL with userinfo refused")
        if (!u.host.equals(host, true)) throw HostPinException("host '${u.host}' is not the pinned portal host")
        val wantPort = port ?: HttpUrl.defaultPort(scheme)
        if (u.port != wantPort) throw HostPinException("port does not match pinned portal host")
        return u
    }

    /** Like [check] but accepts any of the user-confirmed origins (e.g. the hostel and the college portal). */
    fun checkAny(u: HttpUrl, origins: List<Origin>): HttpUrl {
        if (origins.isEmpty()) throw HostPinException("no portal host has been confirmed yet")
        var last: HostPinException? = null
        for (o in origins) try { return check(u, o.host, o.scheme) } catch (e: HostPinException) { last = e }
        throw HostPinException("host '${u.host}' is not one of the confirmed portal hosts")
    }

    /** Resolve a Location header against the current URL, then pin-check the result. */
    fun resolveRedirect(current: HttpUrl, location: String, portalHost: String, scheme: String): HttpUrl {
        val next = current.resolve(location) ?: throw HostPinException("invalid redirect target")
        return check(next, portalHost, scheme)
    }
}
