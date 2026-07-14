---
"@stride/core": patch
---

Harden the local OAuth connect flow and wire up deauthorization on disconnect.
The CLI callback server now binds `127.0.0.1` only (previously all interfaces,
so the one-time OAuth `code` was reachable from the LAN) and times out after 5
minutes instead of hanging forever if the browser flow is abandoned, always
closing the listener on success, failure, or timeout. The loopback wait is
extracted into a testable `waitForOAuthCode` helper that preserves the CSRF
`state` check. `stride disconnect` now calls `@stride/core`'s `deauthorize`
(exposed on the package's public surface) best-effort to revoke the grant on
Strava's side before deleting local tokens, in both the normal and `--purge`
paths; a failed or offline revoke warns and continues rather than blocking
local cleanup.
