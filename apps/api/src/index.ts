import { serve } from '@hono/node-server';
import { isLoopbackHost } from '@stride/core';
import { buildApp } from './app';
import { loadApiState } from './state';

const state = loadApiState();
const app = buildApp(state);
const port = state.config.apiPort;
// Loopback by default (STRIDE_API_HOST): owner data and the state-changing
// routes (/sync, /plan) must not be reachable from the LAN unless asked for.
const hostname = state.config.apiHost;

serve({ fetch: app.fetch, port, hostname }, (info) => {
  const shown = hostname.includes(':') ? `[${hostname}]` : hostname;
  console.log(`Stride API listening on http://${shown}:${info.port}`);
  console.log(`  Data dir: ${state.store.dir}`);
  console.log(`  Claude: ${state.llm ? 'enabled' : 'disabled (set ANTHROPIC_API_KEY)'}`);
  console.log(
    `  Auth: ${state.config.apiToken ? 'bearer token required' : 'none (loopback trust)'}`,
  );
  if (!isLoopbackHost(hostname) && !state.config.apiToken) {
    console.warn(
      `  WARNING: bound to non-loopback ${hostname} without STRIDE_API_TOKEN — anyone on the ` +
        'network can read your data. Set STRIDE_API_TOKEN or STRIDE_API_HOST=127.0.0.1.',
    );
  }
});
