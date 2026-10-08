"""State machine from docs/SPEC.md section 2."""
from __future__ import annotations

import enum
import logging
import threading

log = logging.getLogger(__name__)


class State(enum.Enum):
    OFFLINE = "OFFLINE"
    CONNECTED = "CONNECTED"
    VALIDATED = "VALIDATED"
    LOGIN_NEEDED = "LOGIN_NEEDED"
    AUTHENTICATING = "AUTHENTICATING"
    VERIFYING = "VERIFYING"
    FAILURE = "FAILURE"


class Event(enum.Enum):
    SSID_MATCH = enum.auto()
    SSID_LOST = enum.auto()
    PROBE_OK = enum.auto()
    PROBE_FAIL = enum.auto()
    USER_LOGIN = enum.auto()
    LOGIN_START = enum.auto()
    LOGIN_OK = enum.auto()
    LOGIN_EXHAUSTED = enum.auto()
    LOGIN_FATAL = enum.auto()
    ABORT = enum.auto()
    VERIFY_OK = enum.auto()
    VERIFY_FAIL = enum.auto()
    RESET = enum.auto()


S, E = State, Event
TABLE: dict[tuple[State, Event], State] = {
    (S.OFFLINE, E.SSID_MATCH): S.CONNECTED,
    (S.CONNECTED, E.PROBE_OK): S.VALIDATED,
    (S.CONNECTED, E.PROBE_FAIL): S.LOGIN_NEEDED,
    (S.CONNECTED, E.USER_LOGIN): S.LOGIN_NEEDED,
    (S.VALIDATED, E.PROBE_OK): S.VALIDATED,
    (S.VALIDATED, E.PROBE_FAIL): S.LOGIN_NEEDED,
    (S.VALIDATED, E.USER_LOGIN): S.LOGIN_NEEDED,
    (S.LOGIN_NEEDED, E.LOGIN_START): S.AUTHENTICATING,
    (S.LOGIN_NEEDED, E.PROBE_OK): S.VALIDATED,
    (S.LOGIN_NEEDED, E.USER_LOGIN): S.LOGIN_NEEDED,
    (S.AUTHENTICATING, E.LOGIN_START): S.AUTHENTICATING,   # retry
    (S.AUTHENTICATING, E.LOGIN_OK): S.VERIFYING,
    (S.AUTHENTICATING, E.LOGIN_EXHAUSTED): S.FAILURE,
    (S.AUTHENTICATING, E.LOGIN_FATAL): S.FAILURE,
    (S.AUTHENTICATING, E.ABORT): S.LOGIN_NEEDED,
    (S.VERIFYING, E.VERIFY_OK): S.VALIDATED,
    (S.VERIFYING, E.VERIFY_FAIL): S.LOGIN_NEEDED,
    (S.FAILURE, E.PROBE_OK): S.VALIDATED,
    (S.FAILURE, E.USER_LOGIN): S.LOGIN_NEEDED,
    (S.FAILURE, E.RESET): S.CONNECTED,
}


class StateMachine:
    def __init__(self, on_change=None):
        self._state = State.OFFLINE
        self._lock = threading.Lock()
        self._on_change = on_change

    @property
    def state(self) -> State:
        return self._state

    def handle(self, event: Event) -> bool:
        """Apply event. Returns True if it was a legal transition; illegal events are ignored."""
        with self._lock:
            old = self._state
            if event is Event.SSID_LOST:
                new = State.OFFLINE
            else:
                new = TABLE.get((old, event))
            if new is None:
                return False
            self._state = new
        if new is not old:
            log.info("state %s -> %s (%s)", old.value, new.value, event.name)
            if self._on_change:
                self._on_change(old, new)
        return True
