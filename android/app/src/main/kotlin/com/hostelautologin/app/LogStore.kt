package com.hostelautologin.app

import android.content.Context
import com.hostelautologin.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Masked log lines: in-memory ring for the UI plus a small rotating file. Lines arrive already masked from AppLog. */
object LogStore {
    private const val MAX_LINES = 500
    private const val MAX_FILE_BYTES = 256 * 1024L
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "app.log")
        file?.takeIf { it.exists() }?.readLines()?.takeLast(MAX_LINES)?.let { _lines.value = it }
        AppLog.sink = ::append
    }

    @Synchronized
    private fun append(line: String) {
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        file?.let { f ->
            if (f.length() > MAX_FILE_BYTES) f.renameTo(File(f.parentFile, "app.log.1"))
            f.appendText(line + "\n")
        }
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
        file?.writeText("")
    }

    fun asText(): String = _lines.value.joinToString("\n")
}
