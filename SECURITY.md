# Security Policy

## Supported versions

Stride is pre-1.0. Security fixes are applied to the latest release and `main`.

## Reporting a vulnerability

**Please do not open a public issue for security vulnerabilities.**

Instead, report privately via GitHub's
[private vulnerability reporting](https://github.com/jaypetez/stride/security/advisories/new)
("Report a vulnerability" under the repository's Security tab).

Please include:

- A description of the issue and its impact
- Steps to reproduce
- Affected version/commit

We aim to acknowledge reports within 72 hours.

## Handling of secrets and personal data

Stride is **local-first**: it runs on the user's own machine with the user's own
Strava app credentials and Anthropic API key.

- OAuth tokens are stored locally (`STRIDE_DATA_DIR`, default `.stride/`) with
  restrictive file permissions and are **never** committed or transmitted
  anywhere except to Strava/Anthropic.
- Strava-sourced data is cached locally and expired after 7 days, per the Strava
  API Agreement.
- Never paste real credentials into issues, PRs, or logs. Use `.env` (which is
  git-ignored) and `.env.example` as the template.
