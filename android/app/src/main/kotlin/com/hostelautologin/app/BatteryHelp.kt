package com.hostelautologin.app

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object BatteryHelp {
    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    @SuppressLint("BatteryLife")   // legitimate: a foreground connectivity service the user asked for
    fun requestExemption(context: Context) {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    val isXiaomi get() = Build.MANUFACTURER.equals("xiaomi", true) || Build.MANUFACTURER.equals("redmi", true) ||
        Build.MANUFACTURER.equals("poco", true)

    /** Opens MIUI's Autostart screen, falling back to this app's system settings page. */
    fun openAutostart(context: Context) {
        val miui = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(miui) } catch (e: Exception) { openAppSettings(context) }
    }

    fun openAppSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
