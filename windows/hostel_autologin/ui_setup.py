"""First-run / Settings dialog (tkinter). Own Tk root per dialog so it can run from any thread."""
from __future__ import annotations

import threading
import tkinter as tk
from tkinter import messagebox, ttk
from typing import Callable

from . import creds
from .config import ConfigError, PortalConfig, Settings, portal_json_path
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
    v_ssid, v_user, v_pw = tk.StringVar(value=settings.ssid), tk.StringVar(value=settings.username), tk.StringVar()
    v_auto = tk.BooleanVar(value=settings.auto_login)
    has_pw = bool(settings.username and creds.get_password(settings.username))

    for i, (label, var, show) in enumerate([("Wi-Fi SSID", v_ssid, ""), ("Username", v_user, ""), ("Password", v_pw, "•")]):
        ttk.Label(frm, text=label).grid(row=i, column=0, sticky="w", pady=4)
        ttk.Entry(frm, textvariable=var, width=34, show=show).grid(row=i, column=1, pady=4)
    ttk.Label(frm, text="(leave blank to keep the saved password)" if has_pw else "Stored in Windows Credential Manager",
              foreground="#666").grid(row=3, column=1, sticky="w")
    ttk.Checkbutton(frm, text="Log in automatically", variable=v_auto).grid(row=4, column=1, sticky="w", pady=4)
    status = ttk.Label(frm, text="", wraplength=320, justify="left")
    status.grid(row=5, column=0, columnspan=2, sticky="w", pady=6)

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
        user, ssid = v_user.get().strip(), v_ssid.get().strip()
        if not ssid or not user:
            messagebox.showwarning("HostelAutoLogin", "SSID and username are required.", parent=root)
            return
        pw = v_pw.get()
        if pw:
            creds.save_password(user, pw)
        elif not creds.get_password(user):
            messagebox.showwarning("HostelAutoLogin", "Enter a password.", parent=root)
            return
        if settings.username and settings.username != user:
            creds.delete_password(settings.username)
        settings.ssid, settings.username, settings.auto_login = ssid, user, v_auto.get()
        settings.save()
        saved["ok"] = True
        if on_saved:
            on_saved(settings)
        root.destroy()

    row = ttk.Frame(frm)
    row.grid(row=6, column=0, columnspan=2, sticky="e")
    btn_test = ttk.Button(row, text="Test Login", command=test)
    btn_test.pack(side="left", padx=4)
    ttk.Button(row, text="Save", command=save).pack(side="left", padx=4)
    ttk.Button(row, text="Cancel", command=root.destroy).pack(side="left", padx=4)
    root.mainloop()
    return saved["ok"]
