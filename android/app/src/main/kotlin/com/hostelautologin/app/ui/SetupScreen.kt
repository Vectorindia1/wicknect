package com.hostelautologin.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hostelautologin.app.AutoLoginService
import com.hostelautologin.app.HostelApp
import com.hostelautologin.core.Masker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SetupScreen(app: HostelApp) {
    val saved = remember { app.store.settings() }
    var ssid by remember { mutableStateOf(saved.ssid) }
    var user by remember { mutableStateOf(saved.username) }
    var pw by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val hasPw = remember { app.store.password() != null }
    val scope = rememberCoroutineScope()

    Page {
        Text("Your own hostel Wi-Fi credentials. They are stored encrypted on this phone (Android Keystore) and are only ever sent to the portal host configured in portal.json.")
        OutlinedTextField(ssid, { ssid = it }, label = { Text("Wi-Fi SSID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pw, { pw = it }, label = { Text(if (hasPw) "Password (blank = keep saved)" else "Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                app.store.saveSettings(app.store.settings().copy(ssid = ssid.trim(), username = user.trim()))
                if (pw.isNotEmpty()) app.store.savePassword(pw)
                pw = ""
                AutoLoginService.settingsChanged()
                status = "Saved."
            }) { Text("Save") }
            OutlinedButton(enabled = !busy, onClick = {
                val p = pw.ifEmpty { app.store.password().orEmpty() }
                if (user.isBlank() || p.isEmpty()) { status = "Enter username and password first."; return@OutlinedButton }
                Masker.global.register(p)
                busy = true; status = "Testing… (you must be on the hostel Wi-Fi)"
                scope.launch {
                    val r = withContext(Dispatchers.IO) { AutoLoginService.testLogin(user.trim(), p) }
                    status = "${r.outcome}: ${Masker.global.mask(r.detail)}"
                    busy = false
                }
            }) { Text("Test Login") }
        }
        if (status.isNotEmpty()) Text(status)
    }
}
