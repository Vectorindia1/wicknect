"""Login engine: reproduces the normal portal login from portal.json. Never guesses an endpoint."""
from __future__ import annotations

import enum
import logging
import re
import time
from dataclasses import dataclass
from typing import Callable
from urllib.parse import quote, urlsplit

import requests

from .config import PortalConfig
from .pinning import HostPinError, check_url, resolve_redirect

log = logging.getLogger(__name__)
_PH = re.compile(r"\{\{\s*([^{}]+?)\s*\}\}")
MAX_HOPS = 5
TIMEOUT = (5, 10)
MAX_BODY = 200_000


class Outcome(enum.Enum):
    SUCCESS = "SUCCESS"
    BAD_CREDENTIALS = "BAD_CREDENTIALS"
    CONFIG_ERROR = "CONFIG_ERROR"
    TRANSIENT = "TRANSIENT"


@dataclass
class LoginResult:
    outcome: Outcome
    detail: str = ""

    @property
    def fatal(self) -> bool:
        return self.outcome in (Outcome.BAD_CREDENTIALS, Outcome.CONFIG_ERROR)


class _ConfigProblem(Exception):
    pass


def render(value, ctx: dict, dyn: dict, url_quote: bool = False) -> str:
    def sub(m):
        key = m.group(1)
        if key in ("username", "password"):
            v = ctx[key]
        elif key == "timestamp_ms":
            v = str(int(time.time() * 1000))
        elif key.startswith("dyn:") and key[4:] in dyn:
            v = dyn[key[4:]]
        else:
            raise _ConfigProblem(f"unresolved placeholder '{{{{{key.split(':')[0]}}}}}' in portal.json")
        return quote(v, safe="") if url_quote else v
    return _PH.sub(sub, str(value))


def _json_path(obj, path: str):
    for part in path.split("."):
        if isinstance(obj, dict) and part in obj:
            obj = obj[part]
        elif isinstance(obj, list) and part.isdigit() and int(part) < len(obj):
            obj = obj[int(part)]
        else:
            return None
    return obj


