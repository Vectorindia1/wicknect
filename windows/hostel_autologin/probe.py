"""Internet probe (SPEC section 3). Carries no credentials."""
from __future__ import annotations

import logging

import requests

from .config import PROBE_BODY, PROBE_URL

log = logging.getLogger(__name__)


def probe(url: str = PROBE_URL, expected: str = PROBE_BODY, timeout: float = 5.0) -> bool:
    s = requests.Session()
    s.trust_env = False                         # ignore env/system proxies
    try:
        r = s.get(url, timeout=timeout, allow_redirects=False, headers={"Cache-Control": "no-cache"})
    except requests.RequestException as e:
        log.debug("probe error: %s", type(e).__name__)
        return False
    finally:
        s.close()
    return r.status_code == 200 and r.text.strip() == expected
