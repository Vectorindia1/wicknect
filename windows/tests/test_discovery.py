import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
import pytest
from urllib.parse import parse_qs

from hostel_autologin.config import PortalConfig, auto_config_json
from hostel_autologin.discovery import PortalDiscovery, parse_login_form
from hostel_autologin.login_engine import Outcome


class Portal:
    def __init__(self):
        self.logged_in = False
        self.loads = 0
        self.posts = []
        self.page = None
        self.after_login = None
        self.override = None
        o = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a): pass

            def _send(self, code, body="", headers=None):
                self.send_response(code)
                for k, v in (headers or {}).items():
                    self.send_header(k, v)
                self.send_header("Content-Length", str(len(body.encode())))
                self.end_headers()
                self.wfile.write(body.encode())

            def do_GET(self):
                if o.override:
                    return o.override(self)
                if self.path == "/connecttest.txt":
                    return self._send(200, "Microsoft Connect Test") if o.logged_in else self._send(302, "", {"Location": "/portal/login?x=1"})
                if self.path.startswith("/portal/login"):
                    o.loads += 1
                    return self._send(200, o.page(o.loads) if o.page else login_page(o.loads))
                self._send(404)

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
                o.posts.append(body)
                q = parse_qs(body)
                if q.get("pwd") == ["pw123"] and q.get("reg_no") == ["stud1"]:
                    o.logged_in = True
                    return self._send(302, "", {"Location": o.after_login}) if o.after_login else self._send(200, "Welcome")
                self._send(200, login_page(99, "<p>Invalid credentials</p>"))

        self.srv = HTTPServer(("127.0.0.1", 0), H)
        self.port = self.srv.server_address[1]
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    host = property(lambda self: f"127.0.0.1:{self.port}")
    probe = property(lambda self: f"http://127.0.0.1:{self.port}/connecttest.txt")

    def close(self):
        self.srv.shutdown(); self.srv.server_close()


def login_page(n, extra=""):
    return f"""<html><body>{extra}
      <form method="post" action="/portal/auth">
        <input type="hidden" name="magic" value="magic{n}">
        <input type="text" name="reg_no"><input type="password" name="pwd">
        <input type="checkbox" name="accept_terms" value="yes">
        <input type="submit" name="go" value="Login"></form></body></html>"""


@pytest.fixture
def portal():
    p = Portal(); yield p; p.close()


@pytest.fixture
def evil():
    p = Portal(); evil_hits = []
    p.override = lambda h: (evil_hits.append(h.path), h._send(200, "evil"))
    p.hits = evil_hits
    yield p
    p.close()


def disc(p): return PortalDiscovery(probe_url=p.probe, settle_seconds=0)
def cfg(host, *more): return PortalConfig.from_dict(__import__("json").loads(auto_config_json([(host, "http")] + [(m, "http") for m in more], "reg_no", "pwd")))


@pytest.fixture(autouse=True)
def probe_to_local(monkeypatch):
    # verification probe inside discovery must also hit the local server
    import hostel_autologin.probe as pr
    orig = pr.probe
    monkeypatch.setattr(pr, "probe", lambda url=None, *a, **k: orig(url) if url else orig())


def test_detect_finds_form_and_warns(portal):
    r = disc(portal).detect()
    assert r.kind == "found" and r.portal.host == portal.host
    f = r.portal.form
    assert (f.username_field, f.password_field) == ("reg_no", "pwd")
    assert any("terms" in w for w in f.warnings) and any("plain HTTP" in w for w in f.warnings)
    assert "magic" in [n for n, _ in f.fields]


def test_auto_login_sends_live_hidden_fields(portal):
    res = disc(portal).login_auto(cfg(portal.host), "stud1", "pw123")
    assert res.outcome is Outcome.SUCCESS
    q = parse_qs(portal.posts[0])
    assert q["magic"] == ["magic1"] and q["accept_terms"] == ["yes"] and q["go"] == ["Login"] and q["reg_no"] == ["stud1"]


def test_wrong_password_is_bad_credentials_once(portal):
    assert disc(portal).login_auto(cfg(portal.host), "stud1", "nope").outcome is Outcome.BAD_CREDENTIALS
    assert len(portal.posts) == 1


def test_already_online(portal):
    portal.logged_in = True
    assert disc(portal).login_auto(cfg(portal.host), "u", "p").outcome is Outcome.SUCCESS
    assert portal.posts == []


def test_not_detected_yet_makes_no_request(portal):
    c = PortalConfig.from_dict({"schema_version": 1, "mode": "auto"})
    assert disc(portal).login_auto(c, "u", "p").outcome is Outcome.CONFIG_ERROR


def test_post_login_redirect_to_foreign_host_not_followed(portal, evil):
    portal.after_login = f"http://{evil.host}/landing"
    assert disc(portal).login_auto(cfg(portal.host), "stud1", "pw123").outcome is Outcome.SUCCESS
    assert evil.hits == []


def test_portal_redirect_to_foreign_host_refused(portal, evil):
    portal.override = lambda h: (h.send_response(302), h.send_header("Location", f"http://{evil.host}/login"), h.send_header("Content-Length", "0"), h.end_headers())
    res = disc(portal).login_auto(cfg(portal.host), "stud1", "pw123")
    assert res.outcome is Outcome.CONFIG_ERROR and evil.hits == []


def test_pinned_to_other_host(portal):
    assert disc(portal).login_auto(cfg("10.9.9.9:81"), "stud1", "pw123").outcome is Outcome.CONFIG_ERROR
    assert portal.posts == []


def test_captcha_blocked_and_no_form_explained(portal):
    portal.page = lambda n: '<form action="/portal/auth" method="post"><input name="u"><input type="password" name="p"><input name="captcha"></form>'
    assert "CAPTCHA" in disc(portal).detect().message
    portal.page = lambda n: "<html><script>render()</script></html>"
    assert "browser-based" in disc(portal).detect().message


def test_js_and_meta_redirects_followed(portal):
    def ov(h):
        if h.path == "/connecttest.txt":
            return h._send(200, "<script>window.location.href='/hop'</script>")
        if h.path == "/hop":
            return h._send(200, '<meta http-equiv="refresh" content="0; url=/portal/login">')
        return h._send(200, login_page(1))
    portal.override = ov
    assert disc(portal).detect().kind == "found"


def test_parse_login_form_selects_textarea_and_get_method():
    html = '<form method="GET" action="a"><select name="dom"><option value="x" selected>X<option value="y">Y</select><input name="user"><input type="password" name="pass"></form>'
    f = parse_login_form(html, "http://h/p/")
    assert f.method == "GET" and f.action == "http://h/p/a" and ("dom", "x") in f.fields and f.username_field == "user"


def test_two_confirmed_portals_both_allowed(portal):
    c = cfg("10.9.9.9:81", portal.host)
    assert c.origins == [("10.9.9.9:81", "http"), (portal.host, "http")]
    assert disc(portal).login_auto(c, "stud1", "pw123").outcome is Outcome.SUCCESS
