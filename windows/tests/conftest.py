import json, sys, threading, pathlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))


class FakePortal:
    """Local mock portal. Behaviour is set per test via .mode."""

    def __init__(self):
        self.hits = []          # (method, path, body)
        self.mode = "ok"
        self.redirect_to = None
        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a): pass

            def _do(self, method):
                n = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(n).decode() if n else ""
                outer.hits.append((method, self.path, body))
                if self.path.startswith("/login"):
                    if outer.mode == "ok" and "password=pw123" in body:
                        return self._send(200, "Welcome, you are now connected")
                    if outer.mode == "badcreds" or "password=pw123" not in body:
                        return self._send(200, "Invalid username or password")
                    if outer.mode == "500":
                        return self._send(500, "boom")
                    if outer.mode == "redirect_foreign":
                        self.send_response(302); self.send_header("Location", outer.redirect_to); self.end_headers(); return
                    if outer.mode == "redirect_ok":
                        self.send_response(302); self.send_header("Location", "/done"); self.end_headers(); return
                if self.path == "/done":
                    return self._send(200, "logged in")
                if self.path == "/ka":
                    return self._send(200, "alive")
                self._send(404, "nf")

            def _send(self, code, text):
                self.send_response(code); self.send_header("Content-Length", str(len(text))); self.end_headers()
                self.wfile.write(text.encode())

            def do_GET(self): self._do("GET")
            def do_POST(self): self._do("POST")

        self.srv = HTTPServer(("127.0.0.1", 0), H)
        self.port = self.srv.server_address[1]
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    @property
    def host(self): return f"127.0.0.1:{self.port}"

    def close(self): self.srv.shutdown(); self.srv.server_close()


@pytest.fixture
def portal():
    p = FakePortal(); yield p; p.close()


@pytest.fixture
def evil():
    p = FakePortal(); yield p; p.close()


def make_cfg(host, **over):
    from hostel_autologin.config import PortalConfig
    d = {"schema_version": 1, "portal_host": host, "scheme": "http",
         "login": {"path": "/login", "method": "POST", "content_type": "form",
                   "fields": {"username": "{{username}}", "password": "{{password}}", "mode": "191"}},
         "success": {"status_in": [200], "body_contains": ["connected", "logged in"], "body_not_contains": ["Invalid"]},
         "bad_credentials": {"body_contains": ["Invalid username"]},
         "keepalive": {"path": "/ka", "interval_seconds": 30}}
    d.update(over)
    return PortalConfig.from_dict(d)
