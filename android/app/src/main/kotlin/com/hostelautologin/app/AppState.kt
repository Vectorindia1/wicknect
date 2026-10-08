package com.hostelautologin.app

import com.hostelautologin.core.State
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class UiState(
    val state: State = State.OFFLINE,
    val ssid: String? = null,
    val internetOk: Boolean? = null,
    val paused: Boolean = false,
    val lastLoginMillis: Long? = null,
    val message: String = "",
    val serviceRunning: Boolean = false,
)

/** Single source of truth the Compose UI observes; written only by AutoLoginService. */
object AppState {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    fun update(f: (UiState) -> UiState) { _ui.value = f(_ui.value) }
}
