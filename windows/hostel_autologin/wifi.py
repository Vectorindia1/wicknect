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
