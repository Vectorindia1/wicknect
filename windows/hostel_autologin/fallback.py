"""Pluggable browser-based fallback (SPEC portal.json: fallback.type == "browser").

Register a callable `hook(config, username, password) -> LoginResult` (for example one that drives
Playwright/Selenium against the pinned portal host). It is invoked only when direct HTTP login ends
in a TRANSIENT result and portal.json sets fallback.type to "browser". The hook MUST itself respect
host pinning (hostel_autologin.pinning.check_url) and must not log credentials.
"""
from __future__ import annotations

from typing import Callable

_hook: Callable | None = None


def register_browser_fallback(fn: Callable | None) -> None:
    global _hook
    _hook = fn


def get_browser_fallback() -> Callable | None:
    return _hook
