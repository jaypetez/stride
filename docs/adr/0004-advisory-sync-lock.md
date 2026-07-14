# 0004. Advisory sync lock and the CodeQL dismissal rationale

- Status: Accepted
- Date: 2026-07-14

## Context

A Strava sync is a multi-file read-modify-write across the local store
(`activities.json`, `daily-loads.json`, `meta.json`, `profile.json`). Two syncs
running at once — e.g. a CLI `stride sync` while the API's `POST /sync` fires —
could interleave and corrupt the durable series or the backfill watermark.

In-process this is already handled by a write mutex (`writeChain` in the store).
The remaining gap is **cross-process** coordination. Stride is dependency-light
and local-first, so pulling in a native file-locking dependency (`proper-lockfile`
et al.) is unattractive.

Separately, the security tooling reacts to this code: the CodeQL
`security-and-quality` query pack (see `.github/workflows/codeql.yml`) flags the
lock file's `open(..., 'wx')` and the follow-up `readFile` as
`js/insecure-temporary-file` and `js/file-system-race`. Those heuristics assume a
**shared OS temp directory** where an attacker can pre-plant files or win a race.

## Decision

Implement a **cross-process advisory lock** in `packages/core/src/store/lock.ts`
using only the filesystem:

- Acquire by **exclusively creating** the lock file (`open(file, 'wx')`). Exactly
  one racer can win an exclusive create, and `'wx'` refuses to follow a
  pre-planted symlink.
- The lock lives at a **fixed path inside the user's private, single-user Stride
  data dir** (e.g. `~/.stride/sync.lock`), never in a shared temp dir.
- A lock older than `SYNC_LOCK_TTL_MS` (10 min), or unreadable/corrupt, is
  presumed dead and reclaimed, with **bounded retries** so a reclaim race can't
  loop. The authoritative step is always the exclusive create on retry.
- `syncStrava` wraps its body in `acquire` / `finally`-`release`; the API maps a
  `SyncLockError` to HTTP `409`.

Accept — and explicitly annotate — the two CodeQL findings as not applicable to
this threat model, using inline dismissal comments:

```ts
const handle = await open(file, 'wx'); // codeql[js/insecure-temporary-file]
const raw = await readFile(file, 'utf8'); // codeql[js/file-system-race]
```

## Consequences

**Easier:**

- Zero-dependency cross-process safety that composes with the existing
  in-process mutex; well-behaved Stride processes cannot corrupt the store by
  syncing concurrently, and a crashed sync self-heals after the TTL.

**Harder / watch out for:**

- The lock is **advisory**: it coordinates cooperating Stride processes, not
  arbitrary writers. That is sufficient for a single-user local tool and is
  stated in the code.
- The staleness `readFile` is a genuine TOCTOU, but a **benign** one — it only
  decides whether to attempt a reclaim; correctness rests on the exclusive
  create, not the read. This reasoning is the justification for the
  `js/file-system-race` dismissal.
- The single-user private-directory threat model is what makes the
  `js/insecure-temporary-file` dismissal correct; it would **not** hold if the
  lock ever moved to a shared temp dir or Stride became multi-user. Any such
  change must revisit these dismissals. A native OS-level lock would be the
  alternative if that day comes.
