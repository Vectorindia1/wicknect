package com.hostelautologin.core

enum class State { OFFLINE, CONNECTED, VALIDATED, LOGIN_NEEDED, AUTHENTICATING, VERIFYING, FAILURE }

enum class Event {
    SSID_MATCH, SSID_LOST, PROBE_OK, PROBE_FAIL, USER_LOGIN, LOGIN_START, LOGIN_OK,
    LOGIN_EXHAUSTED, LOGIN_FATAL, ABORT, VERIFY_OK, VERIFY_FAIL, RESET
}

private typealias S = State
private typealias E = Event

/** Same table as docs/SPEC.md section 2 and windows/hostel_autologin/statemachine.py. */
class StateMachine(private val onChange: (State, State) -> Unit = { _, _ -> }) {
    @Volatile var state: State = State.OFFLINE
        private set

    @Synchronized
    fun handle(event: Event): Boolean {
        val old = state
        val new = if (event == Event.SSID_LOST) State.OFFLINE else TABLE[old to event] ?: return false
        state = new
        if (new != old) {
            AppLog.i("state $old -> $new ($event)")
            onChange(old, new)
        }
        return true
    }

    companion object {
        val TABLE: Map<Pair<State, Event>, State> = mapOf(
            (S.OFFLINE to E.SSID_MATCH) to S.CONNECTED,
            (S.CONNECTED to E.PROBE_OK) to S.VALIDATED,
            (S.CONNECTED to E.PROBE_FAIL) to S.LOGIN_NEEDED,
            (S.CONNECTED to E.USER_LOGIN) to S.LOGIN_NEEDED,
            (S.VALIDATED to E.PROBE_OK) to S.VALIDATED,
            (S.VALIDATED to E.PROBE_FAIL) to S.LOGIN_NEEDED,
            (S.VALIDATED to E.USER_LOGIN) to S.LOGIN_NEEDED,
            (S.LOGIN_NEEDED to E.LOGIN_START) to S.AUTHENTICATING,
            (S.LOGIN_NEEDED to E.PROBE_OK) to S.VALIDATED,
            (S.LOGIN_NEEDED to E.USER_LOGIN) to S.LOGIN_NEEDED,
            (S.AUTHENTICATING to E.LOGIN_START) to S.AUTHENTICATING,
            (S.AUTHENTICATING to E.LOGIN_OK) to S.VERIFYING,
            (S.AUTHENTICATING to E.LOGIN_EXHAUSTED) to S.FAILURE,
            (S.AUTHENTICATING to E.LOGIN_FATAL) to S.FAILURE,
            (S.AUTHENTICATING to E.ABORT) to S.LOGIN_NEEDED,
            (S.VERIFYING to E.VERIFY_OK) to S.VALIDATED,
            (S.VERIFYING to E.VERIFY_FAIL) to S.LOGIN_NEEDED,
            (S.FAILURE to E.PROBE_OK) to S.VALIDATED,
            (S.FAILURE to E.USER_LOGIN) to S.LOGIN_NEEDED,
            (S.FAILURE to E.RESET) to S.CONNECTED,
        )
    }
}
