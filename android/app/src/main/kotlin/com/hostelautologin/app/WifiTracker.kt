package com.hostelautologin.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress

/**
 * Follows the Wi-Fi network via ConnectivityManager.NetworkCallback.
 * Every HTTP request this app makes is created from [client], whose sockets and DNS are bound to the
 * Wi-Fi Network, so traffic never leaks onto mobile data.
 */
class WifiTracker(private val context: Context, private val onChange: () -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile var network: Network? = null; private set
    @Volatile var ssid: String? = null; private set
    /** NET_CAPABILITY_VALIDATED && !NET_CAPABILITY_CAPTIVE_PORTAL, or null when there is no Wi-Fi. */
    @Volatile var validated: Boolean? = null; private set

    private fun update(n: Network, caps: NetworkCapabilities) {
        network = n
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        ssid = readSsid(caps)
        onChange()
    }

    private fun readSsid(caps: NetworkCapabilities): String? {
        var raw: String? = null
        if (Build.VERSION.SDK_INT >= 29) raw = (caps.transportInfo as? WifiInfo)?.ssid
        if (raw == null || raw == WifiManager.UNKNOWN_SSID) {
            @Suppress("DEPRECATION")
            raw = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
        }
        val s = raw?.removeSurrounding("\"")
        return if (s.isNullOrEmpty() || s == WifiManager.UNKNOWN_SSID) null else s
    }

    private fun lost(n: Network) {
        if (network == n) { network = null; ssid = null; validated = null }
        onChange()
    }

    private val callback: ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = update(n, c)
                override fun onLost(n: Network) = lost(n)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = update(n, c)
                override fun onLost(n: Network) = lost(n)
            }
        }

    fun start() {
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(req, callback)
    }

    fun stop() {
        try { cm.unregisterNetworkCallback(callback) } catch (_: IllegalArgumentException) { }
    }

    /** HTTP client whose connections and DNS lookups go through the Wi-Fi network; null when not on Wi-Fi. */
    fun client(): OkHttpClient? {
        val n = network ?: return null
        return OkHttpClient.Builder()
            .socketFactory(n.socketFactory)
            .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = n.getAllByName(hostname).toList() })
            .build()
    }

    /** Used by the WebView fallback only (WebView cannot be bound per-instance). */
    fun bindProcess(bind: Boolean) {
        cm.bindProcessToNetwork(if (bind) network else null)
    }
}
