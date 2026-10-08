package com.hostelautologin.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hostelautologin.app.LogStore

@Composable
fun LogsScreen() {
    val lines by LogStore.lines.collectAsState()
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Diagnostics (secrets are masked)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, LogStore.asText())
                ctx.startActivity(Intent.createChooser(send, "Share masked log"))
            }) { Text("Share") }
            OutlinedButton(onClick = { LogStore.clear() }) { Text("Clear") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(lines.asReversed()) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}
