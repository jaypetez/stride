---
"@stride/api": patch
"@stride/cli": patch
"@stride/config": patch
"@stride/core": patch
"@stride/mcp": patch
"@stride/schemas": patch
"@stride/web": patch
---

Patch the `qs` transitive dependency to 6.16.0, clearing two moderate advisories
(GHSA-4mjr-xmp4-gh2g denial of service via attacker-controlled `isBuffer`, and
GHSA-x5fp-wj9c-mxmx array-limit bypass via bracket-key comma parsing). `qs` is
reached through `express`/`body-parser` under `@modelcontextprotocol/sdk`, so it
was in the runtime tree.
