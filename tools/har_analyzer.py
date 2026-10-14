#!/usr/bin/env python3
"""HAR analyzer for Wify.

Reads a HAR file exported from Chrome DevTools, finds the login POST, and prints a
*redacted* portal.json draft (schema v1, see docs/SPEC.md).

Safety properties:
  * Passwords, cookies, tokens, Authorization values and high-entropy values are NEVER emitted;
    they become placeholders ({{password}}, {{username}}, {{REVIEW:...}}).
  * Response bodies are never copied; only keyword hits and status/redirect info are reported.
  * Before writing, the output is scanned for every secret value found in the HAR; if any appears,
    the tool aborts instead of writing.
  * Reads the file only; makes no network requests.

Usage:
  python tools/har_analyzer.py capture.har -o portal.draft.json
  python tools/har_analyzer.py capture.har --list          # list candidate requests only
  python tools/har_analyzer.py capture.har --pick 3        # choose candidate #3 explicitly
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
from urllib.parse import parse_qsl, urlsplit, urlunsplit, urlencode

SECRET_NAME = re.compile(r"pass|pwd|secret|token|cookie|session|auth|csrf|xsrf|sid|nonce|key|otp|pin", re.I)
USER_NAME = re.compile(r"^(user(name)?|uname|login(id|name)?|userid|user_id|uid|email|id)$|user", re.I)
LOGIN_PATH = re.compile(r"login|logon|signin|sign-in|auth|portal|cportal|userlogin|authenticate", re.I)
KEEPALIVE_PATH = re.compile(r"keep.?alive|heartbeat|refresh|status|live|ping|session", re.I)
SUCCESS_WORDS = ["logged in", "login successful", "success", "welcome", "authenticated", "you are now connected", "connected"]
FAILURE_WORDS = ["invalid", "incorrect", "wrong", "failed", "denied", "expired", "not allowed", "limit", "already"]
DROP_HEADERS = {"cookie", "set-cookie", "authorization", "proxy-authorization", "x-csrf-token", "x-xsrf-token"}
KEEP_HEADERS = {"content-type", "referer", "origin", "x-requested-with", "accept", "accept-language"}
TIMESTAMP = re.compile(r"^\d{10}(\d{3})?$")


def entropy(s: str) -> float:
    if not s:
        return 0.0
    return -sum((s.count(c) / len(s)) * math.log2(s.count(c) / len(s)) for c in set(s))


def looks_secret_value(v: str) -> bool:
    return len(v) >= 24 or (len(v) >= 12 and entropy(v) > 3.3)


class Redactor:
    def __init__(self):
        self.secrets: set[str] = set()   # raw values we must never emit

    def note(self, v: str):
        if v and len(v) >= 3:
            self.secrets.add(v)

    def field(self, name: str, value: str) -> str:
        """Return template value for a form/json field."""
        if SECRET_NAME.search(name):
            self.note(value)
            return "{{password}}" if re.search(r"pass|pwd", name, re.I) else f"{{{{REVIEW:{name}}}}}"
        if USER_NAME.search(name):
            self.note(value)
            return "{{username}}"
        if TIMESTAMP.match(value):
            return "{{timestamp_ms}}"
        if looks_secret_value(value):
            self.note(value)
            return f"{{{{REVIEW:{name}}}}}"
        return value

    def url(self, raw: str) -> str:
        p = urlsplit(raw)
        q = []
        for k, v in parse_qsl(p.query, keep_blank_values=True):
            q.append((k, self.field(k, v)))
        netloc = p.hostname or ""
        if p.port:
            netloc += f":{p.port}"
        return urlunsplit((p.scheme, netloc, p.path, urlencode(q, safe="{}:"), ""))


def load_har(path: str) -> list[dict]:
    with open(path, "r", encoding="utf-8-sig") as f:
        har = json.load(f)
    return har["log"]["entries"]


def req_fields(req: dict) -> tuple[str, list[tuple[str, str]]]:
    """Return (kind, [(name,value)]) where kind in form|json|raw|none."""
    post = req.get("postData") or {}
    mime = (post.get("mimeType") or "").lower()
    if "json" in mime and post.get("text"):
        try:
            obj = json.loads(post["text"])
            if isinstance(obj, dict):
                return "json", [(k, str(v)) for k, v in obj.items()]
        except ValueError:
            pass
    if post.get("params"):
        return "form", [(p["name"], p.get("value", "")) for p in post["params"]]
    if post.get("text"):
        if "urlencoded" in mime or "=" in post["text"]:
            return "form", parse_qsl(post["text"], keep_blank_values=True)
        return "raw", []
    return "none", []


def score(entry: dict) -> int:
    req = entry["request"]
    if req["method"].upper() != "POST":
        return 0
    s = 1
    kind, fields = req_fields(req)
    names = [n for n, _ in fields]
    if any(re.search(r"pass|pwd", n, re.I) for n in names):
        s += 10
    if any(USER_NAME.search(n) for n in names):
        s += 4
    if LOGIN_PATH.search(urlsplit(req["url"]).path):
        s += 3
    return s


def candidates(entries: list[dict]) -> list[tuple[int, int, dict]]:
    out = [(score(e), i, e) for i, e in enumerate(entries)]
    out = [t for t in out if t[0] > 1]
    out.sort(key=lambda t: (-t[0], t[1]))
    return out


def header(h: list[dict], name: str) -> str | None:
    for x in h:
        if x["name"].lower() == name.lower():
            return x["value"]
    return None


def response_text(entry: dict) -> str:
    return (entry.get("response", {}).get("content", {}) or {}).get("text", "") or ""


def redirect_chain(entries: list[dict], start: int, red: Redactor) -> list[dict]:
    chain = []
    cur = entries[start]
    for _ in range(8):
        resp = cur["response"]
        loc = header(resp.get("headers", []), "location") or resp.get("redirectURL")
        chain.append({"method": cur["request"]["method"], "url": red.url(cur["request"]["url"]), "status": resp["status"],
                      "location": red.url(loc) if loc else None})
        if not loc:
            break
        nxt = next((e for e in entries[start:] if e["request"]["url"] == loc), None)
        if not nxt:
            break
        cur = nxt
    return chain


def analyze(entries: list[dict], pick: int | None = None) -> dict:
    cands = candidates(entries)
    if not cands:
        raise SystemExit("No login-like POST found. Make sure 'Preserve log' was on and you logged in while recording.")
    idx = cands[0][1] if pick is None else pick
    entry = entries[idx]
    req = entry["request"]
    red = Redactor()
    parts = urlsplit(req["url"])
    kind, fields = req_fields(req)

    headers = {}
    for h in req.get("headers", []):
        n = h["name"].lstrip(":")
        if n.lower() in DROP_HEADERS:
            red.note(h["value"])
            continue
        if n.lower() in KEEP_HEADERS:
            headers[n] = red.url(h["value"]) if n.lower() in ("referer", "origin") and h["value"].startswith("http") else h["value"]
    for c in req.get("cookies", []):
        red.note(c.get("value", ""))
    for h in entry["response"].get("headers", []):
        if h["name"].lower() == "set-cookie":
            red.note(h["value"].split(";", 1)[0].split("=", 1)[-1])

    templ = {n: red.field(n, v) for n, v in fields}
    path = parts.path + (("?" + urlencode([(k, red.field(k, v)) for k, v in parse_qsl(parts.query, keep_blank_values=True)], safe="{}:")) if parts.query else "")

    resp = entry["response"]
    body = response_text(entry).lower()
    ok_hits = [w for w in SUCCESS_WORDS if w in body]
    bad_hits = [w for w in FAILURE_WORDS if w in body]
    chain = redirect_chain(entries, idx, red)

    # keepalive guess: later same-host requests whose path looks like keepalive
    host = parts.netloc
    ka = None
    for e in entries[idx + 1:]:
        p = urlsplit(e["request"]["url"])
        if p.netloc == host and KEEPALIVE_PATH.search(p.path) and e["request"]["method"] in ("GET", "POST"):
            ka = {"path": p.path, "method": e["request"]["method"], "interval_seconds": 300}
            break

    draft = {
        "schema_version": 1,
        "ssid": "University_Hostel",
        "portal_host": host,
        "scheme": parts.scheme,
        "login": {
            "path": path,
            "method": req["method"],
            "content_type": "json" if kind == "json" else "form",
            "headers": headers,
            "fields": templ,
        },
        "success": {
            "status_in": [resp["status"]],
            "body_contains": [],
            "body_not_contains": [],
            "redirect_location_contains": None,
            "json_path_equals": None,
        },
        "bad_credentials": {"body_contains": [], "status_in": []},
        "keepalive": ka,
        "fallback": {"type": "none"},
        "_analysis": {
            "NOTE": "Draft only. Review every field. Delete this _analysis block when done.",
            "chosen_request_index": idx,
            "other_candidates": [{"index": i, "score": s, "url": red.url(e["request"]["url"])} for s, i, e in cands if i != idx][:5],
            "redirect_chain": chain,
            "response_status": resp["status"],
            "response_mime": (resp.get("content") or {}).get("mimeType"),
            "success_keywords_seen_in_response": ok_hits,
            "failure_keywords_seen_in_response": bad_hits,
            "fields_needing_review": sorted({v for v in templ.values() if v.startswith("{{REVIEW")}),
            "hint": "Capture a second HAR with a deliberately WRONG password to learn bad_credentials/body text, then diff.",
        },
    }
    # pre-fill success hints conservatively
    if chain and chain[-1].get("location"):
        draft["_analysis"]["hint_redirect_location"] = chain[-1]["location"]
    return draft, red


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("har")
    ap.add_argument("-o", "--output", help="write redacted draft here (default: stdout)")
    ap.add_argument("--list", action="store_true", help="list candidate login requests and exit")
    ap.add_argument("--pick", type=int, help="entry index to use instead of the best-scoring candidate")
    a = ap.parse_args(argv)

    entries = load_har(a.har)
    if a.list:
        red = Redactor()
        for s, i, e in candidates(entries):
            print(f"#{i}\tscore={s}\t{e['request']['method']} {red.url(e['request']['url'])}\t-> {e['response']['status']}")
        return 0

    draft, red = analyze(entries, a.pick)
    text = json.dumps(draft, indent=2)
    leaked = [s for s in red.secrets if s in text]
    if leaked:
        print(f"ABORT: redaction check failed ({len(leaked)} secret value(s) would be emitted). Nothing written.", file=sys.stderr)
        return 2
    if a.output:
        with open(a.output, "w", encoding="utf-8") as f:
            f.write(text + "\n")
        print(f"Wrote redacted draft to {a.output}. Review it before use; secrets were replaced by placeholders.", file=sys.stderr)
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
