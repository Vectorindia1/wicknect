"""portal.json parsing/validation, user settings, and file locations."""
from __future__ import annotations

import json
import os
import re
import shutil
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path

APP_NAME = "HostelAutoLogin"
BACKOFF = (2, 5, 15, 30)                 # SPEC 1.5
PROBE_URL = "http://www.msftconnecttest.com/connecttest.txt"
PROBE_BODY = "Microsoft Connect Test"
_FORBIDDEN_HEADERS = {"cookie", "authorization", "proxy-authorization", "host", "content-length"}


class ConfigError(Exception):
    pass


def app_dir() -> Path:
    base = os.environ.get("APPDATA")
    p = Path(base) / APP_NAME if base else Path.home() / f".{APP_NAME.lower()}"
    p.mkdir(parents=True, exist_ok=True)
    return p


def log_dir() -> Path:
    p = app_dir() / "logs"
    p.mkdir(parents=True, exist_ok=True)
    return p


def bundled_dir() -> Path:
    return Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))


def portal_json_path() -> Path:
    """User copy in %APPDATA% wins; first run copies the bundled placeholder there."""
    user = app_dir() / "portal.json"
    if not user.exists():
        beside_exe = Path(sys.executable).parent / "portal.json" if getattr(sys, "frozen", False) else None
        src = beside_exe if beside_exe and beside_exe.exists() else bundled_dir() / "portal.json"
        if src.exists():
            shutil.copyfile(src, user)
    return user


@dataclass
class Settings:
    ssids: list = field(default_factory=list)       # all Wi-Fi names that share the same login
    username: str = ""
    auto_login: bool = True
    login_if_ssid_unknown: bool = False
    auto_reconnect: bool = True
    start_with_windows: bool = False

    @classmethod
    def load(cls, path: Path | None = None) -> "Settings":
        path = path or app_dir() / "settings.json"
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return cls()
        if "ssid" in data and "ssids" not in data:          # settings written by older versions
            data["ssids"] = [data["ssid"]] if data["ssid"] else []
        return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})

    def save(self, path: Path | None = None) -> None:
        path = path or app_dir() / "settings.json"
        path.write_text(json.dumps(asdict(self), indent=2), encoding="utf-8")   # no secrets here


@dataclass
class PortalConfig:
    raw: dict
    origins: list                      # user-confirmed portal addresses [(host, scheme), ...]
    mode: str
    auto_username_field: str | None
    auto_password_field: str | None
    ssid: str
    portal_host: str
    scheme: str
    login: dict
    success: dict
    bad_credentials: dict
    keepalive: dict | None
    fallback_type: str

    @classmethod
    def from_dict(cls, d: dict) -> "PortalConfig":
        if not isinstance(d, dict) or d.get("schema_version") != 1:
            raise ConfigError("login profile: unsupported or missing schema_version (expected 1)")
        mode = str(d.get("mode", "manual")).lower()
        if mode not in ("manual", "auto"):
            raise ConfigError("login profile: mode must be manual or auto")
        host = str(d.get("portal_host", "")).strip()
        if (not host and mode == "manual") or "/" in host or "@" in host or "://" in host:
            raise ConfigError("login profile: portal_host must be a bare host or host:port")
        scheme = str(d.get("scheme", "http")).lower()
        if scheme not in ("http", "https"):
            raise ConfigError("login profile: scheme must be http or https")
        login = d.get("login") or {}
        path = str(login.get("path", ""))
        if not path and mode == "manual":
            raise ConfigError("login profile: login.path is required")
        if str(login.get("method", "POST")).upper() not in ("GET", "POST"):
            raise ConfigError("login profile: login.method must be GET or POST")
        if login.get("content_type", "form") not in ("form", "json"):
            raise ConfigError("login profile: login.content_type must be form or json")
        headers = {k: v for k, v in (login.get("headers") or {}).items() if k.lower() not in _FORBIDDEN_HEADERS}
        login = {**login, "headers": headers, "method": str(login.get("method", "POST")).upper()}
        ka = d.get("keepalive")
        if ka is not None and not ka.get("path"):
            ka = None
        auto = d.get("auto") or {}
        origins = [(host.lower(), scheme)] if host else []
        for u in d.get("portal_hosts") or []:
            m = re.match(r"^(https?)://([^/\s]+)", str(u), re.I)
            if m and (m.group(2).lower(), m.group(1).lower()) not in origins:
                origins.append((m.group(2).lower(), m.group(1).lower()))
        return cls(raw=d, origins=origins, mode=mode, auto_username_field=auto.get("username_field") or None,
                   auto_password_field=auto.get("password_field") or None, ssid=str(d.get("ssid", "")), portal_host=host, scheme=scheme, login=login,
                   success=d.get("success") or {}, bad_credentials=d.get("bad_credentials") or {}, keepalive=ka,
                   fallback_type=str((d.get("fallback") or {}).get("type", "none")))

    @classmethod
    def load(cls, path: Path) -> "PortalConfig":
        try:
            return cls.from_dict(json.loads(Path(path).read_text(encoding="utf-8-sig")))
        except OSError as e:
            raise ConfigError(f"cannot read portal.json: {e.strerror}")
        except ValueError as e:
            raise ConfigError(f"portal.json is not valid JSON: {e}")

    @property
    def unconfigured_reason(self) -> str | None:
        if self.mode == "auto":
            return None if self.origins else 'Not set up yet: no portal set up yet. Open Settings and click Connect while on your Wi-Fi.'
        blob = json.dumps(self.login)
        if "REPLACE_ME" in blob:
            return "portal.json is still the placeholder (contains REPLACE_ME). Fill it in from your captured login."
        if re.search(r"\{\{\s*REVIEW", blob):
            return "portal.json contains unresolved {{REVIEW:...}} fields from the HAR analyzer."
        return None


def auto_config_json(origins: list, username_field: str | None, password_field: str | None) -> str:
    """portal.json content for auto-detected portals (written after the user confirms the host(s))."""
    first = origins[0] if origins else ("", "http")
    return json.dumps({"schema_version": 1, "mode": "auto", "portal_host": first[0], "scheme": first[1],
                       "portal_hosts": [f"{sc}://{h}" for h, sc in origins],
                       "auto": {"username_field": username_field or "", "password_field": password_field or ""}}, indent=2)
