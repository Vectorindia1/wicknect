from hostel_autologin.config import BACKOFF, Settings
from hostel_autologin.login_engine import LoginResult, Outcome
from hostel_autologin.monitor import Monitor
from hostel_autologin.statemachine import State
from conftest import make_cfg


class Env:
    def __init__(self, results, probes=None, ssid="University_Hostel"):
        self.results = list(results); self.probes = list(probes or []); self.default_probe = False
        self.waits = []; self.notes = []; self.logins = 0; self.ssid = ssid; self.t = 0.0
        self.disconnected = False; self.reconnects = []
        self.m = Monitor(get_settings=lambda: Settings(ssids=["University_Hostel", "GDGU student"], username="u"),
                         get_config=lambda: make_cfg("172.16.16.16"), get_credentials=lambda: ("u", "p"),
                         probe=self.probe, ssid=lambda: self.ssid, login=self.login,
                         keepalive=lambda c: self.notes.append("ka") or True,
                         notify=lambda t, m: self.notes.append((t, m)), wait=self.wait, clock=lambda: self.t,
                         verify_delay=0, disconnected=lambda: self.disconnected,
                         reconnect=lambda ss: self.reconnects.append(ss) or True)

    def probe(self): return self.probes.pop(0) if self.probes else self.default_probe
    def wait(self, s): self.waits.append(s); return False

    def login(self, cfg, u, p):
        self.logins += 1
        return self.results.pop(0)


T = LoginResult(Outcome.TRANSIENT, "timeout")
S = LoginResult(Outcome.SUCCESS, "ok")


def test_retries_follow_backoff_then_failure():
    e = Env([T] * 10)
    e.m.tick()
    assert e.m.state is State.FAILURE
    assert e.logins == 5 and e.waits == list(BACKOFF)
    assert any(n[0].startswith("Wify: login failed") for n in e.notes if isinstance(n, tuple))


def test_no_more_attempts_after_failure():
    e = Env([T] * 20)
    e.m.tick(); n = e.logins
    for _ in range(5):
        e.t += 1000; e.m.tick()
    assert e.logins == n and e.m.state is State.FAILURE


def test_bad_credentials_single_attempt_no_retry():
    e = Env([LoginResult(Outcome.BAD_CREDENTIALS, "rejected")] * 5)
    e.m.tick()
    assert e.logins == 1 and e.waits == [] and e.m.state is State.FAILURE


def test_success_then_verified():
    e = Env([S], probes=[False, True])      # first probe fails, verify probe ok
    e.m.tick()
    assert e.m.state is State.VALIDATED and e.m.last_login is not None


def test_transient_then_success():
    e = Env([T, S], probes=[False, True])
    e.m.tick()
    assert e.m.state is State.VALIDATED and e.waits == [2]


def test_verify_never_ok_is_bounded():
    e = Env([S] * 10)                        # login "succeeds" but internet never comes
    e.m.tick()
    assert e.m.state is State.FAILURE and e.logins == 2     # stops after two accepted-but-offline results


def test_other_ssid_does_nothing():
    e = Env([S], ssid="Other")
    e.m.tick()
    assert e.m.state is State.OFFLINE and e.logins == 0


def test_validated_does_nothing_and_keepalive():
    e = Env([]); e.default_probe = True
    e.m.tick(); assert e.m.state is State.VALIDATED and e.logins == 0
    e.t += 60; e.m.tick()
    assert "ka" in e.notes


def test_paused_blocks_auto_login_but_not_login_now():
    e = Env([S], probes=[False, True]); e.m.paused = True
    e.m.tick(); assert e.logins == 0 and e.m.state is State.LOGIN_NEEDED
    e.m.login_now(); assert e.logins == 1 and e.m.state is State.VALIDATED


def test_rate_guard_between_automatic_cycles():
    e = Env([S] * 3, probes=[False, True])   # logs in, validated
    e.m.tick(); assert e.logins == 1
    e.default_probe = False; e.t += 10       # session dies right away
    e.m.tick(); assert e.logins == 1         # guard: <120s since last cycle
    e.t += 200; e.results = [S]; e.probes = [False, True]; e.m.tick()
    assert e.logins == 2


def test_login_now_recovers_from_failure():
    e = Env([LoginResult(Outcome.BAD_CREDENTIALS, "x"), S], probes=[False, True])
    e.m.tick(); assert e.m.state is State.FAILURE
    e.m.login_now(); assert e.m.state is State.VALIDATED


def test_missing_credentials_fails_without_login():
    e = Env([S]); e.m.get_credentials = lambda: None
    e.m.tick(); assert e.logins == 0 and e.m.state is State.FAILURE


def test_any_configured_ssid_accepted():
    e = Env([S], probes=[False, True], ssid="GDGU student")
    e.m.tick()
    assert e.m.state is State.VALIDATED


def test_reconnects_when_wifi_drops_with_backoff():
    e = Env([]); e.disconnected = True
    e.m.tick(); assert e.reconnects == [["University_Hostel", "GDGU student"]]
    e.t += 5; e.m.tick(); assert len(e.reconnects) == 1          # too soon
    e.t += 10; e.m.tick(); assert len(e.reconnects) == 2
    e.t += 30; e.m.tick(); assert len(e.reconnects) == 3
    for _ in range(10):
        e.t += 301; e.m.tick()
    assert sum("still disconnected" in n[1] for n in e.notes if isinstance(n, tuple)) == 1
    e.disconnected = False; e.default_probe = True; e.t += 1; e.m.tick()
    assert e.m.state is State.VALIDATED


def test_other_network_left_alone():
    e = Env([], ssid="Phone hotspot")
    e.m.tick()
    assert e.reconnects == [] and e.m.state is State.OFFLINE