class LoginEngine:
    def __init__(self, config: PortalConfig, fallback: Callable | None = None):
        self.cfg = config
        self.fallback = fallback

    # -- helpers ---------------------------------------------------------------------------------
    def _url(self, path: str) -> str:
        url = path if re.match(r"^https?://", path, re.I) else f"{self.cfg.scheme}://{self.cfg.portal_host}{path}"
        return check_url(url, self.cfg.portal_host, self.cfg.scheme)

    def _session(self) -> requests.Session:
        s = requests.Session()
        s.trust_env = False                      # never route credentials via env/system proxies
        s.headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) HostelAutoLogin/1"
        return s

    def _follow(self, s: requests.Session, resp: requests.Response, url: str):
        hops = 0
        while resp.is_redirect and hops < MAX_HOPS:
            nxt = resolve_redirect(url, resp.headers.get("Location", ""), self.cfg.portal_host, self.cfg.scheme)
            log.info("redirect -> %s", urlsplit(nxt).path)
            url, hops = nxt, hops + 1
            resp = s.get(url, timeout=TIMEOUT, allow_redirects=False)
        return resp

    # -- evaluation ------------------------------------------------------------------------------
    def _bad_creds(self, responses: list[requests.Response]) -> bool:
        bc = self.cfg.bad_credentials
        if responses[0].status_code in (bc.get("status_in") or []):
            return True
        needles = [n.lower() for n in bc.get("body_contains") or []]
        return any(n in r.text[:MAX_BODY].lower() for r in responses for n in needles)

    def _success(self, first: requests.Response, final: requests.Response) -> bool:
        c = self.cfg.success
        status_in = c.get("status_in")
        checks = 0
        if status_in:
            checks += 1
            if first.status_code not in status_in:
                return False
        body = final.text[:MAX_BODY].lower()
        if c.get("body_contains"):
            checks += 1
            if not any(n.lower() in body for n in c["body_contains"]):
                return False
        if c.get("body_not_contains"):
            checks += 1
            if any(n.lower() in body for n in c["body_not_contains"]):
                return False
        if c.get("redirect_location_contains"):
            checks += 1
            if c["redirect_location_contains"] not in first.headers.get("Location", ""):
                return False
        jp = c.get("json_path_equals")
        if jp:
            checks += 1
            try:
                if _json_path(final.json(), jp["path"]) != jp["value"]:
                    return False
            except ValueError:
                return False
        return checks > 0 or 200 <= first.status_code < 400

    # -- main ------------------------------------------------------------------------------------
    def login(self, username: str, password: str) -> LoginResult:
        reason = self.cfg.unconfigured_reason
        if reason:
            return LoginResult(Outcome.CONFIG_ERROR, reason)          # no network call at all
        if not username or not password:
            return LoginResult(Outcome.CONFIG_ERROR, "username/password not set")
        if self.cfg.mode == "auto":
            from .discovery import PortalDiscovery
            return PortalDiscovery().login_auto(self.cfg, username, password)
        result = self._direct(username, password)
        if result.outcome is Outcome.TRANSIENT and self.cfg.fallback_type == "browser":
            if self.fallback:
                log.info("direct login failed; trying browser fallback")
                return self.fallback(self.cfg, username, password)
            log.warning("fallback.type is 'browser' but no fallback hook is registered")
        return result

    def _direct(self, username: str, password: str) -> LoginResult:
        ctx = {"username": username, "password": password}
        lg = self.cfg.login
        s = self._session()
        try:
            dyn: dict[str, str] = {}
            pre = lg.get("pre_fetch")
            if pre:
                purl = self._url(pre["path"])
                r = self._follow(s, s.get(purl, timeout=TIMEOUT, allow_redirects=False), purl)
                for ex in pre.get("extract") or []:
                    m = re.search(ex["regex"], r.text[:MAX_BODY])
                    if not m:
                        return LoginResult(Outcome.TRANSIENT, f"pre_fetch: '{ex['name']}' not found")
                    dyn[ex["name"]] = m.group(1)
            url = self._url(render(lg["path"], ctx, dyn, url_quote=True))
            headers = {k: render(v, ctx, dyn) for k, v in lg["headers"].items()}
            fields = {k: render(v, ctx, dyn) for k, v in (lg.get("fields") or {}).items()}
            kw = {"json": fields} if lg.get("content_type") == "json" else {"data": fields}
            if lg["method"] == "GET":
                kw = {"params": fields}
            log.info("login %s %s://%s%s", lg["method"], self.cfg.scheme, self.cfg.portal_host, urlsplit(url).path)
            first = s.request(lg["method"], url, headers=headers, timeout=TIMEOUT, allow_redirects=False, **kw)
            responses = [first]
            final = first
            if first.is_redirect:
                final = self._follow(s, first, url)
                responses.append(final)
            log.info("login response status=%s", first.status_code)
            if self._bad_creds(responses):
                return LoginResult(Outcome.BAD_CREDENTIALS, "portal rejected the credentials")
            if first.status_code >= 500 or final.status_code >= 500:
                return LoginResult(Outcome.TRANSIENT, f"portal error {first.status_code}")
            if self._success(first, final):
                return LoginResult(Outcome.SUCCESS, "login accepted")
            return LoginResult(Outcome.TRANSIENT, "success check did not match")
        except _ConfigProblem as e:
            return LoginResult(Outcome.CONFIG_ERROR, str(e))
        except HostPinError as e:
            return LoginResult(Outcome.CONFIG_ERROR, f"refused: {e}")
        except requests.RequestException as e:
            return LoginResult(Outcome.TRANSIENT, f"network error: {type(e).__name__}")
        finally:
            s.close()

    def keepalive(self) -> bool:
        ka = self.cfg.keepalive
        if not ka:
            return False
        s = self._session()
        try:
            url = self._url(ka["path"])
            r = s.request(str(ka.get("method", "GET")).upper(), url, timeout=TIMEOUT, allow_redirects=False)
            log.info("keepalive status=%s", r.status_code)
            return r.status_code < 400
        except (HostPinError, requests.RequestException) as e:
            log.info("keepalive failed: %s", type(e).__name__)
            return False
        finally:
            s.close()
