# HostelAutoLogin – Shared Specification

Applies to both the Windows (Python) and Android (Kotlin) apps.
Goal: reproduce the **normal, authorized** captive-portal login with the user's **own** stored
credentials, and repeat it when the session expires. Nothing else.

## 1. Hard rules (both platforms)

1. Only the user's own credentials, only the normal login flow. No auth bypass, MAC spoofing, scanning or attacking.
2. Never hardcode, log or print passwords, cookies or tokens. All logs/diagnostics pass through a masking filter.
3. Credentials are sent **only** to the pinned portal host (`portal.json: portal_host`). Any other host – including any
   redirect target – is refused *before* a request is made. Redirects are followed manually and each hop is re-checked.
4. Local only. No telemetry, no server, no analytics.
5. Bounded retries: waits of **2s, 5s, 15s, 30s** (4 retries after the first attempt), then FAILURE: stop and notify.
   Wrong credentials (see §5) stop immediately, with no retries, and do not auto-retry until the user changes settings
   or presses "Login Now".
6. The login request is **never guessed**. Endpoint, fields and success check come from `portal.json`.

## 2. State machine

| State | Meaning |
|---|---|
| `OFFLINE` | Not on the configured SSID (or no Wi-Fi). Nothing to do. |
| `CONNECTED` | On the configured SSID, internet not yet checked. |
| `VALIDATED` | Probe succeeded: internet works. Idle; keepalive may run. |
| `LOGIN_NEEDED` | On the SSID and probe failed (captive portal intercepting). |
| `AUTHENTICATING` | Login request(s) in flight. |
| `VERIFYING` | Login returned success per `portal.json`; re-probing. |
| `FAILURE` | Retries exhausted, bad credentials, or config error. Terminal until user action / SSID change. |

Transitions:

```
OFFLINE --ssid matches-------------------------> CONNECTED
CONNECTED --probe ok---------------------------> VALIDATED
CONNECTED --probe fails------------------------> LOGIN_NEEDED
VALIDATED --probe fails (session expired)------> LOGIN_NEEDED
LOGIN_NEEDED --auto-login on / Login Now-------> AUTHENTICATING
AUTHENTICATING --login success-----------------> VERIFYING
AUTHENTICATING --transient error, retries left-> AUTHENTICATING   (after 2s/5s/15s/30s)
AUTHENTICATING --retries exhausted-------------> FAILURE
AUTHENTICATING --bad credentials/config error--> FAILURE          (no retry)
VERIFYING --probe ok---------------------------> VALIDATED
VERIFYING --probe fails------------------------> LOGIN_NEEDED     (counts as an attempt)
any --SSID lost--------------------------------> OFFLINE
FAILURE --SSID changes / user Login Now / settings saved--> CONNECTED
```

Paused (user toggle) suspends all transitions out of `LOGIN_NEEDED`; probing/status still update.
Rate guard: at most one full login cycle (first try + 4 retries) per 2 minutes unless user-initiated.

## 3. Probe

`GET http://www.msftconnecttest.com/connecttest.txt`, no redirects followed, short timeout (5s).
Internet OK iff status 200 and body (stripped) == `Microsoft Connect Test`.
Anything else (redirect, different body, timeout) = not validated.
Windows loop interval: 15s. Android: driven by `NetworkCallback` + `NET_CAPABILITY_VALIDATED`, with the same probe as confirmation.

The probe is the *only* request that goes to a non-portal host and it carries no credentials.

## 4. `portal.json` schema (v1)

```jsonc
{
  "schema_version": 1,
  "ssid": "University_Hostel",            // default SSID (user can override in Setup)
  "portal_host": "172.16.16.16",          // pinned; host[:port]. Credentials go ONLY here.
  "scheme": "http",                       // "http" | "https"
  "login": {
    "path": "/REPLACE_ME",                // path (+ fixed query) on portal_host. Absolute URLs must match portal_host.
    "method": "POST",
    "content_type": "form",               // "form" (urlencoded) | "json"
    "headers": { "Referer": "http://172.16.16.16/" },   // never Cookie/Authorization
    "fields": {                           // placeholders: {{username}} {{password}} {{timestamp_ms}} {{dyn:NAME}}
      "username": "{{username}}",
      "password": "{{password}}"
    },
    "pre_fetch": {                        // optional: GET a page first (cookies/hidden tokens)
      "path": "/",
      "extract": [ { "name": "csrf", "regex": "name=\"csrf\" value=\"([^\"]+)\"" } ]   // usable as {{dyn:csrf}}
    }
  },
  "success": {                            // ALL configured checks must pass
    "status_in": [200, 302],
    "body_contains": [],                  // any-of list; empty = not checked
    "body_not_contains": ["Invalid", "incorrect"],
    "redirect_location_contains": null,
    "json_path_equals": null              // e.g. {"path": "status", "value": "success"}
  },
  "bad_credentials": {                    // matching any => FAILURE immediately, no retries
    "body_contains": ["Invalid username", "incorrect password"],
    "status_in": [401, 403]
  },
  "keepalive": {                          // optional; null/absent = disabled
    "path": "/REPLACE_ME",
    "method": "GET",
    "interval_seconds": 300
  },
  "fallback": { "type": "none" }          // "none" | "browser" (WebView on Android / pluggable hook on Windows)
}
```

Rules: unknown keys are ignored; `login.path` containing `REPLACE_ME` => state `FAILURE` with message
"portal.json not configured" (never a network call). Placeholder values come from secure storage at request time only;
they are never written to logs or the config file.

## 5. Result classification

| Outcome | Condition | Action |
|---|---|---|
| `SUCCESS` | success checks pass | → VERIFYING |
| `BAD_CREDENTIALS` | matches `bad_credentials` | FAILURE, no retry, notify |
| `CONFIG_ERROR` | placeholder path, bad template, host mismatch, redirect to foreign host | FAILURE, no retry, notify |
| `TRANSIENT` | timeout, connection error, 5xx, success check fails w/o credential match | retry per backoff |

## 6. Logging & masking

Every log line passes through a masker that replaces: the stored password and username-bound secrets (exact match),
values of keys named like `pass|pwd|token|cookie|session|auth|secret|csrf|sid`, `Cookie`/`Set-Cookie`/`Authorization` header values,
and long high-entropy strings (≥24 chars). Request bodies are never logged; only method, host, path, status, and timings.

## 7. Notifications

On FAILURE: toast (Windows) / notification (Android) with a reason (no secrets). On recovery: silent.

## 8. Shared test vectors

Both platforms implement the same unit tests for: state transitions (§2), backoff sequence (§1.5), masking (§6),
host pinning incl. redirect to foreign host / userinfo tricks like `http://172.16.16.16@evil.com/` (§1.3).
