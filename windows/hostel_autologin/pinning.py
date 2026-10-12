"""Host pinning: credentials may only be sent to the configured portal host."""
from __future__ import annotations

from urllib.parse import urljoin, urlsplit


class HostPinError(Exception):
    pass


def split_host(portal_host: str) -> tuple[str, int | None]:
    p = urlsplit("//" + portal_host)
    return (p.hostname or "").lower(), p.port


def check_url(url: str, portal_host: str, scheme: str) -> str:
    """Return url if it targets exactly (scheme, host, port) of the portal; raise HostPinError otherwise."""
    p = urlsplit(url)
    want_host, want_port = split_host(portal_host)
    if not want_host:
        raise HostPinError("portal_host is empty")
    if p.scheme.lower() != scheme.lower():
        raise HostPinError(f"scheme '{p.scheme}' not allowed (expected '{scheme}')")
    if "@" in p.netloc or p.username is not None or p.password is not None:
        raise HostPinError("URL with userinfo refused")
    try:
        port = p.port
    except ValueError:
        raise HostPinError("invalid port")
    if (p.hostname or "").lower() != want_host:
        raise HostPinError(f"host '{p.hostname}' is not the pinned portal host")
    if port != want_port:
        raise HostPinError("port does not match pinned portal host")
    return url


def resolve_redirect(current_url: str, location: str, portal_host: str, scheme: str) -> str:
    return check_url(urljoin(current_url, location), portal_host, scheme)


def check_any(url: str, origins) -> str:
    """Accept the URL if it matches any user-confirmed (host, scheme) origin."""
    if not origins:
        raise HostPinError("no portal host has been confirmed yet")
    for host, scheme in origins:
        try:
            return check_url(url, host, scheme)
        except HostPinError:
            continue
    raise HostPinError(f"host '{urlsplit(url).hostname}' is not one of the confirmed portal hosts")
