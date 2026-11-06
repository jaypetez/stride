---
"@stride/core": patch
---

Android: fix Garmin key connect failing with HTTP 406 by sending `Accept: */*` on the token exchange, and send a User-Agent on every Garmin request.
