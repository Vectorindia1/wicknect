package com.hostelautologin.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hostelautologin.app.HostelApp

private val TABS = listOf("Dashboard" to "◉", "Setup" to "✎", "Logs" to "☰", "Settings" to "⚙")

@Composable
fun HostelAutoLoginApp(app: HostelApp) {
    var tab by rememberSaveable { mutableStateOf(0) }
    MaterialTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    TABS.forEachIndexed { i, (label, glyph) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i },
                            icon = { Text(glyph) }, label = { Text(label) })
                    }
                }
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                when (tab) {
                    0 -> DashboardScreen(app)
                    1 -> SetupScreen(app)
                    2 -> LogsScreen()
                    else -> SettingsScreen(app)
                }
            }
        }
    }
}

@Composable
fun Page(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        content()
    }
}

@Composable
fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(body, style = MaterialTheme.typography.titleMedium)
        }
    }
}
