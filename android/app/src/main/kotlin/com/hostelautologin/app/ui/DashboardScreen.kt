package com.hostelautologin.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hostelautologin.app.AppState
import com.hostelautologin.app.AutoLoginService
import com.hostelautologin.app.HostelApp
import java.text.DateFormat
import java.util.Date

@Composable
fun DashboardScreen(app: HostelApp) {
    val ui by AppState.ui.collectAsState()
    var auto by remember { mutableStateOf(app.store.settings().autoLogin) }
    Page {
        val cfgInfo = remember(ui) { try { app.portal.config() } catch (e: Exception) { null } }
        if (cfgInfo == null || cfgInfo.unconfiguredReason != null || app.store.settings().ssid.isEmpty() || !app.store.hasCredentials())
            InfoCard("Setup needed", "Open the Setup tab: enter your Wi-Fi name, username and password, then tap \"Detect my portal\".")
        else InfoCard("Portal", "${cfgInfo.scheme}://${cfgInfo.portalHost} (${cfgInfo.mode})")
        InfoCard("Wi-Fi network (SSID)", ui.ssid ?: "not connected / unreadable")
        InfoCard("State", ui.state.name + if (ui.paused) " (paused)" else "")
        InfoCard("Internet", when (ui.internetOk) { true -> "Reachable"; false -> "Not reachable"; null -> "Unknown" })
        InfoCard("Last login", ui.lastLoginMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "never")
        if (ui.message.isNotEmpty()) InfoCard("Last message", ui.message)
        if (!ui.serviceRunning) InfoCard("Service", "Not running – open the app again or check battery settings")

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Auto-login")
            Switch(checked = auto, onCheckedChange = {
                auto = it
                app.store.saveSettings(app.store.settings().copy(autoLogin = it))
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { AutoLoginService.loginNow() }) { Text("Login now") }
            OutlinedButton(onClick = { AppState.update { it.copy(paused = !it.paused) } }) { Text(if (ui.paused) "Resume" else "Pause") }
        }
    }
}
