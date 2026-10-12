import logging
import pytest
from hostel_autologin.masking import Masker, MaskingFilter
from hostel_autologin.pinning import HostPinError, check_url, resolve_redirect
from hostel_autologin.statemachine import Event, State, StateMachine
from hostel_autologin.wifi import parse_netsh
from hostel_autologin.config import BACKOFF, PortalConfig, ConfigError


# ---- state machine
def run(events):
    sm = StateMachine()
    for e in events:
        sm.handle(e)
    return sm.state


def test_happy_path():
    assert run([Event.SSID_MATCH, Event.PROBE_FAIL, Event.LOGIN_START, Event.LOGIN_OK, Event.VERIFY_OK]) is State.VALIDATED


def test_session_expiry_loop():
    assert run([Event.SSID_MATCH, Event.PROBE_OK, Event.PROBE_FAIL]) is State.LOGIN_NEEDED


def test_failure_is_sticky_until_user_or_ssid_change():
    s = [Event.SSID_MATCH, Event.PROBE_FAIL, Event.LOGIN_START, Event.LOGIN_FATAL]
    assert run(s) is State.FAILURE
    assert run(s + [Event.PROBE_FAIL]) is State.FAILURE
    assert run(s + [Event.LOGIN_START]) is State.FAILURE        # illegal: no auto restart
    assert run(s + [Event.USER_LOGIN]) is State.LOGIN_NEEDED
    assert run(s + [Event.SSID_LOST, Event.SSID_MATCH]) is State.CONNECTED


def test_illegal_events_ignored_and_ssid_lost_from_anywhere():
    sm = StateMachine()
    assert sm.handle(Event.LOGIN_OK) is False and sm.state is State.OFFLINE
    sm.handle(Event.SSID_MATCH); sm.handle(Event.PROBE_FAIL); sm.handle(Event.LOGIN_START)
    sm.handle(Event.SSID_LOST)
    assert sm.state is State.OFFLINE


def test_backoff_sequence():
    assert BACKOFF == (2, 5, 15, 30)


# ---- masking
def test_masker_exact_and_patterns():
    m = Masker(); m.register("hunter2pw")
    out = m.mask("pw=hunter2pw\ncookie: JSESSIONID=abc123\ntoken=xyz\nAuthorization: Bearer abcdef\nPOST /a?password=foo&x=1")
    for bad in ("hunter2pw", "abc123", "xyz", "Bearer abcdef", "foo"):
        assert bad not in out
    assert "x=1" in out
    assert "A" * 30 not in m.mask("id " + "A" * 30)


def test_logging_filter_masks_args_and_exceptions():
    m = Masker(); m.register("topsecretpw")
    rec = logging.LogRecord("x", logging.INFO, "f", 1, "login with %s", ("topsecretpw",), None)
    MaskingFilter(m).filter(rec)
    assert "topsecretpw" not in rec.getMessage()
    try:
        raise ValueError("bad topsecretpw")
    except ValueError:
        import sys
        rec = logging.LogRecord("x", logging.ERROR, "f", 1, "err", None, sys.exc_info())
    MaskingFilter(m).filter(rec)
    assert "topsecretpw" not in rec.exc_text


# ---- host pinning
OK = "http://172.16.16.16/login"


def test_pin_accepts_exact():
    assert check_url(OK, "172.16.16.16", "http") == OK
    assert check_url("http://HOST.lan:8080/x", "host.lan:8080", "http")


@pytest.mark.parametrize("url", [
    "http://evil.com/login", "http://172.16.16.16.evil.com/", "http://172.16.16.16@evil.com/",
    "http://evil.com@172.16.16.16/", "https://172.16.16.16/", "http://172.16.16.16:8080/", "http://172.16.16.17/",
    "//evil.com/x", "ftp://172.16.16.16/"])
def test_pin_rejects(url):
    with pytest.raises(HostPinError):
        check_url(url, "172.16.16.16", "http")


def test_pin_redirects():
    assert resolve_redirect(OK, "/done", "172.16.16.16", "http") == "http://172.16.16.16/done"
    with pytest.raises(HostPinError):
        resolve_redirect(OK, "http://evil.com/", "172.16.16.16", "http")
    with pytest.raises(HostPinError):
        resolve_redirect(OK, "//evil.com/", "172.16.16.16", "http")


# ---- misc
def test_parse_netsh():
    out = ("    Name : Wi-Fi\n    State : connected\n    SSID : University_Hostel\n    BSSID : aa:bb\n\n"
           "    Name : Wi-Fi 2\n    State : disconnected\n    SSID : Other\n")
    assert parse_netsh(out) == "University_Hostel"
    assert parse_netsh("    State : disconnected\n") is None


def test_placeholder_is_unconfigured_and_validation():
    import json, pathlib
    cfg = PortalConfig.from_dict(json.loads((pathlib.Path(__file__).parents[1] / "portal.json").read_text()))
    assert cfg.mode == "auto" and "Detect my portal" in cfg.unconfigured_reason          # shipped default: nothing detected yet
    manual = PortalConfig.from_dict({"schema_version": 1, "portal_host": "x", "login": {"path": "/REPLACE_ME"}})
    assert "REPLACE_ME" in manual.unconfigured_reason
    with pytest.raises(ConfigError):
        PortalConfig.from_dict({"schema_version": 1, "portal_host": "http://x/", "login": {"path": "/a"}})
    c = PortalConfig.from_dict({"schema_version": 1, "portal_host": "x", "login": {"path": "/a", "headers": {"Cookie": "a=b", "X": "1"}}})
    assert c.login["headers"] == {"X": "1"}
