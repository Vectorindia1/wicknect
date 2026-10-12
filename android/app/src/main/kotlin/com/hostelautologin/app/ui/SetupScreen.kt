package com.hostelautologin.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hostelautologin.app.AppState
import com.hostelautologin.app.AutoLoginService
import com.hostelautologin.app.HostelApp
import com.hostelautologin.core.DetectResult
import com.hostelautologin.core.DetectedPortal
import com.hostelautologin.core.Masker
import com.hostelautologin.core.Origin
import com.hostelautologin.core.PortalConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SetupScreen(app: HostelApp) {
    val saved = remember { app.store.settings() }
    val ui by AppState.ui.collectAsState()
    var ssidsText by remember { mutableStateOf(saved.ssids.joinToString("\n")) }
    fun ssidList() = ssidsText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    var user by remember { mutableStateOf(saved.username) }
    var pw by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<DetectedPortal?>(null) }
    val hasPw = remember { app.store.password() != null }
    val scope = rememberCoroutineScope()

    fun persist() {
        app.store.saveSettings(app.store.settings().copy(ssids = ssidList(), username = user.trim()))
        if (pw.isNotEmpty()) { app.store.savePassword(pw); pw = "" }
        AutoLoginService.settingsChanged()
    }

    found?.let { p ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text("Send your login to ${p.host}?") },
            text = { Text(p.summary + "\n\nYour username and password will only ever be sent to this address. Only continue if it is your Wi-Fi's login page.") },
            confirmButton = {
                TextButton(onClick = {
                    val existing = try { app.portal.config().origins } catch (e: Exception) { emptyList() }
                    val merged = (existing + Origin(p.host, p.scheme)).distinct()          // several networks can use different login pages
                    app.portal.save(PortalConfig.autoJson(merged, p.form.usernameField, p.form.passwordField))
                    persist(); status = "Portal saved: ${p.scheme}://${p.host}. Now tap Test Login."; found = null
                }) { Text("Yes, use this portal") }
            },
            dismissButton = { TextButton(onClick = { found = null }) { Text("Cancel") } },
        )
    }

    Page {
        Text("1. Add your Wi-Fi names. Networks that use the same username and password (for example the hostel Wi-Fi and the college Wi-Fi) all go in the list.\n2. Enter your own username and password.\n3. While connected to each network, tap Detect my portal, then Test Login.\n\nCredentials are stored encrypted on this phone and only sent to the login-page addresses you confirm.")
        OutlinedTextField(ssidsText, { ssidsText = it }, label = { Text("Wi-Fi names, one per line") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = {
            val cur = ui.ssid
            if (cur == null) status = "Can't read the Wi-Fi name. Connect to Wi-Fi and allow location permission, or type it."
            else if (cur !in ssidList()) ssidsText = (ssidList() + cur).joinToString("\n")
        }) { Text("Add current Wi-Fi" + (ui.ssid?.let { " ($it)" } ?: "")) }
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pw, { pw = it }, label = { Text(if (hasPw) "Password (blank = keep saved)" else "Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { persist(); status = "Saved." }) { Text("Save") }
            Button(enabled = !busy, onClick = {
                busy = true; status = "Looking for your portal's login page…"
                scope.launch {
                    val r = withContext(Dispatchers.IO) { AutoLoginService.detectPortal() }
                    when (r) {
                        is DetectResult.Found -> { found = r.portal; status = "" }
                        is DetectResult.AlreadyOnline -> status = "You already have internet, so there is no login page right now. Disconnect and reconnect to the Wi-Fi (or wait for the session to expire) and try again."
                        is DetectResult.Problem -> status = r.message
                    }
                    busy = false
                }
            }) { Text("Detect my portal") }
        }
        OutlinedButton(enabled = !busy, onClick = {
            val p = pw.ifEmpty { app.store.password().orEmpty() }
            if (user.isBlank() || p.isEmpty()) { status = "Enter username and password first."; return@OutlinedButton }
            Masker.global.register(p)
            busy = true; status = "Testing… (you must be on your Wi-Fi)"
            scope.launch {
                val r = withContext(Dispatchers.IO) { AutoLoginService.testLogin(user.trim(), p) }
                status = "${r.outcome}: ${Masker.global.mask(r.detail)}"
                busy = false
            }
        }) { Text("Test Login") }
        if (status.isNotEmpty()) Text(status)
    }
}
