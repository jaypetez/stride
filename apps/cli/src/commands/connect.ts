import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import http from 'node:http';
import {
  assertStravaConfigured,
  buildAuthorizeUrl,
  exchangeCodeForTokens,
  StravaClient,
} from '@stride/core';
import { AthleteProfile } from '@stride/schemas';
import { getProfile, loadApp, todayIso } from '../app';
import { dim, errorMsg, info, success } from '../ui';

/** Default time to wait for the browser OAuth round-trip before giving up. */
const DEFAULT_OAUTH_TIMEOUT_MS = 5 * 60_000;

function openBrowser(url: string): void {
  try {
    if (process.platform === 'win32')
      spawn('cmd', ['/c', 'start', '', url], { detached: true, stdio: 'ignore' });
    else if (process.platform === 'darwin')
      spawn('open', [url], { detached: true, stdio: 'ignore' });
    else spawn('xdg-open', [url], { detached: true, stdio: 'ignore' });
  } catch {
    // If we can't open a browser, the user can copy the printed URL.
  }
}

export interface WaitForOAuthCodeOptions {
  /** Port to bind. Use `0` to let the OS pick a free port (see `onListening`). */
  port: number;
  /** Interface to bind. Defaults to loopback so the code isn't LAN-reachable. */
  host?: string;
  /** Only requests to this path are treated as the OAuth callback. */
  path?: string;
  /** The CSRF `state` value that must be echoed back by Strava. */
  state: string;
  /** How long to wait before rejecting with a timeout error. */
  timeoutMs?: number;
  /** Called once the server is listening, with the actually-bound address. */
  onListening?: (address: { port: number; host: string }) => void;
}

/**
 * Run a one-shot loopback HTTP server that captures the OAuth `code` Strava
 * redirects back to. Bound to `127.0.0.1` by default (never all interfaces),
 * enforces the CSRF `state` check, times out so the CLI can't hang forever, and
 * always closes the server (success, failure, or timeout) so no listener leaks.
 */
export function waitForOAuthCode(opts: WaitForOAuthCodeOptions): Promise<{ code: string }> {
  const {
    port,
    host = '127.0.0.1',
    path = '/',
    state,
    timeoutMs = DEFAULT_OAUTH_TIMEOUT_MS,
    onListening,
  } = opts;

  return new Promise<{ code: string }>((resolve, reject) => {
    let settled = false;
    const server = http.createServer();
    let timer: ReturnType<typeof setTimeout> | undefined;

    // Single exit path: stop the timer, close the server (so we never leak the
    // listener), then settle the promise exactly once.
    const settle = (err: Error | null, code?: string): void => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      server.close();
      if (err) reject(err);
      else resolve({ code: code ?? '' });
    };

    server.on('request', (req, res) => {
      const reqUrl = new URL(req.url ?? '/', `http://${host}:${port}`);
      if (reqUrl.pathname !== path) {
        res.writeHead(404).end('Not found');
        return;
      }
      const returnedCode = reqUrl.searchParams.get('code');
      const returnedState = reqUrl.searchParams.get('state');
      const oauthError = reqUrl.searchParams.get('error');
      res.writeHead(200, { 'content-type': 'text/html', Connection: 'close' });
      if (oauthError || !returnedCode) {
        res.end('<h2>Stride: authorization failed.</h2><p>You can close this tab.</p>');
        settle(new Error(oauthError ?? 'No authorization code returned.'));
        return;
      }
      if (returnedState !== state) {
        res.end('<h2>Stride: state mismatch.</h2><p>Please try again.</p>');
        settle(new Error('OAuth state mismatch — aborting for safety.'));
        return;
      }
      res.end(
        '<h2>Stride connected ✓</h2><p>You can close this tab and return to the terminal.</p>',
      );
      settle(null, returnedCode);
    });

    server.on('error', (err) => settle(err));

    timer = setTimeout(() => {
      settle(
        new Error(
          `Strava authorization timed out after ${Math.round(timeoutMs / 1000)}s — no callback ` +
            'received. Re-run `stride connect` to try again.',
        ),
      );
    }, timeoutMs);
    // Don't let a pending timeout keep the process alive on its own.
    timer.unref();

    server.listen(port, host, () => {
      const addr = server.address();
      const actualPort = typeof addr === 'object' && addr ? addr.port : port;
      onListening?.({ port: actualPort, host });
    });
  });
}

export async function connectCommand(): Promise<void> {
  const app = loadApp();
  try {
    assertStravaConfigured(app.config);
  } catch (err) {
    errorMsg((err as Error).message);
    return;
  }

  const redirect = new URL(app.config.strava.redirectUri);
  const port = Number(redirect.port || '80');
  const state = randomUUID();
  const authorizeUrl = buildAuthorizeUrl(app.config.strava, state);

  let code: string;
  try {
    // Bind loopback only (127.0.0.1) so the OAuth code is never reachable from
    // the LAN, and time out rather than hang if the browser flow is abandoned.
    const result = await waitForOAuthCode({
      port,
      host: '127.0.0.1',
      path: redirect.pathname,
      state,
      onListening: () => {
        info('Opening Strava authorization in your browser…');
        dim(`If it doesn't open automatically, visit:\n  ${authorizeUrl}\n`);
        openBrowser(authorizeUrl);
      },
    });
    code = result.code;
  } catch (err) {
    errorMsg((err as Error).message);
    return;
  }

  const tokens = await exchangeCodeForTokens(app.config.strava, code);
  await app.store.saveTokens(tokens);

  // Seed the profile from the athlete record (best-effort).
  try {
    const client = new StravaClient({
      config: app.config.strava,
      tokens,
      onTokensRefreshed: (t) => app.store.saveTokens(t),
    });
    const athlete = await client.getAthlete();
    const profile = await getProfile(app.store);
    await app.store.saveProfile(
      AthleteProfile.parse({
        ...profile,
        id: athlete.id ? String(athlete.id) : profile.id,
        name: athlete.firstname
          ? `${athlete.firstname} ${athlete.lastname ?? ''}`.trim()
          : profile.name,
        sex: athlete.sex === 'M' ? 'male' : athlete.sex === 'F' ? 'female' : profile.sex,
        weightKg: athlete.weight ?? profile.weightKg,
        updatedAt: todayIso(app.config),
      }),
    );
  } catch {
    // Non-fatal: profile can be filled in on sync.
  }

  success('Connected to Strava. Run `stride sync` to import your activities.');
}
