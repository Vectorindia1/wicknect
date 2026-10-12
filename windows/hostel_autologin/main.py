"""Entry point."""
from __future__ import annotations

import argparse
import logging
import socket
import sys
from logging.handlers import RotatingFileHandler

from . import creds, fallback
from .config import ConfigError, PortalConfig, Settings, log_dir, portal_json_path
from .login_engine import LoginEngine
from .masking import MaskingFilter
from .monitor import Monitor
from .probe import probe
from .wifi import current_ssid, reconnect_wifi, wifi_disconnected

LOCK_PORT = 47653


def setup_logging(console: bool = False) -> None:
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    h = RotatingFileHandler(log_dir() / "hostelautologin.log", maxBytes=512_000, backupCount=3, encoding="utf-8")
    h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    h.addFilter(MaskingFilter())
    root.addHandler(h)
    if console:
        c = logging.StreamHandler()
        c.setFormatter(h.formatter)
        c.addFilter(MaskingFilter())
        root.addHandler(c)
    logging.getLogger("urllib3").setLevel(logging.WARNING)


def single_instance():
    s = socket.socket()
    try:
        s.bind(("127.0.0.1", LOCK_PORT))
    except OSError:
        return None
    return s


def main(argv=None) -> int:
    ap = argparse.ArgumentParser("HostelAutoLogin")
    ap.add_argument("--check-config", action="store_true", help="validate portal.json and exit")
    ap.add_argument("--console-log", action="store_true")
    args = ap.parse_args(argv)
    setup_logging(args.console_log)
    log = logging.getLogger("main")

    if args.check_config:
        try:
            cfg = PortalConfig.load(portal_json_path())
            print(cfg.unconfigured_reason or "portal.json looks configured")
            print(f"portal: {cfg.scheme}://{cfg.portal_host}  login path set: {not cfg.unconfigured_reason}")
            return 0
        except ConfigError as e:
            print(f"ERROR: {e}")
            return 1

    lock = single_instance()
    if lock is None:
        log.info("another instance is running; exiting")
        return 0

    settings = Settings.load()
    from .ui_setup import show_setup
    if not settings.username or not creds.get_password(settings.username):
        if not show_setup(settings, fallback=fallback.get_browser_fallback()):
            return 0

    def get_config() -> PortalConfig:
        return PortalConfig.load(portal_json_path())      # re-read each time: edits apply without restart

    def get_credentials():
        pw = creds.get_password(settings.username)
        return (settings.username, pw) if pw else None

    def do_login(cfg, user, pw):
        return LoginEngine(cfg, fallback.get_browser_fallback()).login(user, pw)

    from .tray import Tray
    holder = {}
    mon = Monitor(get_settings=lambda: settings, get_config=get_config, get_credentials=get_credentials,
                  probe=probe, ssid=current_ssid, login=do_login,
                  keepalive=lambda cfg: LoginEngine(cfg).keepalive(),
                  disconnected=wifi_disconnected, reconnect=reconnect_wifi,
                  notify=lambda t, m: holder["tray"].notify(t, m))
    tray = holder["tray"] = Tray(mon, settings, fallback.get_browser_fallback())
    mon.start()
    log.info("HostelAutoLogin started")
    tray.run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
