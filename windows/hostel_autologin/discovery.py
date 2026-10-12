"""Automatic mode: read the captive portal's own login form (like a browser does) and submit the user's credentials.

Python twin of android/core/.../PortalDiscovery.kt. Credentials are only sent to the portal host the user
confirmed during detect() (stored as portal_host) - every redirect hop and the form action are pin-checked.
"""
from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass, field
from html.parser import HTMLParser
from urllib.parse import urljoin, urlsplit

import requests

from .config import PROBE_BODY, PROBE_URL
from .login_engine import LoginResult, Outcome
from .pinning import HostPinError, check_url

log = logging.getLogger(__name__)
MAX_HOPS = 6
MAX_BODY = 200_000
TIMEOUT = (5, 10)

USER = re.compile(r"user|login|uname|email|account|acct|phone|mobile|reg|roll|(^|[^a-z])id($|[^a-z])", re.I)
CAPTCHA = re.compile(r"captcha|otp|verif|security.?code|sms", re.I)
TERMS = re.compile(r"accept|agree|terms|tos|policy|consent", re.I)
TEXTLIKE = {"", "text", "email", "tel", "number"}
META_REFRESH = re.compile(r"""<meta[^>]+http-equiv\s*=\s*["']?refresh[^>]+url\s*=\s*['"]?([^'">\s]+)""", re.I)
JS_REDIRECT = re.compile(r"""(?:window\.|document\.|top\.)?location(?:\.href)?\s*(?:=|\.replace\(|\.assign\()\s*["']([^"']+)["']""", re.I)
PASSWORD_INPUT = re.compile(r"""type\s*=\s*["']?password""", re.I)


def has_password_field(html: str) -> bool:
    return bool(PASSWORD_INPUT.search(html))


@dataclass
class LoginForm:
    action: str
    method: str
    fields: list[tuple[str, str]]          # what the browser would submit, minus credentials
    username_field: str | None
    password_field: str
    warnings: list[str] = field(default_factory=list)
    blocked: str | None = None             # CAPTCHA/OTP etc. - cannot be automated


