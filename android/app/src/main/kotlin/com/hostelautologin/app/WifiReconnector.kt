package com.hostelautologin.app

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import com.hostelautologin.core.AppLog

/**
 * Best-effort Wi-Fi rejoin. Android does not let apps force a connection:
 *  - Android 10+: the app registers the user's SSIDs as network suggestions; the OS then auto-joins them when in range
 *    (the user approves this once in a system prompt).
 *  - Android 8-9: the legacy API can re-enable a saved network and reconnect directly.
 */
class WifiReconnector(context: Context) {
    private val wm = context.applicationContext.getSystemService(WifiManager::class.java)
    private var suggested: List<String> = emptyList()

    /** Returns true if a request was made to the system. */
    fun reconnect(ssids: List<String>): Boolean {
        if (!wm.isWifiEnabled) { AppLog.w("Wi-Fi is switched off; cannot reconnect"); return false }
        return if (Build.VERSION.SDK_INT >= 29) suggest(ssids) else legacy(ssids)
    }

    private fun suggest(ssids: List<String>): Boolean {
        if (ssids == suggested) return true                       // already registered; the OS decides when to join
        wm.removeNetworkSuggestions(emptyList())                  // empty list removes all of this app's suggestions
        val list = ssids.map { WifiNetworkSuggestion.Builder().setSsid(it).setIsAppInteractionRequired(false).build() }
        val status = wm.addNetworkSuggestions(list)
        AppLog.i("network suggestions status=$status for ${ssids.size} network(s)")
        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) { suggested = ssids; return true }
        return false
    }

    @Suppress("DEPRECATION")
    private fun legacy(ssids: List<String>): Boolean {
        val saved = try { wm.configuredNetworks.orEmpty() } catch (e: SecurityException) { emptyList() }
        val target = saved.firstOrNull { it.SSID?.removeSurrounding("\"") in ssids } ?: return false
        wm.enableNetwork(target.networkId, true)
        return wm.reconnect()
    }
}
