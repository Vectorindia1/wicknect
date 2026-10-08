"""Background monitor: probe loop, login cycles with bounded retries, keepalive."""
from __future__ import annotations

import logging
import threading
import time
from typing import Callable

from .config import BACKOFF, PortalConfig, Settings
from .login_engine import LoginResult, Outcome
from .statemachine import Event, State, StateMachine

log = logging.getLogger(__name__)
POLL_SECONDS = 15
MIN_CYCLE_GAP = 120          # SPEC: at most one automatic login cycle per 2 minutes


class Monitor:
    def __init__(self, *, get_settings: Callable[[], Settings], get_config: Callable[[], PortalConfig],
                 get_credentials: Callable[[], tuple[str, str] | None], probe: Callable[[], bool],
                 ssid: Callable[[], str | None], login: Callable[[PortalConfig, str, str], LoginResult],
                 keepalive: Callable[[PortalConfig], bool] | None = None,
                 notify: Callable[[str, str], None] = lambda t, m: None,
                 wait: Callable[[float], bool] | None = None, clock: Callable[[], float] = time.monotonic,
                 verify_delay: float = 1.5, on_state: Callable[[State], None] | None = None):
        self.get_settings, self.get_config, self.get_credentials = get_settings, get_config, get_credentials
        self._probe, self._ssid, self._login, self._keepalive = probe, ssid, login, keepalive
        self.notify = notify
        self.stop_event = threading.Event()
        self.wait = wait or self.stop_event.wait
        self.clock = clock
        self.verify_delay = verify_delay
        self.paused = False
        self.last_login: float | None = None        # wall-clock time.time()
        self.last_message = ""
        self.internet_ok: bool | None = None
        self.current_ssid: str | None = None
        self._last_cycle = -1e9
        self._last_keepalive = clock()
        self._cycle_lock = threading.Lock()
        self.on_state = on_state
        self.sm = StateMachine(on_change=lambda o, n: self.on_state and self.on_state(n))

    # -- status -------------------------------------------------------------------------------
    @property
    def state(self) -> State:
        return self.sm.state

    @property
    def status_text(self) -> str:
        s = self.state.value + (" (paused)" if self.paused else "")
        return f"{s}: {self.last_message}" if self.last_message else s

    # -- ticks --------------------------------------------------------------------------------
    def tick(self) -> None:
        st = self.get_settings()
        ssid = self._ssid()
        self.current_ssid = ssid
        on_target = (ssid == st.ssid) or (ssid is None and st.login_if_ssid_unknown)
        if not on_target:
            self.internet_ok = None
            self.sm.handle(Event.SSID_LOST)
            return
        self.sm.handle(Event.SSID_MATCH)
        ok = self._probe()
        self.internet_ok = ok
        if ok:
            self.sm.handle(Event.PROBE_OK)
            self._maybe_keepalive()
            return
        if self.state is State.FAILURE:
            return                                   # wait for user action; no endless attempts
        self.sm.handle(Event.PROBE_FAIL)
        if (self.state is State.LOGIN_NEEDED and st.auto_login and not self.paused
                and self.clock() - self._last_cycle >= MIN_CYCLE_GAP):
            self.run_login_cycle()

    def _maybe_keepalive(self) -> None:
        cfg = self.get_config()
        ka = cfg.keepalive
        if not (ka and self._keepalive and self.state is State.VALIDATED):
            return
        if self.clock() - self._last_keepalive >= max(30, int(ka.get("interval_seconds", 300))):
            self._last_keepalive = self.clock()
            self._keepalive(cfg)

    # -- login cycle --------------------------------------------------------------------------
    def login_now(self) -> None:
        """User-initiated: allowed from FAILURE, ignores pause and the rate guard."""
        st = self.get_settings()
        ssid = self._ssid()
        if not (ssid == st.ssid or (ssid is None and st.login_if_ssid_unknown)):
            self.notify("HostelAutoLogin", f"Not connected to '{st.ssid}'.")
            return
        self.sm.handle(Event.SSID_MATCH)
        self.sm.handle(Event.USER_LOGIN)
        self.run_login_cycle(user_initiated=True)

    def settings_changed(self) -> None:
        self.sm.handle(Event.RESET)

    def run_login_cycle(self, user_initiated: bool = False) -> bool:
        if not self._cycle_lock.acquire(blocking=False):
            return False
        try:
            self._last_cycle = self.clock()
            return self._cycle(user_initiated)
        finally:
            self._cycle_lock.release()

    def _fail(self, message: str, event: Event) -> bool:
        self.last_message = message
        self.sm.handle(event)
        log.error("login failed: %s", message)
        self.notify("HostelAutoLogin: login failed", message)
        return False

    def _cycle(self, user_initiated: bool) -> bool:
        try:
            cfg = self.get_config()
        except Exception as e:                                        # ConfigError
            self.sm.handle(Event.LOGIN_START)
            return self._fail(str(e), Event.LOGIN_FATAL)
        creds = self.get_credentials()
        if not creds:
            self.sm.handle(Event.LOGIN_START)
            return self._fail("No stored credentials. Open Settings to add them.", Event.LOGIN_FATAL)
        user, pw = creds
        last = ""
        for attempt in range(len(BACKOFF) + 1):
            if attempt:
                if self.wait(BACKOFF[attempt - 1]):
                    return False                                      # shutting down
                if self.paused and not user_initiated:
                    self.sm.handle(Event.ABORT)
                    return False
            self.sm.handle(Event.LOGIN_START)
            res = self._login(cfg, user, pw)
            last = res.detail
            self.last_message = f"attempt {attempt + 1}: {res.detail}"
            log.info("login attempt %d -> %s", attempt + 1, res.outcome.value)
            if res.fatal:
                return self._fail(res.detail, Event.LOGIN_FATAL)
            if res.outcome is Outcome.SUCCESS:
                self.sm.handle(Event.LOGIN_OK)
                if self.verify_delay and self.wait(self.verify_delay):
                    return False
                if self._probe():
                    self.internet_ok = True
                    self.last_login = time.time()
                    self.last_message = "logged in"
                    self.sm.handle(Event.VERIFY_OK)
                    return True
                last = "login accepted but internet still not reachable"
                self.sm.handle(Event.VERIFY_FAIL)
        return self._fail(f"gave up after {len(BACKOFF) + 1} attempts: {last}", Event.LOGIN_EXHAUSTED) \
            if self.state is State.AUTHENTICATING else self._fail_verify(last)

    def _fail_verify(self, last: str) -> bool:
        # last attempt ended in VERIFY_FAIL (state LOGIN_NEEDED): go through AUTHENTICATING to reach FAILURE
        self.sm.handle(Event.LOGIN_START)
        return self._fail(f"gave up after {len(BACKOFF) + 1} attempts: {last}", Event.LOGIN_EXHAUSTED)

    # -- thread -------------------------------------------------------------------------------
    def run(self) -> None:
        while not self.stop_event.is_set():
            try:
                self.tick()
            except Exception:                                         # keep the loop alive
                log.exception("monitor tick failed")
            if self.stop_event.wait(POLL_SECONDS):
                break

    def start(self) -> threading.Thread:
        t = threading.Thread(target=self.run, name="monitor", daemon=True)
        t.start()
        return t

    def stop(self) -> None:
        self.stop_event.set()
