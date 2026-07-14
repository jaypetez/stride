#!/usr/bin/env node
/**
 * Stride verify/smoke harness — the deterministic "verifier an agent can't talk
 * past". Boots each surface over its real transport and asserts behavior:
 *   - HTTP API: /health + demo endpoints on a real socket
 *   - MCP server: initialize -> tools/list -> tools/call over stdio JSON-RPC
 *   - CLI: `analyze --demo --json` output
 * Runs fully offline (no secrets, no network). Exits non-zero on any failure.
 *
 * Each surface runs via `node --import tsx <entry>.ts` (single killable process,
 * no build step required). Run with: `node scripts/smoke.mjs` (= `pnpm verify`).
 */
import { spawn } from 'node:child_process';
import net from 'node:net';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const NOW = '2026-07-09T00:00:00Z';
const children = [];
let failures = 0;

function log(msg) {
  process.stdout.write(`${msg}\n`);
}
function pass(name) {
  log(`  ✓ ${name}`);
}
function fail(name, detail) {
  failures++;
  log(`  ✗ ${name}\n      ${detail}`);
}
function assert(cond, name, detail) {
  if (cond) pass(name);
  else fail(name, detail ?? 'assertion failed');
}

function spawnTs(entry, args = [], extraEnv = {}) {
  const child = spawn(process.execPath, ['--import', 'tsx', entry, ...args], {
    cwd: root,
    env: { ...process.env, STRIDE_NOW: NOW, STRIDE_DATA_DIR: path.join(root, '.stride-smoke'), ...extraEnv },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  children.push(child);
  return child;
}

async function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

async function waitFor(fn, timeoutMs, everyMs = 150) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      if (await fn()) return true;
    } catch {
      // keep polling
    }
    await new Promise((r) => setTimeout(r, everyMs));
  }
  return false;
}

// ---------------------------------------------------------------- API
async function checkApi() {
  log('\nAPI (HTTP):');
  const port = await freePort();
  const base = `http://127.0.0.1:${port}`;
  const child = spawnTs('apps/api/src/index.ts', [], { STRIDE_API_PORT: String(port) });
  let stderr = '';
  child.stderr.on('data', (d) => {
    stderr += d.toString();
  });

  const up = await waitFor(async () => (await fetch(`${base}/health`)).ok, 15000);
  if (!up) {
    fail('API boots and serves /health', `server did not come up on ${base}\n${stderr.slice(-400)}`);
    return;
  }
  pass('API boots and serves /health');

  const analyze = await (await fetch(`${base}/analyze/demo`)).json();
  assert(analyze?.metrics?.tss > 0, 'GET /analyze/demo returns metrics.tss > 0', `got ${JSON.stringify(analyze).slice(0, 160)}`);

  const pmc = await (await fetch(`${base}/pmc?demo=true`)).json();
  assert(Array.isArray(pmc?.pmc) && pmc.pmc.length > 0, 'GET /pmc?demo returns a PMC series');

  const planRes = await fetch(`${base}/plan`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ demo: true, race: '10k', weeks: 6 }),
  });
  const plan = await planRes.json();
  assert(plan?.plan?.weeks?.length === 6 && plan?.validation?.valid === true, 'POST /plan (demo) returns a valid 6-week plan');

  const bad = await fetch(`${base}/plan`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ weeks: 'nope' }),
  });
  assert(bad.status === 400, 'POST /plan rejects a bad body with 400', `got ${bad.status}`);
}

// ---------------------------------------------------------------- MCP
async function checkMcp() {
  log('\nMCP (stdio JSON-RPC):');
  const child = spawnTs('apps/mcp/src/index.ts');
  let buf = '';
  const responses = new Map();
  child.stdout.on('data', (d) => {
    buf += d.toString();
    let nl = buf.indexOf('\n');
    while (nl >= 0) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (line) {
        try {
          const msg = JSON.parse(line);
          if (msg.id !== undefined) responses.set(msg.id, msg);
        } catch {
          // ignore non-JSON lines
        }
      }
      nl = buf.indexOf('\n');
    }
  });
  const send = (obj) => child.stdin.write(`${JSON.stringify(obj)}\n`);

  send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'smoke', version: '1' } } });
  const inited = await waitFor(async () => responses.has(1), 15000);
  if (!inited) {
    fail('MCP initialize handshake', 'no response to initialize');
    return;
  }
  pass('MCP initialize handshake');

  send({ jsonrpc: '2.0', method: 'notifications/initialized' });
  send({ jsonrpc: '2.0', id: 2, method: 'tools/list' });
  await waitFor(async () => responses.has(2), 8000);
  const tools = responses.get(2)?.result?.tools ?? [];
  assert(tools.length === 8, 'tools/list returns 8 tools', `got ${tools.length}`);

  send({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'get_training_load', arguments: { demo: true } } });
  await waitFor(async () => responses.has(3), 8000);
  const text = responses.get(3)?.result?.content?.[0]?.text ?? '';
  assert(text.includes('CTL'), 'tools/call get_training_load returns computed load', text.slice(0, 120));
}

// ---------------------------------------------------------------- CLI
async function checkCli() {
  log('\nCLI:');
  const child = spawnTs('apps/cli/src/index.ts', ['analyze', '--demo', '--json']);
  let out = '';
  child.stdout.on('data', (d) => {
    out += d.toString();
  });
  const done = await new Promise((resolve) => child.on('exit', () => resolve(true)));
  if (!done) return;
  try {
    const parsed = JSON.parse(out);
    assert(parsed?.metrics?.tss > 0, 'stride analyze --demo --json emits metrics.tss > 0', `got ${out.slice(0, 160)}`);
  } catch (err) {
    fail('stride analyze --demo --json emits valid JSON', String(err));
  }
}

function cleanup() {
  for (const c of children) {
    try {
      c.kill();
    } catch {
      // best effort
    }
  }
}

async function main() {
  log('Stride smoke harness (offline, STRIDE_NOW pinned)');
  try {
    await checkApi();
    await checkMcp();
    await checkCli();
  } finally {
    cleanup();
  }
  log('');
  if (failures > 0) {
    log(`FAILED: ${failures} check(s) did not pass.`);
    process.exit(1);
  }
  log('All smoke checks passed.');
  process.exit(0);
}

main().catch((err) => {
  console.error(err);
  cleanup();
  process.exit(1);
});
