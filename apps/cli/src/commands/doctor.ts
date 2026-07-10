import { loadApp } from '../app';
import { dim, heading, info, success } from '../ui';

/** Preflight: report tooling, configured credentials, and what runs offline. */
export async function doctorCommand(): Promise<void> {
  const app = loadApp();
  const tokens = await app.store.loadTokens().catch(() => null);
  const stravaConfigured = Boolean(app.config.strava.clientId && app.config.strava.clientSecret);

  heading('Environment');
  info(`  Node:            ${process.version}`);
  info(`  Platform:        ${process.platform}`);
  info(`  Data dir:        ${app.store.dir}`);
  info(`  Reference clock: ${app.config.now ? `${app.config.now} (STRIDE_NOW)` : 'system clock'}`);
  info(`  Log level:       ${process.env.STRIDE_LOG ?? 'warn (default)'}`);

  heading('Credentials');
  info(
    `  Strava app:      ${stravaConfigured ? 'configured' : 'not set (connect/sync unavailable)'}`,
  );
  info(`  Strava tokens:   ${tokens ? 'connected' : 'not connected (run `stride connect`)'}`);
  info(
    `  Anthropic key:   ${app.config.anthropicApiKey ? 'set (LLM prose enabled)' : 'not set (deterministic fallback)'}`,
  );

  heading('Runs offline — no credentials needed');
  info('  stride analyze --demo   ·   stride next --demo   ·   stride plan --demo');
  info('  API demo endpoints (?demo=true)   ·   MCP demo tools ({ demo: true })');

  heading('Needs credentials');
  info('  Strava OAuth: connect, sync, and analyzing your own data');
  info('  Anthropic key: optional — enriches coaching prose (never required)');

  success('Preflight complete.');
  dim('  Tip: set STRIDE_NOW=<ISO> for byte-reproducible demo `next`/`plan` output.');
}
