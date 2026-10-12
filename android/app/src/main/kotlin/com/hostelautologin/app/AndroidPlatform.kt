package com.hostelautologin.app

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.hostelautologin.core.AppLog
import com.hostelautologin.core.DetectResult
import com.hostelautologin.core.PortalDiscovery
import com.hostelautologin.core.LoginEngine
import com.hostelautologin.core.LoginResult
import com.hostelautologin.core.Outcome
import com.hostelautologin.core.Platform
import com.hostelautologin.core.PortalConfig
import com.hostelautologin.core.Settings
import com.hostelautologin.core.State
import com.hostelautologin.core.probeInternet

class AndroidPlatform(
    private val context: Context,
    private val app: HostelApp,
    private val wifi: WifiTracker,
    private val onState: (State, String) -> Unit,
) : Platform {
    private val webViewFallback = WebViewFallback(context, wifi)

    override fun settings(): Settings = app.store.settings()
    override fun config(): PortalConfig = app.portal.config()
    override fun credentials() = app.store.credentials()
    override fun currentSsid() = wifi.ssid
    override fun osValidated() = wifi.validated
    override fun probe(): Boolean = wifi.client()?.let { probeInternet(it) } ?: false

    override fun login(cfg: PortalConfig, user: String, pw: String): LoginResult = loginWith(cfg, user, pw)

    fun loginWith(cfg: PortalConfig, user: String, pw: String): LoginResult {
        val client = wifi.client() ?: return LoginResult(Outcome.TRANSIENT, "not connected to Wi-Fi")
        return LoginEngine(cfg, client, webViewFallback).login(user, pw)
    }

    /** User-initiated: read the portal's login form so the user can confirm the host. */
    fun detect(): DetectResult {
        val client = wifi.client() ?: return DetectResult.Problem("Not connected to Wi-Fi.")
        return PortalDiscovery(client).detect()
    }

    override fun keepalive(cfg: PortalConfig): Boolean {
        val client = wifi.client() ?: return false
        return LoginEngine(cfg, client).keepalive()
    }

    override fun notifyFailure(message: String) {
        val n = NotificationCompat.Builder(context, HostelApp.CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("HostelAutoLogin: login failed")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(2, n)
    }

    override fun onStateChanged(state: State, message: String) {
        AppLog.i("ui state -> $state")
        onState(state, message)
    }
}
