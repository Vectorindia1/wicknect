package com.hostelautologin.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hostelautologin.core.AppLog
import com.hostelautologin.core.Controller
import com.hostelautologin.core.LoginResult
import com.hostelautologin.core.Outcome
import com.hostelautologin.core.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Foreground service: watches the Wi-Fi network and keeps the portal session logged in. */
class AutoLoginService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private lateinit var wifi: WifiTracker
    private lateinit var platform: AndroidPlatform
    private lateinit var controller: Controller

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as HostelApp
        wifi = WifiTracker(this) { wake.trySend(Unit) }
        platform = AndroidPlatform(this, app, wifi) { s, m -> publish(s, m) }
        controller = Controller(platform)
        instance = this
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        wifi.start()
        AppState.update { it.copy(serviceRunning = true) }
        AppLog.i("service started")
        scope.launch {
            while (isActive) {
                try {
                    controller.paused = AppState.ui.value.paused
                    controller.tick()
                    publish(controller.state, controller.message)
                } catch (e: Exception) {
                    AppLog.e("tick failed: ${e.javaClass.simpleName}")
                }
                withTimeoutOrNull(POLL_MS) { wake.receive() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        instance = null
        wifi.stop()
        scope.cancel()
        AppState.update { it.copy(serviceRunning = false) }
        AppLog.i("service stopped")
        super.onDestroy()
    }

    private fun publish(state: State, message: String) {
        AppState.update {
            it.copy(state = state, ssid = wifi.ssid, internetOk = controller.internetOk,
                lastLoginMillis = controller.lastLoginMillis, message = message)
        }
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIF_ID, notification("$state${if (message.isNotEmpty()) " – $message" else ""}"))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, HostelApp.CH_STATUS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("HostelAutoLogin")
            .setContentText(text.take(120))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).build()
    }

    private fun loginNow() { scope.launch { controller.loginNow(); publish(controller.state, controller.message) } }

    private fun settingsChanged() { controller.settingsChanged(); wake.trySend(Unit) }

    private fun testLogin(user: String, pw: String): LoginResult = try {
        platform.loginWith(platform.config(), user, pw)
    } catch (e: Exception) { LoginResult(Outcome.CONFIG_ERROR, e.message ?: "config error") }

    companion object {
        private const val NOTIF_ID = 1
        private const val POLL_MS = 15_000L
        @Volatile private var instance: AutoLoginService? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AutoLoginService::class.java))
        }

        fun loginNow() { instance?.loginNow() }
        fun settingsChanged() { instance?.settingsChanged() }
        /** Blocking; call from a background thread. */
        fun testLogin(user: String, pw: String): LoginResult =
            instance?.testLogin(user, pw) ?: LoginResult(Outcome.CONFIG_ERROR, "service is not running")
    }
}
