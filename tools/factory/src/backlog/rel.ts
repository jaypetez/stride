import type { WorkstreamBacklog } from '../types.ts';

export const backlog: WorkstreamBacklog = {
  workstream: {
    key: 'REL',
    title: 'Release & Ops',
    area: 'release',
    description:
      'Getting Stride Groups to users and keeping it running: Cloudflare dev/prod environments and deploys, signed APK releases with provenance on GitHub Releases (Obtainium-installable), versioning, observability, backups, and the v0.1 release checklist.',
  },
  epics: [
    {
      key: 'REL-E1',
      title: 'Cloud environments & deploys',
      phase: 'P1',
      summary:
        'Repeatable dev and prod Cloudflare environments, deployed from CI with migrations applied safely and a post-deploy smoke test.',
    },
    {
      key: 'REL-E2',
      title: 'Android distribution',
      phase: 'P6',
      summary:
        'Signed, attested APKs on GitHub Releases with versions derived from changesets and install/update instructions via Obtainium. Play Store is parked.',
    },
    {
      key: 'REL-E3',
      title: 'Observability & operations',
      phase: 'P6',
      summary:
        'Structured logs with request ids, Workers analytics, privacy-respecting crash reports, D1 backup/restore runbook, and the v0.1 release checklist.',
    },
  ],
  issues: [
    {
      key: 'REL-001',
      epic: 'REL-E1',
      title: 'Define Cloudflare dev/prod environments and a deploy workflow',
      phase: 'P1',
      priority: 'high',
      size: 'M',
      type: 'chore',
      agent: true,
      context:
        'apps/cloud/wrangler.jsonc gets `env.dev` and `env.prod` with separate D1 databases, Durable Object namespaces, Queues and routes. A GitHub workflow deploys `dev` on merge to main and `prod` on a release tag, using GitHub environments for secrets (docs/adr/0005-*.md).',
      acceptance: [
        'wrangler.jsonc declares dev and prod environments with distinct bindings and routes.',
        '.github/workflows/deploy-cloud.yml runs `wrangler d1 migrations apply` then `wrangler deploy` for the target env.',
        'Prod deploy requires the `prod` GitHub environment (manual approval).',
        'Actions SHA-pinned; token permissions least-privilege; no secrets echoed.',
      ],
      testPlan: [
        '`pnpm --filter @stride/cloud exec wrangler deploy --dry-run --env dev` succeeds in CI (no credentials needed).',
        'actionlint passes on deploy-cloud.yml.',
      ],
      files: [
        'apps/cloud/wrangler.jsonc',
        '.github/workflows/deploy-cloud.yml',
        'docs/release/environments.md',
      ],
      dependsOn: ['CLOUD-001', 'CLOUD-002', 'GOV-010'],
    },
    {
      key: 'REL-010',
      epic: 'REL-E1',
      title: 'Add a post-deploy smoke test against the deployed Worker',
      phase: 'P1',
      priority: 'medium',
      size: 'S',
      type: 'test',
      agent: true,
      context:
        'After each deploy, hit `/health` and an authenticated no-op with a CI-only test user to catch broken bindings or migrations, and fail the workflow (prompting rollback) if it does not pass.',
      acceptance: [
        'scripts/cloud-smoke.mjs checks `/health` (version + migration level) and returns non-zero on failure.',
        'deploy-cloud.yml runs it after deploy and prints a `wrangler rollback` hint on failure.',
        'The same script runs offline against `wrangler dev` in CI.',
      ],
      testPlan: [
        'CI job runs `scripts/cloud-smoke.mjs` against a local `wrangler dev` instance and passes.',
      ],
      files: ['scripts/cloud-smoke.mjs', '.github/workflows/deploy-cloud.yml'],
      dependsOn: ['REL-001'],
    },
    {
      key: 'REL-007',
      epic: 'REL-E1',
      title: 'Seed the dev environment with demo groups and athletes',
      phase: 'P2',
      priority: 'low',
      size: 'S',
      type: 'chore',
      agent: true,
      context:
        'The dev environment should look alive for manual testing: a demo group, several synthetic athletes with activities (reusing the existing demo fixtures in packages/core), a program and chat history. Reuse the QA-002 seed script rather than writing a second one.',
      acceptance: [
        '`pnpm --filter @stride/cloud seed --env dev` loads the QA-002 seed into the remote dev D1.',
        'Seeding is idempotent and refuses to run against `prod`.',
        'Seeded users are flagged as synthetic and excluded from any metrics.',
      ],
      testPlan: [
        '`pnpm --filter @stride/cloud test` — seed idempotency test against local D1 (run twice, same row counts).',
      ],
      files: ['apps/cloud/scripts/seed.ts'],
      dependsOn: ['QA-002', 'REL-001'],
    },
    {
      key: 'REL-002',
      epic: 'REL-E2',
      title: 'Build signed release APKs with build provenance attestation',
      phase: 'MVP',
      priority: 'high',
      size: 'M',
      type: 'chore',
      agent: true,
      context:
        'Distribution is via GitHub Releases while off the Play Store. A tag-triggered workflow builds a signed universal APK (and AAB for later Play use), attests provenance with `actions/attest-build-provenance`, and attaches both plus SHA-256 sums to the release.',
      acceptance: [
        '.github/workflows/release-android.yml triggers on `android-v*` tags and runs `./gradlew -p android assembleRelease bundleRelease`.',
        'Keystore is decoded from the `release` environment secrets into a temp file that is deleted after the build.',
        'Release assets: APK, AAB, `SHA256SUMS`; provenance attestation is verifiable with `gh attestation verify`.',
        'R8 mapping file uploaded as a workflow artifact (not a public asset).',
      ],
      testPlan: [
        'The workflow runs `assembleRelease` with a throwaway CI-generated keystore on PRs touching it (no secrets needed).',
        'actionlint passes.',
      ],
      files: ['.github/workflows/release-android.yml', 'android/app/build.gradle.kts'],
      dependsOn: ['ANDF-001', 'ANDF-005', 'GOV-015'],
    },
    {
      key: 'REL-003',
      epic: 'REL-E2',
      title: 'Tie Android versionName/versionCode to changesets',
      phase: 'MVP',
      priority: 'medium',
      size: 'S',
      type: 'chore',
      agent: true,
      context:
        'The repo versions packages together with Changesets. The Android app should take versionName from a small `android/version.json` bumped alongside, with a monotonically increasing versionCode, so release notes and tags stay consistent.',
      acceptance: [
        '`android/version.json` holds `versionName` and `versionCode`; Gradle reads it.',
        'A script bumps both during `pnpm version` (changeset version) when android/ changed.',
        'CI fails if versionCode does not increase relative to the last `android-v*` tag on release.',
      ],
      testPlan: [
        '`pnpm --filter @stride/factory test` or a dedicated vitest for the bump script; `./gradlew -p android :app:help` prints the version.',
      ],
      files: [
        'android/version.json',
        'scripts/android-version.mjs',
        'android/app/build.gradle.kts',
      ],
      dependsOn: ['ANDF-001'],
    },
    {
      key: 'REL-008',
      epic: 'REL-E2',
      title: 'Document install and updates via Obtainium',
      phase: 'MVP',
      priority: 'medium',
      size: 'XS',
      type: 'docs',
      agent: true,
      context:
        'Friends and family install the APK directly. Obtainium tracks GitHub Releases and installs updates, so users do not have to sideload manually each time. Include how to verify the signing fingerprint.',
      acceptance: [
        'docs/release/install-android.md covers enabling installs, Obtainium setup for jaypetez/stride with the `android-v` tag filter, and first launch.',
        'Includes the release signing SHA-256 fingerprint and an `apksigner verify --print-certs` example.',
        'README links to the install guide.',
      ],
      testPlan: ['`pnpm check` passes.'],
      files: ['docs/release/install-android.md', 'README.md'],
      dependsOn: ['REL-002'],
    },
    {
      key: 'REL-004',
      epic: 'REL-E3',
      title: 'Add structured Worker logs, request ids and analytics',
      phase: 'P1',
      priority: 'medium',
      size: 'S',
      type: 'feature',
      agent: true,
      context:
        'Match the existing API contract: errors return `{ error, requestId }` with an `x-request-id` header. Enable Workers Logs, emit JSON log lines with route, status, latency and user id hash (never content), and write key counters to Workers Analytics Engine.',
      acceptance: [
        'Middleware assigns/propagates `x-request-id` and logs one JSON line per request.',
        'PII redaction: no message bodies, tokens or emails in logs (unit-tested).',
        '`observability.enabled` set in wrangler.jsonc; Analytics Engine dataset bound for request and error counters.',
      ],
      testPlan: [
        '`pnpm --filter @stride/cloud test` — `logging.test.ts` asserts request id round-trip and redaction.',
      ],
      files: [
        'apps/cloud/src/middleware/logging.ts',
        'apps/cloud/wrangler.jsonc',
        'apps/cloud/test/logging.test.ts',
      ],
      dependsOn: ['CLOUD-001'],
    },
    {
      key: 'REL-005',
      epic: 'REL-E3',
      title: 'Add opt-in crash reporting to our own Worker endpoint',
      phase: 'P6',
      priority: 'low',
      size: 'M',
      type: 'feature',
      agent: true,
      context:
        'No third-party crash SDK (privacy and Data safety simplicity). Use ACRA with an HTTP sender pointed at a Worker `/crash` endpoint, opt-in from settings, stripped of user identifiers, stored with short retention.',
      acceptance: [
        'ACRA configured with HTTP sender and opt-in dialog; disabled by default.',
        '`POST /crash` validates and stores reports in D1 with 30-day retention via cron.',
        'Reports contain no account id, email or message content (tested).',
      ],
      testPlan: [
        '`pnpm --filter @stride/cloud test` — `crash.test.ts` (schema, retention sweep).',
        'Android unit test asserts the report field allowlist.',
      ],
      files: ['android/app/src/main/**/CrashReporting.kt', 'apps/cloud/src/routes/crash.ts'],
      dependsOn: ['ANDF-003', 'REL-004'],
    },
    {
      key: 'REL-006',
      epic: 'REL-E3',
      title: 'Write the D1 backup and Time Travel restore runbook',
      phase: 'P6',
      priority: 'medium',
      size: 'S',
      type: 'docs',
      agent: true,
      context:
        'D1 Time Travel allows point-in-time restore for 30 days on paid plans. Document restore drills, plus a weekly `wrangler d1 export` to a private R2 bucket for longer retention, and note that Durable Object chat history is not covered by D1 restore.',
      acceptance: [
        'docs/release/backup-restore.md covers Time Travel restore, export schedule and DO data limits.',
        'A scheduled workflow (or cron Worker) exports prod D1 weekly to private R2 with lifecycle expiry.',
        'Restore drill steps verified against the dev environment and recorded.',
      ],
      testPlan: ['actionlint on the backup workflow; doc reviewed in PR.'],
      files: ['docs/release/backup-restore.md', '.github/workflows/backup-d1.yml'],
      dependsOn: ['REL-001'],
    },
    {
      key: 'REL-009',
      epic: 'REL-E3',
      title: 'Create and run the v0.1 release checklist',
      phase: 'P6',
      priority: 'high',
      size: 'S',
      type: 'task',
      agent: false,
      context:
        'Final human gate before handing the APK to friends: all P0–P6 issues closed or deferred, threat model residual risks accepted, legal pages live, deletion tested end to end, and the release tagged.',
      acceptance: [
        'docs/release/v0.1-checklist.md exists and every item is ticked or explicitly deferred with an issue link.',
        'Prod deploy green with smoke test; `android-v0.1.0` release published with attestation.',
        'At least three group members installed via Obtainium and completed sign-in → join group.',
      ],
      testPlan: ['`gh attestation verify` on the release APK succeeds; checklist PR merged.'],
      files: ['docs/release/v0.1-checklist.md'],
      dependsOn: ['REL-002', 'REL-008', 'REL-010', 'SEC-007', 'SEC-006'],
    },
  ],
};
