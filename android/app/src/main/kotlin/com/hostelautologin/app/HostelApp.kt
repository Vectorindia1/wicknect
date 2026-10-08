package com.hostelautologin.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class HostelApp : Application() {
    lateinit var store: SecureStore
        private set
    lateinit var portal: PortalStore
        private set

    override fun onCreate() {
        super.onCreate()
        LogStore.init(this)
        store = SecureStore(this)
        portal = PortalStore(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Login failures", NotificationManager.IMPORTANCE_HIGH))
    }

    companion object {
        const val CH_STATUS = "status"
        const val CH_ALERT = "alerts"
    }
}
