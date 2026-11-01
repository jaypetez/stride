/**
 * Thin, retrying wrapper around the `gh` CLI. All GitHub I/O in the factory goes
 * through here so tests can swap it for a fake (`GhClient` interface).
 */
import { execFileSync } from 'node:child_process';

export interface GhClient {
  /** GraphQL query/mutation. Throws on `errors`. */
  graphql<T = any>(query: string, variables?: Record<string, unknown>): T;
  /** REST call. `body` is sent as JSON. */
  rest<T = any>(method: string, path: string, body?: unknown): T;
}

const RETRYABLE =
  /secondary rate limit|abuse detection|was submitted too quickly|HTTP 50[234]|HTTP 429|timeout|ECONNRESET/i;

function sleep(ms: number): void {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

export interface CliGhOptions {
  /** Minimum ms between mutating calls (GitHub content-creation limits ~80/min). */
  mutationSpacingMs?: number;
  log?: (msg: string) => void;
}

export function createCliGh(opts: CliGhOptions = {}): GhClient {
  const spacing = opts.mutationSpacingMs ?? 800;
  const log = opts.log ?? ((m: string) => process.stderr.write(`${m}\n`));
  let lastMutation = 0;

  function run(args: string[], input: string, mutating: boolean): string {
    for (let attempt = 1; ; attempt++) {
      if (mutating) {
        const wait = lastMutation + spacing - Date.now();
        if (wait > 0) sleep(wait);
        lastMutation = Date.now();
      }
      try {
        return execFileSync('gh', args, {
          input,
          encoding: 'utf8',
          maxBuffer: 64 * 1024 * 1024,
          stdio: ['pipe', 'pipe', 'pipe'],
        });
      } catch (err: any) {
        const text = `${err.stderr ?? ''}${err.stdout ?? ''}${err.message ?? ''}`;
        // Secondary (content-creation) limits can take minutes to clear; the seeder
        // is idempotent, so giving up after ~20 min and re-running is also fine.
        if (attempt < 10 && RETRYABLE.test(text)) {
          const backoff = Math.min(300_000, 30_000 * attempt);
          log(`  gh: retryable error (attempt ${attempt}); sleeping ${backoff / 1000}s`);
          sleep(backoff);
          continue;
        }
        throw new Error(`gh ${args.slice(0, 3).join(' ')} failed: ${text.trim().slice(0, 2000)}`);
      }
    }
  }

  return {
    graphql(query, variables = {}) {
      const mutating = /^\s*mutation/.test(query);
      const out = run(
        ['api', 'graphql', '--input', '-'],
        JSON.stringify({ query, variables }),
        mutating,
      );
      const json = JSON.parse(out);
      if (json.errors?.length) {
        throw new Error(`GraphQL error: ${JSON.stringify(json.errors).slice(0, 2000)}`);
      }
      return json.data;
    },
    rest(method, path, body) {
      const args = ['api', '--method', method, path];
      if (body !== undefined) args.push('--input', '-');
      const out = run(args, body === undefined ? '' : JSON.stringify(body), method !== 'GET');
      return out.trim() ? JSON.parse(out) : (undefined as any);
    },
  };
}
