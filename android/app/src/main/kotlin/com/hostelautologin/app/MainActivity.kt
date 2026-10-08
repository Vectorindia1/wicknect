package com.hostelautologin.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.hostelautologin.app.ui.HostelAutoLoginApp

class MainActivity : ComponentActivity() {
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)          // required by Android to read the Wi-Fi SSID
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        perms.launch(needed.toTypedArray())
        AutoLoginService.start(this)
        val app = application as HostelApp
        setContent { HostelAutoLoginApp(app) }
    }
}
