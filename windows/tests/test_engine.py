import logging
import pytest
from conftest import make_cfg
from hostel_autologin.login_engine import LoginEngine, Outcome
from hostel_autologin.masking import MaskingFilter, masker

USER, PW = "stud1", "pw123"


def test_success(portal):
    r = LoginEngine(make_cfg(portal.host)).login(USER, PW)
    assert r.outcome is Outcome.SUCCESS
    m, path, body = portal.hits[-1]
    assert m == "POST" and "username=stud1" in body and "mode=191" in body


def test_bad_credentials(portal):
    r = LoginEngine(make_cfg(portal.host)).login(USER, "wrong")
    assert r.outcome is Outcome.BAD_CREDENTIALS


def test_server_error_is_transient(portal):
    portal.mode = "500"
    assert LoginEngine(make_cfg(portal.host)).login(USER, PW).outcome is Outcome.TRANSIENT


def test_connection_refused_is_transient():
    cfg = make_cfg("127.0.0.1:1")
    assert LoginEngine(cfg).login(USER, PW).outcome is Outcome.TRANSIENT


def test_redirect_same_host_followed(portal):
    portal.mode = "redirect_ok"
    cfg = make_cfg(portal.host, success={"status_in": [302], "body_contains": ["logged in"]})
    assert LoginEngine(cfg).login(USER, PW).outcome is Outcome.SUCCESS
    assert portal.hits[-1][1] == "/done"


def test_redirect_to_foreign_host_refused(portal, evil):
    portal.mode = "redirect_foreign"
    portal.redirect_to = f"http://{evil.host}/steal"
    r = LoginEngine(make_cfg(portal.host)).login(USER, PW)
    assert r.outcome is Outcome.CONFIG_ERROR
    assert evil.hits == []                      # nothing ever sent to the other host


def test_absolute_foreign_login_path_refused(evil):
    cfg = make_cfg("172.16.16.16", login={"path": f"http://{evil.host}/login", "fields": {"p": "{{password}}"}})
    assert LoginEngine(cfg).login(USER, PW).outcome is Outcome.CONFIG_ERROR
    assert evil.hits == []


def test_placeholder_config_makes_no_request(portal):
    cfg = make_cfg(portal.host, login={"path": "/REPLACE_ME", "fields": {"p": "{{password}}"}})
    r = LoginEngine(cfg).login(USER, PW)
    assert r.outcome is Outcome.CONFIG_ERROR and portal.hits == []


def test_unknown_placeholder_is_config_error(portal):
    cfg = make_cfg(portal.host, login={"path": "/login", "fields": {"x": "{{nope}}"}})
    assert LoginEngine(cfg).login(USER, PW).outcome is Outcome.CONFIG_ERROR
    assert portal.hits == []


def test_env_proxy_ignored(portal, monkeypatch):
    monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:9"); monkeypatch.setenv("http_proxy", "http://127.0.0.1:9")
    assert LoginEngine(make_cfg(portal.host)).login(USER, PW).outcome is Outcome.SUCCESS


def test_keepalive(portal):
    assert LoginEngine(make_cfg(portal.host)).keepalive() is True
    assert portal.hits[-1][1] == "/ka"


def test_fallback_hook_used_on_transient(portal):
    from hostel_autologin.login_engine import LoginResult
    portal.mode = "500"
    cfg = make_cfg(portal.host, fallback={"type": "browser"})
    r = LoginEngine(cfg, fallback=lambda c, u, p: LoginResult(Outcome.SUCCESS, "via browser")).login(USER, PW)
    assert r.detail == "via browser"


def test_password_never_in_logs(portal, caplog):
    masker.register(PW)
    h = logging.StreamHandler(); h.addFilter(MaskingFilter())
    caplog.handler.addFilter(MaskingFilter())
    with caplog.at_level(logging.DEBUG):
        LoginEngine(make_cfg(portal.host)).login(USER, PW)
        LoginEngine(make_cfg(portal.host)).login(USER, "wrong-pw-xyz")
    assert PW not in caplog.text and "wrong-pw-xyz" not in caplog.text
