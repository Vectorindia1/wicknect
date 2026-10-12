package com.hostelautologin.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hostelautologin.app.AutoLoginService
import com.hostelautologin.app.BatteryHelp
import com.hostelautologin.app.HostelApp
import com.hostelautologin.core.ConfigException
import com.hostelautologin.core.PortalConfig

@Composable
fun SettingsScreen(app: HostelApp) {
    val ctx = LocalContext.current
    var exempt by remember { mutableStateOf(BatteryHelp.isExempt(ctx)) }
    var unknownSsid by remember { mutableStateOf(app.store.settings().loginIfSsidUnknown) }
    var json by remember { mutableStateOf(app.portal.text()) }
    var msg by remember { mutableStateOf("") }

    Page {
        Text("Reliability", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Text(if (exempt) "Battery optimization: exempt ✔" else "Battery optimization is ON – Android may stop the service in the background.")
        Button(onClick = { BatteryHelp.requestExemption(ctx); exempt = BatteryHelp.isExempt(ctx) }) { Text("Exempt from battery optimization") }
        Text(
            "Xiaomi / Redmi / POCO (MIUI, HyperOS): open Autostart and enable HostelAutoLogin, then in the app's Battery saver " +
                "choose \"No restrictions\", and lock the app in the Recents screen (long-press the card → lock)." +
                if (BatteryHelp.isXiaomi) "  (This looks like a Xiaomi device.)" else "",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { BatteryHelp.openAutostart(ctx) }) { Text("Open Autostart") }
            OutlinedButton(onClick = { BatteryHelp.openAppSettings(ctx) }) { Text("App settings") }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Log in even if the SSID can't be read")
            Switch(checked = unknownSsid, onCheckedChange = {
                unknownSsid = it
                app.store.saveSettings(app.store.settings().copy(loginIfSsidUnknown = it))
            })
        }

        Text("Advanced: portal.json", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Text("Normally set by \"Detect my portal\" in Setup. Only edit this if your portal needs a hand-made login request (see README); it is validated before saving.")
        OutlinedTextField(json, { json = it }, modifier = Modifier.fillMaxWidth(), minLines = 10)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                msg = try {
                    PortalConfig.parse(json)
                    app.portal.save(json); AutoLoginService.settingsChanged(); "Saved."
                } catch (e: ConfigException) { "Invalid: ${e.message}" }
            }) { Text("Save") }
            OutlinedButton(onClick = { app.portal.reset(); json = app.portal.text(); msg = "Reset to automatic mode." }) { Text("Reset") }
        }
        if (msg.isNotEmpty()) Text(msg)
    }
}
