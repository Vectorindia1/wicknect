"""Current Wi-Fi SSID on Windows via `netsh wlan show interfaces`."""
from __future__ import annotations

import re
import subprocess
import sys

_SSID = re.compile(r"^\s*SSID\s*:\s*(.*?)\s*$", re.M)          # does not match 'BSSID'
_STATE = re.compile(r"^\s*State\s*:\s*(.*?)\s*$", re.M)


def parse_netsh(out: str) -> str | None:
    """Return the SSID of a *connected* interface, else None."""
    for block in re.split(r"\n\s*\n", out):
        st = _STATE.search(block)
        m = _SSID.search(block)
        if m and m.group(1) and (st is None or st.group(1).lower() == "connected"):
            return m.group(1)
    return None


def current_ssid() -> str | None:
    if sys.platform != "win32":
        return None
    try:
        out = subprocess.run(["netsh", "wlan", "show", "interfaces"], capture_output=True, text=True, timeout=8,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), errors="replace").stdout
    except (OSError, subprocess.SubprocessError):
        return None
    return parse_netsh(out)


def _netsh(*args: str, timeout: int = 12) -> tuple[int, str]:
    try:
        r = subprocess.run(["netsh", "wlan", *args], capture_output=True, text=True, timeout=timeout,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), errors="replace")
        return r.returncode, r.stdout
    except (OSError, subprocess.SubprocessError):
        return 1, ""


def parse_disconnected(out: str) -> bool:
    """True when a Wi-Fi adapter exists but is not connected. False when connected or there is no adapter."""
    blocks = [b for b in re.split(r"\n\s*\n", out) if _STATE.search(b)]
    if not blocks:
        return False
    return not any(_STATE.search(b).group(1).lower() == "connected" for b in blocks)


def wifi_disconnected() -> bool:
    if sys.platform != "win32":
        return False
    return parse_disconnected(_netsh("show", "interfaces", timeout=8)[1])


_PROFILE = re.compile(r"^\s*All User Profile\s*:\s*(.*?)\s*$", re.M)


def parse_profiles(out: str) -> list[str]:
    return _PROFILE.findall(out)


def pick_profiles(profiles: list[str], ssids: list[str]) -> list[str]:
    """Saved Wi-Fi profiles to try, in the user's SSID order (Windows names duplicates 'Name 2')."""
    out: list[str] = []
    for ssid in ssids:
        for p in profiles:
            if (p.lower() == ssid.lower() or re.fullmatch(re.escape(ssid.lower()) + r" \d+", p.lower())) and p not in out:
                out.append(p)
    return out


def reconnect_wifi(ssids: list[str]) -> bool:
    """Ask Windows to join the first saved profile that works. Needs the network to have been connected to once."""
    if sys.platform != "win32":
        return False
    for name in pick_profiles(parse_profiles(_netsh("show", "profiles")[1]), ssids):
        code, out = _netsh("connect", f"name={name}")
        if code == 0:
            return True
    return False
