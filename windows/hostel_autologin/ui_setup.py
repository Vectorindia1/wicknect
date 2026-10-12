"""First-run / Settings dialog (tkinter). Own Tk root per dialog so it can run from any thread."""
from __future__ import annotations

import threading
import tkinter as tk
from tkinter import messagebox, ttk
from typing import Callable

from . import creds
from .config import ConfigError, PortalConfig, Settings, auto_config_json, portal_json_path
from .discovery import PortalDiscovery
from .wifi import current_ssid
from .login_engine import LoginEngine
from .masking import masker


def show_setup(settings: Settings, on_saved: Callable[[Settings], None] | None = None,
               fallback: Callable | None = None) -> bool:
    """Blocking. Returns True if saved."""
    saved = {"ok": False}
    root = tk.Tk()
    root.title("HostelAutoLogin – Setup")
    root.resizable(False, False)
    frm = ttk.Frame(root, padding=14)
    frm.grid()
    v_user, v_pw = tk.StringVar(value=settings.username), tk.StringVar()
    v_auto = tk.BooleanVar(value=settings.auto_login)
    has_pw = bool(settings.username and creds.get_password(settings.username))

    ttk.Label(frm, text="Wi-Fi names\n(one per line)").grid(row=0, column=0, sticky="nw", pady=4)
    t_ssids = tk.Text(frm, width=34, height=3)
    t_ssids.insert("1.0", "\n".join(settings.ssids))
    t_ssids.grid(row=0, column=1, pady=4)
    for i, (label, var, show) in enumerate([("Username", v_user, ""), ("Password", v_pw, "•")], start=1):
        ttk.Label(frm, text=label).grid(row=i, column=0, sticky="w", pady=4)
        ttk.Entry(frm, textvariable=var, width=34, show=show).grid(row=i, column=1, pady=4)
    ttk.Label(frm, text="(leave blank to keep the saved password)" if has_pw else "Stored in Windows Credential Manager",
              foreground="#666").grid(row=3, column=1, sticky="w")
    ttk.Checkbutton(frm, text="Log in automatically", variable=v_auto).grid(row=4, column=1, sticky="w", pady=4)
    v_recon = tk.BooleanVar(value=settings.auto_reconnect)
    ttk.Checkbutton(frm, text="Reconnect Wi-Fi automatically when it drops", variable=v_recon).grid(row=4, column=1, sticky="e", pady=4)
    status = ttk.Label(frm, text="", wraplength=320, justify="left")
    status.grid(row=5, column=0, columnspan=3, sticky="w", pady=6)

    def ssid_list() -> list[str]:
        out: list[str] = []
        for line in t_ssids.get("1.0", "end").splitlines():
            if line.strip() and line.strip() not in out:
                out.append(line.strip())
        return out

    def password() -> str:
        return v_pw.get() or (creds.get_password(v_user.get().strip()) or "")

    def test():
        user, pw = v_user.get().strip(), password()
        if not user or not pw:
            status.config(text="Enter username and password first.")
            return
        masker.register(pw)
        status.config(text="Testing login… (you must be on the hostel Wi-Fi)")
        btn_test.state(["disabled"])

        def work():
            try:
                res = LoginEngine(PortalConfig.load(portal_json_path()), fallback).login(user, pw)
                msg = f"{res.outcome.value}: {masker.mask(res.detail)}"
            except ConfigError as e:
                msg = f"CONFIG_ERROR: {e}"
            root.after(0, lambda: (status.config(text=msg), btn_test.state(["!disabled"])))
        threading.Thread(target=work, daemon=True).start()

    def save():
        user, ssids = v_user.get().strip(), ssid_list()
        if not ssids or not user:
            messagebox.showwarning("HostelAutoLogin", "At least one Wi-Fi name and a username are required.", parent=root)
            return
        pw = v_pw.get()
        if pw:
            creds.save_password(user, pw)
        elif not creds.get_password(user):
            messagebox.showwarning("HostelAutoLogin", "Enter a password.", parent=root)
            return
        if settings.username and settings.username != user:
            creds.delete_password(settings.username)
        settings.ssids, settings.username, settings.auto_login, settings.auto_reconnect = ssids, user, v_auto.get(), v_recon.get()
        settings.save()
        saved["ok"] = True
        if on_saved:
            on_saved(settings)
        root.destroy()

    def use_current():
        cur = current_ssid()
        if cur:
            if cur not in ssid_list():
                t_ssids.insert("end", ("\n" if t_ssids.get("1.0", "end").strip() else "") + cur)
        else:
            status.config(text="Can't read the Wi-Fi name (not connected, or Windows needs Location access). Type it instead.")

    def detect():
        status.config(text="Looking for your portal's login page…")
        btn_detect.state(["disabled"])

        def work():
            res = PortalDiscovery().detect()
            root.after(0, lambda: done(res))

        def done(res):
            btn_detect.state(["!disabled"])
            if res.kind == "online":
                status.config(text="You already have internet, so there is no login page right now. Reconnect to the Wi-Fi (or wait for the session to expire) and try again.")
            elif res.kind == "problem":
                status.config(text=res.message)
            else:
                p = res.portal
                if messagebox.askyesno("HostelAutoLogin", p.summary + f"\n\nSend your username and password to {p.host}?\n"
                                       "Only continue if this is your Wi-Fi's login page.", parent=root):
                    try:                      # several networks may use different login pages: keep every confirmed one
                        known = PortalConfig.load(portal_json_path()).origins
                    except ConfigError:
                        known = []
                    origins = known + [(p.host.lower(), p.scheme)] if (p.host.lower(), p.scheme) not in known else known
                    portal_json_path().write_text(auto_config_json(origins, p.form.username_field, p.form.password_field), encoding="utf-8")
                    status.config(text=f"Portal saved: {p.scheme}://{p.host}. Now click Test Login.")
        threading.Thread(target=work, daemon=True).start()

    ttk.Button(frm, text="Add current Wi-Fi", command=use_current).grid(row=0, column=2, padx=6, sticky="n")
    row = ttk.Frame(frm)
    row.grid(row=6, column=0, columnspan=3, sticky="e")
    btn_detect = ttk.Button(row, text="Detect my portal", command=detect)
    btn_detect.pack(side="left", padx=4)
    btn_test = ttk.Button(row, text="Test Login", command=test)
    btn_test.pack(side="left", padx=4)
    ttk.Button(row, text="Save", command=save).pack(side="left", padx=4)
    ttk.Button(row, text="Cancel", command=root.destroy).pack(side="left", padx=4)
    root.mainloop()
    return saved["ok"]
