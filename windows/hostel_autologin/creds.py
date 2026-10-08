"""Credential storage in Windows Credential Manager via keyring. Password never touches disk/logs."""
from __future__ import annotations

import keyring

from .config import APP_NAME
from .masking import masker


def save_password(username: str, password: str) -> None:
    keyring.set_password(APP_NAME, username, password)
    masker.register(password)


def get_password(username: str) -> str | None:
    if not username:
        return None
    pw = keyring.get_password(APP_NAME, username)
    masker.register(pw)
    return pw


def delete_password(username: str) -> None:
    try:
        keyring.delete_password(APP_NAME, username)
    except keyring.errors.PasswordDeleteError:
        pass