class _Forms(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.forms: list[dict] = []
        self._form: dict | None = None
        self._select: dict | None = None
        self._textarea: dict | None = None

    def handle_starttag(self, tag, attrs):
        a = {k.lower(): (v if v is not None else "") for k, v in attrs}
        if tag == "form":
            self._form = {"attrs": a, "controls": []}
            self.forms.append(self._form)
        elif self._form is None:
            return
        elif tag == "input":
            self._form["controls"].append({"tag": "input", "attrs": a})
        elif tag == "select":
            self._select = {"tag": "select", "attrs": a, "options": []}
            self._form["controls"].append(self._select)
        elif tag == "option" and self._select is not None:
            self._select["options"].append({"value": a.get("value"), "selected": "selected" in a, "text": ""})
        elif tag == "textarea":
            self._textarea = {"tag": "textarea", "attrs": a, "text": ""}
            self._form["controls"].append(self._textarea)

    def handle_data(self, data):
        if self._textarea is not None:
            self._textarea["text"] += data
        elif self._select is not None and self._select["options"]:
            self._select["options"][-1]["text"] += data

    def handle_endtag(self, tag):
        if tag == "form":
            self._form = None
        elif tag == "select":
            self._select = None
        elif tag == "textarea":
            self._textarea = None


def parse_login_form(html: str, base_url: str, username_hint: str | None = None, password_hint: str | None = None) -> LoginForm | None:
    p = _Forms()
    try:
        p.feed(html)
    except Exception:
        return None
    for f in p.forms:
        controls = [c for c in f["controls"] if "name" in c["attrs"] and c["attrs"]["name"] and "disabled" not in c["attrs"]]
        pws = [c for c in controls if c["tag"] == "input" and c["attrs"].get("type", "").lower() == "password"]
        pw = next((c for c in pws if c["attrs"]["name"] == password_hint), None) if password_hint else None
        pw = pw or (pws[0] if pws else None)
        if pw is None:
            continue
        texts = [c for c in controls if c["tag"] == "input" and c["attrs"].get("type", "").lower() in TEXTLIKE]
        captcha = next((c for c in texts if CAPTCHA.search(c["attrs"]["name"])), None)
        pw_idx = controls.index(pw)
        user_el = (next((c for c in texts if username_hint and c["attrs"]["name"] == username_hint), None)
                   or next((c for c in texts if c is not captcha and USER.search(c["attrs"]["name"])), None)
                   or next((c for c in reversed(texts) if c is not captcha and controls.index(c) < pw_idx), None)
                   or next((c for c in texts if c is not captcha), None))
        warnings: list[str] = []
        fields: list[tuple[str, str]] = []
        submit_added = False
        for c in controls:
            if c is pw or c is user_el:
                continue
            a, name = c["attrs"], c["attrs"]["name"]
            if c["tag"] == "select":
                opt = next((o for o in c["options"] if o["selected"]), None) or (c["options"][0] if c["options"] else None)
                if opt:
                    fields.append((name, opt["value"] if opt["value"] else opt["text"].strip()))
            elif c["tag"] == "textarea":
                fields.append((name, c["text"]))
            else:
                t = a.get("type", "").lower()
                if t == "submit":
                    if not submit_added:
                        submit_added = True
                        fields.append((name, a.get("value", "")))
                elif t in ("button", "image", "reset", "file"):
                    pass
                elif t in ("checkbox", "radio"):
                    force = t == "checkbox" and TERMS.search(name) and "checked" not in a
                    if "checked" in a or force:
                        fields.append((name, a.get("value") or "on"))
                        if force:
                            warnings.append(f"Ticks the '{name}' checkbox (terms of use) for you.")
                else:
                    if t in TEXTLIKE and c is not captcha and not a.get("value"):
                        warnings.append(f"Extra empty field '{name}' will be sent blank.")
                    fields.append((name, a.get("value", "")))
        action = urljoin(base_url, f["attrs"].get("action", "").strip() or base_url)
        if action.lower().startswith("http://"):
            warnings.append("This portal uses plain HTTP: others on the network could see your password.")
        return LoginForm(action, (f["attrs"].get("method") or "get").upper(), fields,
                         user_el["attrs"]["name"] if user_el else None, pw["attrs"]["name"], warnings,
                         f"This portal asks for a CAPTCHA/OTP code ('{captcha['attrs']['name']}'), which can't be automated." if captcha else None)
    return None


def host_of(url: str) -> str:
    p = urlsplit(url)
    default = 443 if p.scheme == "https" else 80
    return (p.hostname or "") if (p.port or default) == default else f"{p.hostname}:{p.port}"


@dataclass
class DetectedPortal:
    host: str
    scheme: str
    form: LoginForm
    page_url: str

    @property
    def summary(self) -> str:
        s = (f"Login page: {self.scheme}://{self.host}\nUsername field: {self.form.username_field or '(none found)'}\n"
             f"Password field: {self.form.password_field}\nOther fields sent as the browser would: {len(self.form.fields)}")
        if self.form.warnings:
            s += "\n\n" + "\n".join(f"• {w}" for w in self.form.warnings)
        return s


@dataclass
class DetectResult:
    kind: str                  # "found" | "online" | "problem"
    portal: DetectedPortal | None = None
    message: str = ""


class PortalDiscovery:
    def __init__(self, probe_url: str = PROBE_URL, settle_seconds: float = 1.5):
        self.probe_url, self.settle = probe_url, settle_seconds

    def _session(self) -> requests.Session:
        s = requests.Session()
        s.trust_env = False
        s.headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) HostelAutoLogin/1"
        return s

    @staticmethod
    def _redirect_target(r: requests.Response) -> str | None:
        if r.is_redirect:
            return r.headers.get("Location")
        text = r.text[:MAX_BODY]
        if not (200 <= r.status_code < 300) or has_password_field(text):
            return None
        m = META_REFRESH.search(text) or JS_REDIRECT.search(text)
        return m.group(1) if m else None

    def _land(self, s: requests.Session, pin):
        """Returns ('online', None) | ('page', (url, html)) | ('failed', (reason, transient))."""
        url = self.probe_url
        for hop in range(MAX_HOPS + 1):
            try:
                r = s.get(url, timeout=TIMEOUT, allow_redirects=False)
            except requests.RequestException as e:
                return "failed", (f"network error: {type(e).__name__}", True)
            if hop == 0 and r.status_code == 200 and r.text.strip() == PROBE_BODY:
                return "online", None
            nxt = self._redirect_target(r)
            if nxt is None:
                if 200 <= r.status_code < 300:
                    return "page", (url, r.text[:MAX_BODY])
                return "failed", (f"portal page returned {r.status_code}", r.status_code >= 500)
            url = urljoin(url, nxt)
            pin(url)
        return "failed", ("too many redirects", False)

    def detect(self) -> DetectResult:
        s = self._session()
        try:
            kind, data = self._land(s, lambda u: None)
        finally:
            s.close()
        if kind == "online":
            return DetectResult("online")
        if kind == "failed":
            return DetectResult("problem", message=f"Couldn't reach a login page: {data[0]}. Are you on the Wi-Fi that shows a login page?")
        url, html = data
        form = parse_login_form(html, url)
        if form is None:
            return DetectResult("problem", message=f"Found a page at {host_of(url)} but no username/password form. "
                                "This portal probably needs a browser-based login, which isn't supported in automatic mode.")
        if host_of(form.action) != host_of(url):
            return DetectResult("problem", message=f"The login form submits to a different host ({host_of(form.action)}) than the page ({host_of(url)}). Refusing automatic setup.")
        if form.blocked:
            return DetectResult("problem", message=form.blocked)
        return DetectResult("found", DetectedPortal(host_of(url), urlsplit(url).scheme, form, url))

    def login_auto(self, cfg, user: str, pw: str) -> LoginResult:
        reason = cfg.unconfigured_reason
        if reason:
            return LoginResult(Outcome.CONFIG_ERROR, reason)

        def pin(u):
            check_url(u, cfg.portal_host, cfg.scheme)

        s = self._session()
        try:
            kind, data = self._land(s, pin)
            if kind == "online":
                return LoginResult(Outcome.SUCCESS, "already online")
            if kind == "failed":
                return LoginResult(Outcome.TRANSIENT, data[0])
            return self._submit(s, cfg, data[0], data[1], user, pw, pin)
        except HostPinError as e:
            return LoginResult(Outcome.CONFIG_ERROR, f"refused: {e}. If your portal changed, run \"Detect my portal\" again.")
        except requests.RequestException as e:
            return LoginResult(Outcome.TRANSIENT, f"network error: {type(e).__name__}")
        finally:
            s.close()

    def _submit(self, s, cfg, page_url, html, user, pw, pin) -> LoginResult:
        pin(page_url)
        form = parse_login_form(html, page_url, cfg.auto_username_field, cfg.auto_password_field)
        if form is None:
            return LoginResult(Outcome.CONFIG_ERROR, "no login form found on the portal page")
        if form.blocked:
            return LoginResult(Outcome.CONFIG_ERROR, form.blocked)
        check_url(form.action, cfg.portal_host, cfg.scheme)
        pairs = list(form.fields)
        if form.username_field:
            pairs.append((form.username_field, user))
        pairs.append((form.password_field, pw))
        log.info("auto login %s %s://%s%s", form.method, cfg.scheme, cfg.portal_host, urlsplit(form.action).path)
        hdr = {"Referer": page_url}
        if form.method == "GET":
            r = s.get(form.action, params=pairs, headers=hdr, timeout=TIMEOUT, allow_redirects=False)
        else:
            r = s.post(form.action, data=pairs, headers=hdr, timeout=TIMEOUT, allow_redirects=False)
        hops = 0
        while r.is_redirect and hops < MAX_HOPS:
            hops += 1
            nxt = urljoin(r.url, r.headers.get("Location", ""))
            try:
                pin(nxt)
            except HostPinError:
                break                                     # never follow off the portal host after sending credentials
            r = s.get(nxt, timeout=TIMEOUT, allow_redirects=False)
        log.info("auto login response status=%s", r.status_code)
        if r.status_code >= 500:
            return LoginResult(Outcome.TRANSIENT, f"portal error {r.status_code}")
        if has_password_field(r.text[:MAX_BODY]):
            if self.settle:
                time.sleep(self.settle)
            from .probe import probe
            if probe(self.probe_url):
                return LoginResult(Outcome.SUCCESS, "logged in")
            return LoginResult(Outcome.BAD_CREDENTIALS, "the portal showed the login form again - username or password probably wrong")
        return LoginResult(Outcome.SUCCESS, "login submitted")
