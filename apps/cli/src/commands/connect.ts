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
    code = await new Promise<string>((resolve, reject) => {
      const server = http.createServer((req, res) => {
        const reqUrl = new URL(req.url ?? '/', `http://localhost:${port}`);
        if (reqUrl.pathname !== redirect.pathname) {
          res.writeHead(404).end('Not found');
          return;
        }
        const returnedCode = reqUrl.searchParams.get('code');
        const returnedState = reqUrl.searchParams.get('state');
        const oauthError = reqUrl.searchParams.get('error');
        res.writeHead(200, { 'content-type': 'text/html' });
        if (oauthError || !returnedCode) {
          res.end('<h2>Stride: authorization failed.</h2><p>You can close this tab.</p>');
          server.close();
          reject(new Error(oauthError ?? 'No authorization code returned.'));
          return;
        }
        if (returnedState !== state) {
          res.end('<h2>Stride: state mismatch.</h2><p>Please try again.</p>');
          server.close();
          reject(new Error('OAuth state mismatch — aborting for safety.'));
          return;
        }
        res.end(
          '<h2>Stride connected ✓</h2><p>You can close this tab and return to the terminal.</p>',
        );
        server.close();
        resolve(returnedCode);
      });
      server.on('error', reject);
      server.listen(port, () => {
        info('Opening Strava authorization in your browser…');
        dim(`If it doesn't open automatically, visit:\n  ${authorizeUrl}\n`);
        openBrowser(authorizeUrl);
      });
    });
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
        updatedAt: todayIso(),
      }),
    );
  } catch {
    // Non-fatal: profile can be filled in on sync.
  }

  success('Connected to Strava. Run `stride sync` to import your activities.');
}
