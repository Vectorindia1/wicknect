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
    var reconnect by remember { mutableStateOf(app.store.settings().autoReconnect) }
    var json by remember { mutableStateOf(app.portal.text()) }
    var msg by remember { mutableStateOf("") }
    var expert by remember { mutableStateOf(false) }

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
            Text("Reconnect Wi-Fi automatically when it drops")
            Switch(checked = reconnect, onCheckedChange = {
                reconnect = it
                app.store.saveSettings(app.store.settings().copy(autoReconnect = it))
                AutoLoginService.settingsChanged()
            })
        }
        Text(
            "Android doesn't let apps force a Wi-Fi connection. On Android 10+ the app registers your Wi-Fi names as suggestions (approve the " +
                "system prompt once) and Android then rejoins them when in range. If your Wi-Fi keeps dropping: in Wi-Fi settings turn off " +
                "\"Switch to mobile data automatically\" / \"Smart network switch\" / \"Wi-Fi+\", and keep \"Connect automatically\" on for each network.",
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Log in even if the phone can't tell which Wi-Fi it is on")
            Switch(checked = unknownSsid, onCheckedChange = {
                unknownSsid = it
                app.store.saveSettings(app.store.settings().copy(loginIfSsidUnknown = it))
            })
        }

        OutlinedButton(onClick = { expert = !expert }) { Text(if (expert) "Hide expert options" else "Expert options (most people don't need these)") }
        if (expert) {
            Text("Expert: login profile", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text("The Connect tab sets this up for you. Only edit it if your Wi-Fi needs a hand-made login request; it is checked before saving.")
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
        }
        if (msg.isNotEmpty()) Text(msg)
    }
}
