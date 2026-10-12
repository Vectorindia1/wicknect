package com.hostelautologin.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hostelautologin.app.AppState
import com.hostelautologin.app.AutoLoginService
import com.hostelautologin.app.BatteryHelp
import com.hostelautologin.app.HostelApp
import com.hostelautologin.core.DetectResult
import com.hostelautologin.core.DetectedPortal
import com.hostelautologin.core.Friendly
import com.hostelautologin.core.LoginResult
import com.hostelautologin.core.Masker
import com.hostelautologin.core.Origin
import com.hostelautologin.core.Outcome
import com.hostelautologin.core.PortalConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-screen setup for ordinary users: Wi-Fi name(s), username, password, one button. */
@Composable
fun SetupScreen(app: HostelApp) {
    val ctx = LocalContext.current
    val saved = remember { app.store.settings() }
    val ui by AppState.ui.collectAsState()
    var ssidsText by remember { mutableStateOf(saved.ssids.joinToString("\n")) }
    var user by remember { mutableStateOf(saved.username) }
    var pw by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var success by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<DetectedPortal?>(null) }
    var exempt by remember { mutableStateOf(BatteryHelp.isExempt(ctx)) }
    val hasPw = remember { app.store.password() != null }
    val scope = rememberCoroutineScope()

    fun ssidList() = ssidsText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    // Pre-fill the network the phone is connected to right now.
    LaunchedEffect(ui.ssid) { if (ssidsText.isBlank()) ui.ssid?.let { ssidsText = it } }

    fun persist() {
        app.store.saveSettings(app.store.settings().copy(ssids = ssidList(), username = user.trim()))
        if (pw.isNotEmpty()) app.store.savePassword(pw)
        AutoLoginService.settingsChanged()
    }

    suspend fun detect(): DetectResult = withContext(Dispatchers.IO) {
        var r = AutoLoginService.detectPortal()
        var tries = 0
        while (r is DetectResult.Problem && r.message.startsWith("Service is not running") && tries++ < 6) { delay(1000); r = AutoLoginService.detectPortal() }
        r
    }

    suspend fun test(allowRedetect: Boolean) {
        status = "Logging in…"
        val p = app.store.password().orEmpty()
        val r: LoginResult = withContext(Dispatchers.IO) {
            var x = AutoLoginService.testLogin(user.trim(), p)
            var tries = 0
            while (x.detail.startsWith("service is not running") && tries++ < 6) { delay(1000); x = AutoLoginService.testLogin(user.trim(), p) }
            x
        }
        // The login page may have moved, or this is a second network with its own page: look for it once.
        if (allowRedetect && r.outcome == Outcome.CONFIG_ERROR && ("refused" in r.detail || "not set up" in r.detail.lowercase())) {
            when (val d = detect()) {
                is DetectResult.Found -> { found = d.portal; status = ""; return }
                else -> {}
            }
        }
        success = r.outcome == Outcome.SUCCESS
        status = Friendly.login(LoginResult(r.outcome, Masker.global.mask(r.detail)))
    }

    fun connect() {
        val ssids = ssidList()
        when {
            ssids.isEmpty() -> { success = false; status = "Please type the name of your Wi-Fi first."; return }
            user.isBlank() -> { success = false; status = "Please type your username."; return }
            pw.isEmpty() && !hasPw -> { success = false; status = "Please type your password."; return }
        }
        Masker.global.register(pw)
        persist()
        busy = true; success = null; status = "Looking for your Wi-Fi's login page…"
        scope.launch {
            val cfgOk = try { app.portal.config().unconfiguredReason == null } catch (e: Exception) { false }
            if (!cfgOk) {
                when (val d = detect()) {
                    is DetectResult.Found -> { found = d.portal; status = "" }
                    else -> { success = false; status = Friendly.detect(d) }
                }
            } else test(allowRedetect = true)
            busy = false
        }
    }

    found?.let { p ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text("Is this your Wi-Fi's login page?") },
            text = {
                Text("We found a login page at ${p.host}.\n\nIf this is the page you normally log in on, tap Yes. Your username and password will only ever be sent to this address." +
                    (if (p.scheme == "http") "\n\nNote: this page is not encrypted, so other people on the same Wi-Fi could see your password. That is how your Wi-Fi works, not something this app controls." else ""))
            },
            confirmButton = {
                TextButton(onClick = {
                    val existing = try { app.portal.config().origins } catch (e: Exception) { emptyList() }
                    app.portal.save(PortalConfig.autoJson((existing + Origin(p.host, p.scheme)).distinct(), p.form.usernameField, p.form.passwordField))
                    found = null; busy = true
                    scope.launch { test(allowRedetect = false); busy = false }
                }) { Text("Yes, continue") }
            },
            dismissButton = { TextButton(onClick = { found = null; status = "Okay, nothing was saved." }) { Text("No") } },
        )
    }

    Page {
        Text("Get connected", style = MaterialTheme.typography.headlineSmall)
        Text("1. Connect your phone to your Wi-Fi.\n2. Type your own Wi-Fi login details below.\n3. Tap Connect. That's it.")
        OutlinedTextField(ssidsText, { ssidsText = it }, label = { Text("Wi-Fi name") }, minLines = 1, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Several Wi-Fi names with the same login (hostel, college…)? Put each one on its own line.") })
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pw, { pw = it }, label = { Text(if (hasPw) "Password (leave empty to keep the saved one)" else "Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy, onClick = { connect() }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Please wait…" else "Connect") }
        if (status.isNotEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                containerColor = when (success) { true -> MaterialTheme.colorScheme.primaryContainer; false -> MaterialTheme.colorScheme.errorContainer; null -> MaterialTheme.colorScheme.surfaceVariant })) {
                Column(Modifier.padding(16.dp)) { Text(status) }
            }
        }
        if (success == true && !exempt) {
            Text("One last thing: let the app keep running in the background so it can log you in again by itself.")
            OutlinedButton(onClick = { BatteryHelp.requestExemption(ctx); exempt = BatteryHelp.isExempt(ctx) }) { Text("Allow background running") }
        }
    }
}
