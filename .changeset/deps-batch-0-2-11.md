---
"@stride/api": patch
"@stride/cli": patch
"@stride/config": patch
"@stride/core": patch
"@stride/mcp": patch
"@stride/schemas": patch
"@stride/web": patch
---

Dependency maintenance batch.

Runtime: `hono` 4.13.7 -> 4.13.8, `@tanstack/react-query` 5.102.8 -> 5.103.1,
`dotenv` 17.4.2 -> 18.0.1 (major; every call site already passes
`{ quiet: true }`, so stdout stays clean for the MCP stdio wire).

Security: `ip-address` 10.3.1 -> 10.7.2 (transitive via
`@modelcontextprotocol/sdk` -> `express-rate-limit`), fixing a moderate
advisory (patched in 10.5.1) with a lockfile-only update.

CI: `github/codeql-action` (`init`/`analyze`/`upload-sarif`) 4.38.0 -> 4.38.1,
all three pinned to the same SHA.

Dev tooling: `@commitlint/cli` and `@commitlint/config-conventional` 21.2.2 ->
21.2.3, `@types/node` 26.5.0 -> 26.6.2, `tsx` 4.23.13 -> 4.23.15.
