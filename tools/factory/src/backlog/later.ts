import type { WorkstreamBacklog } from '../types.ts';

export const backlog: WorkstreamBacklog = {
  workstream: {
    key: 'LATER',
    title: 'Parked / Later',
    area: 'later',
    description:
      'Deliberately deferred work: photos in chat (with the compliance it requires), Google Play publication, a direct Garmin Connect integration once the program reopens, passkeys/email OTP, a Strava compliance review, and iOS (Swift) scoping. Nothing here is picked up by agents until moved into an active phase.',
  },
  epics: [
    {
      key: 'LATER-E1',
      title: 'Photos in chat',
      phase: 'Later',
      summary:
        'Image sharing in group chat and on activities, with on-device EXIF/GPS stripping, the system Photo Picker, R2 storage behind a custom domain, CSAM scanning and moderation.',
    },
    {
      key: 'LATER-E2',
      title: 'Google Play publication',
      phase: 'Later',
      summary:
        'Everything needed to move from sideloaded APKs to the Play Store under a personal developer account, including the 12-tester / 14-day closed test.',
    },
    {
      key: 'LATER-E3',
      title: 'More integrations, auth and platforms',
      phase: 'Later',
      summary:
        'Direct Garmin Connect API, passkeys and email OTP, a formal Strava compliance review, and scoping a native iOS app in Swift.',
    },
  ],
  issues: [
    {
      key: 'LATER-001',
      epic: 'LATER-E1',
      title: 'Add R2-backed image upload API with signed URLs',
      phase: 'Later',
      priority: 'medium',
      size: 'M',
      type: 'feature',
      agent: true,
      context:
        'Images are uploaded to R2 via short-lived signed URLs and served only through the custom domain (required for Cloudflare CSAM scanning). Access is checked by the authz policy module: only group members may fetch a group image.',
      acceptance: [
        '`POST /groups/:id/images` returns a signed upload URL with size (≤ 5 MB) and content-type (JPEG/WebP) limits.',
        'Images are served via the custom domain with authz checks; r2.dev is disabled.',
        'Deleted messages and deleted accounts remove their images (extends SEC-007).',
      ],
      testPlan: [
        '`pnpm --filter @stride/cloud test` — `images.test.ts` with Miniflare R2 (authz matrix, limits, deletion).',
      ],
      files: ['apps/cloud/src/routes/images.ts', 'apps/cloud/wrangler.jsonc'],
      dependsOn: ['CHAT-001', 'CLOUD-005', 'GOV-016'],
    },
    {
      key: 'LATER-002',
      epic: 'LATER-E1',
      title: 'Pick photos with the Photo Picker and strip EXIF/GPS on device',
      phase: 'Later',
      priority: 'medium',
      size: 'M',
      type: 'feature',
      agent: true,
      context:
        'Use the system Photo Picker (no READ_MEDIA_IMAGES permission). Re-encode the bitmap to WebP before upload so EXIF, including GPS location, is dropped, and downscale to a max edge of 2048 px.',
      acceptance: [
        'Chat composer offers a photo button using `PickVisualMedia`; no media permissions in the merged manifest.',
        'Uploaded bytes contain no EXIF block (asserted by a unit test on the encoder).',
        'Upload progress, retry and cancel are handled via WorkManager.',
      ],
      testPlan: [
        'JVM test decoding the output with an EXIF reader finds no tags; Roborazzi screenshot of the composer.',
      ],
      files: ['android/feature/chat/**', 'android/core/data/**'],
      dependsOn: ['LATER-001', 'ANDF-004'],
    },
    {
      key: 'LATER-003',
      epic: 'LATER-E1',
      title: 'Enable CSAM scanning and an image moderation workflow',
      phase: 'Later',
      priority: 'high',
      size: 'S',
      type: 'task',
      agent: false,
      context:
        'Turn on the Cloudflare CSAM Scanning Tool for the image domain, document the NCMEC reporting process, and extend the report/block flow (SOCIAL-004) so reported images are hidden pending review.',
      acceptance: [
        'CSAM Scanning Tool enabled for the image zone with a verified notification email.',
        'Reported images are hidden for all members until a moderator decision.',
        'docs/security/image-moderation.md documents the escalation and legal reporting path.',
      ],
      testPlan: ['Cloud test: reporting an image hides it from the authz-filtered fetch path.'],
      files: ['docs/security/image-moderation.md'],
      dependsOn: ['LATER-001', 'SOCIAL-004'],
    },
    {
      key: 'LATER-004',
      epic: 'LATER-E2',
      title: 'Prepare Play Data safety form and Health apps declaration',
      phase: 'Later',
      priority: 'medium',
      size: 'S',
      type: 'docs',
      agent: true,
      context:
        'Draft answers for the Data safety form and Health apps declaration from the actual data flows (threat model and privacy policy), plus Health Connect permission rationales per data type. Confirm the app targets the current required API level and is 16 KB page-size compatible.',
      acceptance: [
        'docs/release/play/data-safety.md lists every data type, purpose, sharing and encryption answer.',
        'docs/release/play/health-declaration.md justifies each Health Connect permission.',
        'A CI check asserts targetSdk ≥ the current Play requirement and native libs are 16 KB aligned.',
      ],
      testPlan: ['`./gradlew -p android :app:checkPlayRequirements` (custom task) passes.'],
      files: ['docs/release/play/*', 'android/app/build.gradle.kts'],
      dependsOn: ['SEC-002', 'INTEG-010'],
    },
    {
      key: 'LATER-005',
      epic: 'LATER-E2',
      title: 'Run the 12-tester, 14-day closed test and publish to production',
      phase: 'Later',
      priority: 'medium',
      size: 'M',
      type: 'task',
      agent: false,
      context:
        'Personal developer accounts created after Nov 2023 must run a closed test with at least 12 opted-in testers for 14 continuous days before production access. Upload the AAB from REL-002, complete the store listing and apply for production.',
      acceptance: [
        'Play Console app created; store listing, screenshots, privacy policy URL and deletion URL filled.',
        'Closed test track with ≥ 12 testers opted in for 14 consecutive days.',
        'Production access granted and first production release rolled out.',
      ],
      testPlan: [
        'Play Console shows the closed test requirement as met (screenshot in the issue).',
      ],
      dependsOn: ['LATER-004', 'REL-002'],
    },
    {
      key: 'LATER-006',
      epic: 'LATER-E3',
      title: 'Integrate the Garmin Connect API when the program reopens',
      phase: 'Later',
      priority: 'low',
      size: 'L',
      type: 'feature',
      agent: true,
      context:
        'The Garmin Connect Developer Program paused new sign-ups in 2026; Garmin data currently arrives via Health Connect and FIT import. When access is granted, add the Activity API push/ping flow feeding the normalized ingestion API, with Garmin attribution in the UI.',
      acceptance: [
        'Garmin OAuth connect/disconnect flow on the Worker.',
        'Ping/push webhook handler enqueues FIT downloads and feeds INTEG-001.',
        'Dedup against the same activity already received via Health Connect.',
        'Garmin attribution shown wherever Garmin-sourced data appears.',
      ],
      testPlan: [
        '`pnpm --filter @stride/cloud test` — Garmin webhook + dedup tests against a fake Garmin server.',
      ],
      files: ['apps/cloud/src/integrations/garmin/*'],
      dependsOn: ['INTEG-001'],
    },
    {
      key: 'LATER-007',
      epic: 'LATER-E3',
      title: 'Add passkeys and email OTP sign-in',
      phase: 'Later',
      priority: 'low',
      size: 'M',
      type: 'feature',
      agent: true,
      context:
        'Google sign-in is the v0.1 method. Add passkeys via Credential Manager and Better Auth’s passkey plugin, and email OTP for users without Google accounts (Cloudflare Email Service for delivery).',
      acceptance: [
        'Users can register and sign in with a passkey on Android.',
        'Email OTP sign-in with rate limiting and expiring codes.',
        'Existing accounts can link additional methods.',
      ],
      testPlan: [
        'Cloud auth tests for OTP expiry/rate limit; Android unit tests with a fake CredentialManager.',
      ],
      files: ['apps/cloud/src/auth/*', 'android/feature/auth/**'],
      dependsOn: ['CLOUD-004', 'ANDX-001'],
    },
    {
      key: 'LATER-008',
      epic: 'LATER-E3',
      title: 'Request written Strava confirmation and review compliance',
      phase: 'Later',
      priority: 'medium',
      size: 'XS',
      type: 'task',
      agent: false,
      context:
        'Group visibility and AI use of Strava data are an accepted risk (docs/adr/0006-*.md; GOAL.md §4 TODO). Email developers@strava.com describing the small-group use case and record the answer; flip the strict-mode flags if needed.',
      acceptance: [
        'Email sent describing the use case, athlete count and data handling.',
        'Response (or no-response after 30 days) recorded in docs/adr/0006.',
        '`STRAVA_VISIBILITY` / `STRAVA_AI` defaults updated to match the outcome.',
      ],
      testPlan: [
        'If flags change: `pnpm --filter @stride/cloud test` (strava-flags matrix) passes with the new defaults.',
      ],
      dependsOn: ['SEC-012'],
    },
    {
      key: 'LATER-009',
      epic: 'LATER-E3',
      title: 'Scope a native iOS app in Swift',
      phase: 'Later',
      priority: 'low',
      size: 'S',
      type: 'spike',
      agent: true,
      context:
        'iOS is out of scope for v0.1; if pursued it will be native Swift/SwiftUI against the same Worker API and contract fixtures. Produce a scoping doc only: HealthKit as the Health Connect equivalent, Sign in with Apple requirement, APNs vs FCM, and test strategy.',
      acceptance: [
        'docs/ios/scoping.md covers architecture, HealthKit mapping, auth requirements and App Store review risks.',
        'Lists which contract fixtures (QA-001) the iOS client would reuse.',
        'Rough backlog of epics for an iOS workstream.',
      ],
      testPlan: ['`pnpm check` passes; doc reviewed in PR.'],
      files: ['docs/ios/scoping.md'],
      dependsOn: ['QA-001'],
    },
  ],
};
