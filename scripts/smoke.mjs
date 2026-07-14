#!/usr/bin/env node
/**
 * Stride verify/smoke harness — the deterministic "verifier an agent can't talk
 * past". Boots each surface over its real transport and asserts behavior:
 *   - HTTP API: /health + demo endpoints on a real socket; the { error, requestId }
 *     error contract on a failing request
 *   - MCP server: initialize -> tools/list -> tools/call over stdio JSON-RPC, and
 *     that stdout stays PURE JSON (any stray line fails the run)
 *   - CLI: `analyze --demo --json`, plus byte-identical `next`/`plan` reruns
 *     (the determinism claim, with STRIDE_NOW pinned)
 * Runs fully offline (no secrets, no network). Exits non-zero on any failure.
 *
 * Each surface runs via `node --import tsx <entry>.ts` (single killable process,
 * no build step required). Run with: `node scripts/smoke.mjs` (= `pnpm verify`).
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const NOW = '2026-07-09T00:00:00Z';
const DATA_DIR = path.join(root, '.stride-smoke');
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

/**
 * Build the child env for a spawned surface. Scrubs live-credential vars
 * (`ANTHROPIC_API_KEY`, any `STRAVA_*`) so demo/verify runs ALWAYS take the
 * deterministic, offline no-LLM / no-network branch — even on a developer/CI
 * machine that has those set. Without this, `pnpm verify` stops being
 * reproducible the moment a key is present.
 */
export function childEnv(extraEnv = {}) {
  const env = { ...process.env, STRIDE_NOW: NOW, STRIDE_DATA_DIR: DATA_DIR, ...extraEnv };
  delete env.ANTHROPIC_API_KEY;
  for (const key of Object.keys(env)) {
    if (key.startsWith('STRAVA_')) delete env[key];
  }
  return env;
}

function spawnTs(entry, args = [], extraEnv = {}) {
  const child = spawn(process.execPath, ['--import', 'tsx', entry, ...args], {
    cwd: root,
    env: childEnv(extraEnv),
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  children.push(child);
  return child;
}

/**
 * Kill a child and any grandchildren. `tsx` re-spawns node, so a bare
 * `child.kill()` can orphan the real worker — on Windows we must kill the tree
 * via taskkill, elsewhere a SIGKILL to the group/pid.
 */
function killTree(child) {
  if (!child || child.killed || child.exitCode !== null) return;
  try {
    if (process.platform === 'win32') {
      spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
    } else {
      child.kill('SIGKILL');
    }
  } catch {
    // best effort
  }
}

/** Run a child to completion, capturing stdout, with a hard timeout + cleanup. */
function runToExit(child, timeoutMs) {
  return new Promise((resolve) => {
    let out = '';
    let err = '';
    let timedOut = false;
    child.stdout.on('data', (d) => {
      out += d.toString();
    });
    child.stderr.on('data', (d) => {
      err += d.toString();
    });
    const timer = setTimeout(() => {
      timedOut = true;
      killTree(child);
    }, timeoutMs);
    timer.unref?.();
    child.on('exit', (code) => {
      clearTimeout(timer);
      resolve({ code, out, err, timedOut });
    });
    child.on('error', (e) => {
      clearTimeout(timer);
      resolve({ code: null, out, err: `${err}${e}`, timedOut });
    });
  });
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

  try {
    const up = await waitFor(async () => (await fetch(`${base}/health`)).ok, 15000);
    if (!up) {
      fail(
        'API boots and serves /health',
        `server did not come up on ${base}\n${stderr.slice(-400)}`,
      );
      return;
    }
    pass('API boots and serves /health');

    const analyze = await (await fetch(`${base}/analyze/demo`)).json();
    assert(
      analyze?.metrics?.tss > 0,
      'GET /analyze/demo returns metrics.tss > 0',
      `got ${JSON.stringify(analyze).slice(0, 160)}`,
    );
    assert(
      typeof analyze?.disclaimer === 'string' && analyze.disclaimer.length > 0,
      'GET /analyze/demo carries the safety disclaimer',
      `got ${JSON.stringify(analyze?.disclaimer)}`,
    );

    const pmc = await (await fetch(`${base}/pmc?demo=true`)).json();
    assert(Array.isArray(pmc?.pmc) && pmc.pmc.length > 0, 'GET /pmc?demo returns a PMC series');

    const planRes = await fetch(`${base}/plan`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ demo: true, race: '10k', weeks: 6 }),
    });
    const plan = await planRes.json();
    assert(
      plan?.plan?.weeks?.length === 6 && plan?.validation?.valid === true,
      'POST /plan (demo) returns a valid 6-week plan',
    );

    // Error contract: a failing request returns { error, requestId } with a
    // matching x-request-id header (routed through onError, not a bare 400).
    const bad = await fetch(`${base}/plan`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ weeks: 'nope' }),
    });
    const badBody = await bad.json();
    assert(bad.status === 400, 'POST /plan rejects a bad body with 400', `got ${bad.status}`);
    assert(
      typeof badBody?.error === 'string' &&
        typeof badBody?.requestId === 'string' &&
        bad.headers.get('x-request-id') === badBody.requestId,
      'error responses carry { error, requestId } + matching x-request-id header',
      `body=${JSON.stringify(badBody)} header=${bad.headers.get('x-request-id')}`,
    );
  } finally {
    killTree(child);
  }
}

