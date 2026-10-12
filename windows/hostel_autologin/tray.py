"""System tray (pystray) + toast notifications."""
from __future__ import annotations

import os
import threading

import pystray
from PIL import Image, ImageDraw

from . import startup
from .config import Settings, log_dir, portal_json_path
from .monitor import Monitor
from .statemachine import State
from .ui_setup import show_setup

COLORS = {State.VALIDATED: "#2e9e4f", State.OFFLINE: "#8a8a8a", State.FAILURE: "#d33a3a",
          State.CONNECTED: "#d9a21b", State.LOGIN_NEEDED: "#d9a21b", State.AUTHENTICATING: "#2f73d9",
          State.VERIFYING: "#2f73d9"}


def make_icon(state: State) -> Image.Image:
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((6, 6, 58, 58), fill=COLORS.get(state, "#8a8a8a"))
    d.arc((16, 20, 48, 52), 215, 325, fill="white", width=5)
    d.arc((24, 28, 40, 44), 215, 325, fill="white", width=5)
    d.ellipse((29, 42, 35, 48), fill="white")
    return img


class Tray:
    def __init__(self, monitor: Monitor, settings: Settings, fallback=None):
        self.m, self.settings, self.fallback = monitor, settings, fallback
        self._dialog = threading.Lock()
        self.icon = pystray.Icon("HostelAutoLogin", make_icon(State.OFFLINE), "HostelAutoLogin", self._menu())
        monitor.on_state = self._state_changed

    def notify(self, title: str, msg: str) -> None:
        try:
            self.icon.notify(msg, title)
        except Exception:
            pass

    def _state_changed(self, state: State) -> None:
        self.icon.icon = make_icon(state)
        self.icon.title = f"HostelAutoLogin – {state.value}"
        self.icon.update_menu()

    def _menu(self):
        M, I = pystray.Menu, pystray.MenuItem
        return M(
            I(lambda _: self.m.status_text[:80], None, enabled=False),
            I("Login Now", lambda: threading.Thread(target=self.m.login_now, daemon=True).start()),
            I(lambda _: "Resume" if self.m.paused else "Pause", self._toggle_pause),
            I("Reconnect Wi-Fi now", lambda: threading.Thread(target=self._reconnect_now, daemon=True).start()),
            I("Open Logs", lambda: os.startfile(log_dir())),
            I("Advanced: open config folder", lambda: os.startfile(portal_json_path().parent)),
            I("Settings (Wi-Fi & login)…", lambda: threading.Thread(target=self._settings, daemon=True).start()),
            I("Start with Windows", self._toggle_startup, checked=lambda _: startup.is_enabled()),
            I("Exit", self._exit),
        )

    def _reconnect_now(self):
        from .wifi import reconnect_wifi
        ok = reconnect_wifi(list(self.settings.ssids))
        self.notify("HostelAutoLogin", "Asked Windows to reconnect." if ok else "No saved Wi-Fi network to join. Connect to it once manually in Windows first.")

    def _toggle_pause(self):
        self.m.paused = not self.m.paused
        self.icon.update_menu()

    def _toggle_startup(self):
        startup.set_enabled(not startup.is_enabled())
        self.settings.start_with_windows = startup.is_enabled()
        self.settings.save()

    def _settings(self):
        if not self._dialog.acquire(blocking=False):
            return
        try:
            show_setup(self.settings, lambda s: self.m.settings_changed(), self.fallback)
        finally:
            self._dialog.release()

    def _exit(self):
        self.m.stop()
        self.icon.stop()

    def run(self):
        self.icon.run()
