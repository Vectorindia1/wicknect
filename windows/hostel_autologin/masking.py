"""Secret masking for logs and diagnostics. Nothing sensitive may reach a log line unmasked."""
from __future__ import annotations

import logging
import re
import threading

MASK = "***"
_SENSITIVE_KEY = r"(?:pass(?:word|wd)?|pwd|secret|token|cookie|session(?:id)?|auth(?:orization)?|csrf|xsrf|sid|nonce|key)"
# key=value / key: value / "key": "value"
_KV = re.compile(rf"(?i)(\b[\w-]*{_SENSITIVE_KEY}[\w-]*\"?\s*[=:]\s*\"?)([^&\s\"',;]+)")
_HEADER = re.compile(r"(?i)\b(cookie|set-cookie|authorization|proxy-authorization)\b(\s*[:=]\s*)([^\r\n]+)")
_LONG = re.compile(r"(?<![A-Za-z0-9+/=_-])[A-Za-z0-9+/=_-]{24,}(?![A-Za-z0-9+/=_-])")


class Masker:
    def __init__(self):
        self._secrets: set[str] = set()
        self._lock = threading.Lock()

    def register(self, secret: str | None) -> None:
        if secret and len(secret) >= 3:
            with self._lock:
                self._secrets.add(secret)

    def clear(self) -> None:
        with self._lock:
            self._secrets.clear()

    def mask(self, text: object) -> str:
        s = str(text)
        with self._lock:
            secrets = sorted(self._secrets, key=len, reverse=True)
        for sec in secrets:
            s = s.replace(sec, MASK)
        s = _HEADER.sub(lambda m: f"{m.group(1)}{m.group(2)}{MASK}", s)
        s = _KV.sub(lambda m: f"{m.group(1)}{MASK}", s)
        s = _LONG.sub(MASK, s)
        return s


masker = Masker()


class MaskingFilter(logging.Filter):
    """Formats the record fully, masks it, and drops args so no handler can see raw values."""

    def __init__(self, m: Masker = masker):
        super().__init__()
        self.m = m

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            msg = record.getMessage()
        except Exception:
            msg = str(record.msg)
        record.msg = self.m.mask(msg)
        record.args = None
        if record.exc_info:
            # exception text can embed URLs/headers; render and mask it too
            import traceback
            record.exc_text = self.m.mask("".join(traceback.format_exception(*record.exc_info)))
            record.exc_info = None
        return True