// ---------------------------------------------------------------- MCP
async function checkMcp() {
  log('\nMCP (stdio JSON-RPC):');
  const child = spawnTs('apps/mcp/src/index.ts');
  let buf = '';
  const responses = new Map();
  const stdoutViolations = [];
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
          // stdout is the MCP protocol channel — it MUST be pure JSON. A stray
          // console.log is the one thing this discipline exists to catch.
          stdoutViolations.push(line);
        }
      }
      nl = buf.indexOf('\n');
    }
  });
  const send = (obj) => child.stdin.write(`${JSON.stringify(obj)}\n`);

  try {
    send({
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: {
        protocolVersion: '2024-11-05',
        capabilities: {},
        clientInfo: { name: 'smoke', version: '1' },
      },
    });
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

    send({
      jsonrpc: '2.0',
      id: 3,
      method: 'tools/call',
      params: { name: 'get_training_load', arguments: { demo: true } },
    });
    await waitFor(async () => responses.has(3), 8000);
    const text = responses.get(3)?.result?.content?.[0]?.text ?? '';
    assert(text.includes('CTL'), 'tools/call get_training_load returns computed load', text.slice(0, 120));

    // Give any stray output a beat to flush, then police stdout purity.
    await new Promise((r) => setTimeout(r, 200));
    assert(
      stdoutViolations.length === 0,
      'MCP stdout is pure JSON (no stray non-JSON lines)',
      `saw ${stdoutViolations.length} non-JSON line(s): ${JSON.stringify(stdoutViolations.slice(0, 3))}`,
    );
  } finally {
    killTree(child);
  }
}

// ---------------------------------------------------------------- CLI
async function checkCli() {
  log('\nCLI:');
  const analyze = await runToExit(
    spawnTs('apps/cli/src/index.ts', ['analyze', '--demo', '--json']),
    20000,
  );
  if (analyze.timedOut) {
    fail('stride analyze --demo --json completes', 'timed out');
  } else {
    try {
      const parsed = JSON.parse(analyze.out);
      assert(
        parsed?.metrics?.tss > 0,
        'stride analyze --demo --json emits metrics.tss > 0',
        `got ${analyze.out.slice(0, 160)}`,
      );
    } catch (err) {
      fail('stride analyze --demo --json emits valid JSON', String(err));
    }
  }

  // Determinism: with STRIDE_NOW pinned, `next` and `plan` (demo) must produce
  // byte-identical output across runs (the diffable-output guarantee).
  for (const args of [
    ['next', '--demo'],
    ['plan', '--demo', '--race', '10k', '--weeks', '6'],
  ]) {
    const a = await runToExit(spawnTs('apps/cli/src/index.ts', args), 20000);
    const b = await runToExit(spawnTs('apps/cli/src/index.ts', args), 20000);
    if (a.timedOut || b.timedOut) {
      fail(`stride ${args.join(' ')} is deterministic`, 'a run timed out');
      continue;
    }
    assert(
      a.out.length > 0 && a.out === b.out,
      `stride ${args.join(' ')} output is byte-identical across runs`,
      `len(a)=${a.out.length} len(b)=${b.out.length}`,
    );
  }
}

function cleanup() {
  for (const c of children) killTree(c);
  // Remove the throwaway smoke store so runs don't leak state on disk.
  try {
    fs.rmSync(DATA_DIR, { recursive: true, force: true });
  } catch {
    // best effort
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

// Only run the harness when invoked directly (`node scripts/smoke.mjs`), not
// when imported (e.g. by a unit test exercising `childEnv`).
if (process.argv[1]?.endsWith('smoke.mjs')) {
  main().catch((err) => {
    console.error(err);
    cleanup();
    process.exit(1);
  });
}
