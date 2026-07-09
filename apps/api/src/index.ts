import { serve } from '@hono/node-server';
import { buildApp } from './app';
import { loadApiState } from './state';

const state = loadApiState();
const app = buildApp(state);
const port = state.config.apiPort;

serve({ fetch: app.fetch, port }, (info) => {
  console.log(`Stride API listening on http://localhost:${info.port}`);
  console.log(`  Data dir: ${state.store.dir}`);
  console.log(`  Claude: ${state.llm ? 'enabled' : 'disabled (set ANTHROPIC_API_KEY)'}`);
});
