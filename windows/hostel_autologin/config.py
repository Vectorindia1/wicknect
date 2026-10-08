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
    ssid: str = "University_Hostel"
    username: str = ""
    auto_login: bool = True
    login_if_ssid_unknown: bool = False
    start_with_windows: bool = False

    @classmethod
    def load(cls, path: Path | None = None) -> "Settings":
        path = path or app_dir() / "settings.json"
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return cls()
        return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})

    def save(self, path: Path | None = None) -> None:
        path = path or app_dir() / "settings.json"
        path.write_text(json.dumps(asdict(self), indent=2), encoding="utf-8")   # no secrets here


@dataclass
class PortalConfig:
    raw: dict
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
            raise ConfigError("portal.json: unsupported or missing schema_version (expected 1)")
        host = str(d.get("portal_host", "")).strip()
        if not host or "/" in host or "@" in host or "://" in host:
            raise ConfigError("portal.json: portal_host must be a bare host or host:port")
        scheme = str(d.get("scheme", "http")).lower()
        if scheme not in ("http", "https"):
            raise ConfigError("portal.json: scheme must be http or https")
        login = d.get("login") or {}
        path = str(login.get("path", ""))
        if not path:
            raise ConfigError("portal.json: login.path is required")
        if str(login.get("method", "POST")).upper() not in ("GET", "POST"):
            raise ConfigError("portal.json: login.method must be GET or POST")
        if login.get("content_type", "form") not in ("form", "json"):
            raise ConfigError("portal.json: login.content_type must be form or json")
        headers = {k: v for k, v in (login.get("headers") or {}).items() if k.lower() not in _FORBIDDEN_HEADERS}
        login = {**login, "headers": headers, "method": str(login.get("method", "POST")).upper()}
        ka = d.get("keepalive")
        if ka is not None and not ka.get("path"):
            ka = None
        return cls(raw=d, ssid=str(d.get("ssid", "University_Hostel")), portal_host=host, scheme=scheme, login=login,
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
        blob = json.dumps(self.login)
        if "REPLACE_ME" in blob:
            return "portal.json is still the placeholder (contains REPLACE_ME). Fill it in from your captured login."
        if re.search(r"\{\{\s*REVIEW", blob):
            return "portal.json contains unresolved {{REVIEW:...}} fields from the HAR analyzer."
        return None
