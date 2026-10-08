package com.hostelautologin.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as HostelApp
        if (app.store.settings().autoLogin && app.store.hasCredentials()) AutoLoginService.start(context)
    }
}
