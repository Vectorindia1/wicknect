"""One-window setup for ordinary users (tkinter): Wi-Fi name(s), username, password, one Connect button.
Own Tk root per dialog so it can run from any thread."""
from __future__ import annotations

import threading
import tkinter as tk
from tkinter import messagebox, ttk
from typing import Callable

from . import creds, friendly, startup
from .config import ConfigError, PortalConfig, Settings, auto_config_json, portal_json_path
from .discovery import PortalDiscovery
from .login_engine import LoginEngine, LoginResult, Outcome
from .masking import masker
from .wifi import current_ssid


def show_setup(settings: Settings, on_saved: Callable[[Settings], None] | None = None,
               fallback: Callable | None = None) -> bool:
    """Blocking. Returns True once the user has saved working settings."""
    saved = {"ok": False}
    root = tk.Tk()
    root.title("Wify – Get connected")
    root.resizable(False, False)
    frm = ttk.Frame(root, padding=16)
    frm.grid()

    ttk.Label(frm, text="1. Connect your computer to your Wi-Fi.\n2. Type your own Wi-Fi login details.\n3. Click Connect. That's it.",
              justify="left").grid(row=0, column=0, columnspan=2, sticky="w", pady=(0, 10))

    ttk.Label(frm, text="Wi-Fi name").grid(row=1, column=0, sticky="nw", pady=4)
    t_ssids = tk.Text(frm, width=34, height=3)
    t_ssids.insert("1.0", "\n".join(settings.ssids) or (current_ssid() or ""))
    t_ssids.grid(row=1, column=1, pady=4)
    ttk.Label(frm, text="Several Wi-Fi names with the same login (hostel, college…)?\nPut each one on its own line.",
              foreground="#666", justify="left").grid(row=2, column=1, sticky="w")

    v_user, v_pw = tk.StringVar(value=settings.username), tk.StringVar()
    has_pw = bool(settings.username and creds.get_password(settings.username))
    ttk.Label(frm, text="Username").grid(row=3, column=0, sticky="w", pady=4)
    ttk.Entry(frm, textvariable=v_user, width=36).grid(row=3, column=1, pady=4)
    ttk.Label(frm, text="Password").grid(row=4, column=0, sticky="w", pady=4)
    ttk.Entry(frm, textvariable=v_pw, width=36, show="•").grid(row=4, column=1, pady=4)
    ttk.Label(frm, text="Leave empty to keep the saved password" if has_pw else "Stored safely in Windows Credential Manager",
              foreground="#666").grid(row=5, column=1, sticky="w")

    v_start = tk.BooleanVar(value=True)
    ttk.Checkbutton(frm, text="Start automatically when I sign in to Windows", variable=v_start).grid(row=6, column=1, sticky="w", pady=(8, 0))

    status = ttk.Label(frm, text="", wraplength=380, justify="left")
    status.grid(row=8, column=0, columnspan=2, sticky="w", pady=8)
    btn = ttk.Button(frm, text="Connect")
    btn.grid(row=7, column=0, columnspan=2, sticky="ew", pady=(10, 0))

    def say(text: str, ok: bool | None = None) -> None:
        root.after(0, lambda: status.config(text=text, foreground={True: "#1a7f37", False: "#b42318", None: "#222"}[ok]))

    def ssid_list() -> list[str]:
        out: list[str] = []
        for line in t_ssids.get("1.0", "end").splitlines():
            if line.strip() and line.strip() not in out:
                out.append(line.strip())
        return out

    def finish(r: LoginResult) -> None:
        masker.register(v_pw.get())
        ok = r.outcome is Outcome.SUCCESS
        say(friendly.login(LoginResult(r.outcome, masker.mask(r.detail))), ok)
        root.after(0, lambda: btn.state(["!disabled"]))
        if ok:
            saved["ok"] = True
            if on_saved:
                on_saved(settings)

    def run_test(user: str, pw: str, allow_redetect: bool) -> None:
        say("Logging in…")
        try:
            r = LoginEngine(PortalConfig.load(portal_json_path()), fallback).login(user, pw)
        except ConfigError as e:
            r = LoginResult(Outcome.CONFIG_ERROR, str(e))
        if allow_redetect and r.outcome is Outcome.CONFIG_ERROR and ("refused" in r.detail or "not set up" in r.detail.lower()):
            if detect_and_confirm(user, pw):
                return
        finish(r)

    def detect_and_confirm(user: str, pw: str) -> bool:
        """True if the flow continued (user confirmed, test running); False to let the caller report."""
        say("Looking for your Wi-Fi's login page…")
        res = PortalDiscovery().detect()
        if res.kind == "online":
            say("You already have internet, so there is no login page to find right now. Turn Wi-Fi off and on "
                "(or wait until the Wi-Fi logs you out), then click Connect again.", False)
            root.after(0, lambda: btn.state(["!disabled"]))
            return True
        if res.kind == "problem":
            say(res.message, False)
            root.after(0, lambda: btn.state(["!disabled"]))
            return True
        p = res.portal
        answer: dict = {}
        evt = threading.Event()

        def ask():
            note = ("\n\nNote: this page is not encrypted, so other people on the same Wi-Fi could see your password. "
                    "That is how your Wi-Fi works, not something this app controls.") if p.scheme == "http" else ""
            answer["yes"] = messagebox.askyesno("Is this your Wi-Fi's login page?",
                                                f"We found a login page at {p.host}.\n\nIf this is the page you normally log in on, click Yes. "
                                                f"Your username and password will only ever be sent to this address.{note}", parent=root)
            evt.set()
        root.after(0, ask)
        evt.wait()
        if not answer["yes"]:
            say("Okay, nothing was saved.")
            root.after(0, lambda: btn.state(["!disabled"]))
            return True
        try:
            known = PortalConfig.load(portal_json_path()).origins
        except ConfigError:
            known = []
        origin = (p.host.lower(), p.scheme)
        origins = known if origin in known else known + [origin]
        portal_json_path().write_text(auto_config_json(origins, p.form.username_field, p.form.password_field), encoding="utf-8")
        run_test(user, pw, allow_redetect=False)
        return True

    def work(user: str, pw: str) -> None:
        try:
            try:
                configured = PortalConfig.load(portal_json_path()).unconfigured_reason is None
            except ConfigError:
                configured = False
            if not configured:
                detect_and_confirm(user, pw)
            else:
                run_test(user, pw, allow_redetect=True)
        except Exception:
            say("Something unexpected went wrong. Please try again.", False)
            root.after(0, lambda: btn.state(["!disabled"]))

    def connect() -> None:
        user, ssids = v_user.get().strip(), ssid_list()
        pw = v_pw.get() or (creds.get_password(user) or "")
        if not ssids:
            return say("Please type the name of your Wi-Fi first.", False)
        if not user:
            return say("Please type your username.", False)
        if not pw:
            return say("Please type your password.", False)
        masker.register(pw)
        if v_pw.get():
            creds.save_password(user, v_pw.get())
        if settings.username and settings.username != user:
            creds.delete_password(settings.username)
        settings.ssids, settings.username = ssids, user
        settings.start_with_windows = v_start.get()
        settings.save()
        try:
            startup.set_enabled(v_start.get())
        except OSError:
            pass
        btn.state(["disabled"])
        threading.Thread(target=work, args=(user, pw), daemon=True).start()

    btn.config(command=connect)
    root.protocol("WM_DELETE_WINDOW", root.destroy)
    root.mainloop()
    return saved["ok"]
