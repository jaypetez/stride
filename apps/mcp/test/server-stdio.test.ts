import { type ChildProcess, spawn } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, describe, expect, it } from 'vitest';

// Resolve paths relative to this test file (works in Vitest's ESM runtime).
const here = path.dirname(fileURLToPath(import.meta.url));
const entry = path.resolve(here, '../src/index.ts');
const repoRoot = path.resolve(here, '../../..');
const dataDir = path.join(os.tmpdir(), `stride-mcp-stdio-${process.pid}-${Date.now()}`);

let child: ChildProcess | undefined;

afterAll(() => {
  child?.kill();
});

// Exercises the REAL stdio JSON-RPC transport (not just the tool functions):
// spawn the server as a child, speak newline-delimited JSON-RPC over stdin/stdout.
// Modeled on the MCP section of scripts/smoke.mjs.
describe('MCP stdio JSON-RPC transport', () => {
  it('completes initialize -> tools/list -> tools/call over stdio', async () => {
    const proc = spawn(process.execPath, ['--import', 'tsx', entry], {
      cwd: repoRoot,
      env: {
        ...process.env,
        STRIDE_DATA_DIR: dataDir,
        STRIDE_NOW: '2026-07-09T00:00:00Z',
      },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    child = proc;

    let stderr = '';
    proc.stderr?.on('data', (d) => {
      stderr += d.toString();
    });

    // Parse newline-delimited JSON from stdout, indexing responses by id.
    const responses = new Map<number, any>();
    let buf = '';
    proc.stdout?.on('data', (d) => {
      buf += d.toString();
      let nl = buf.indexOf('\n');
      while (nl >= 0) {
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (line) {
          try {
            const msg = JSON.parse(line);
            if (msg.id !== undefined && msg.id !== null) responses.set(msg.id, msg);
          } catch {
            // ignore non-JSON stdout lines
          }
        }
        nl = buf.indexOf('\n');
      }
    });

    const send = (obj: unknown) => proc.stdin?.write(`${JSON.stringify(obj)}\n`);
    const waitForId = async (id: number, timeoutMs: number): Promise<any> => {
      const deadline = Date.now() + timeoutMs;
      while (Date.now() < deadline) {
        if (responses.has(id)) return responses.get(id);
        await new Promise((r) => setTimeout(r, 50));
      }
      return undefined;
    };

    // 1) initialize handshake
    send({
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: {
        protocolVersion: '2024-11-05',
        capabilities: {},
        clientInfo: { name: 'stride-stdio-test', version: '1.0.0' },
      },
    });
    const initRes = await waitForId(1, 20000);
    expect(initRes, `no initialize response; stderr:\n${stderr.slice(-600)}`).toBeDefined();
    expect(initRes.result.serverInfo.name).toBe('stride');

    // 2) acknowledge, then list tools
    send({ jsonrpc: '2.0', method: 'notifications/initialized' });
    send({ jsonrpc: '2.0', id: 2, method: 'tools/list' });
    const listRes = await waitForId(2, 10000);
    expect(listRes, `no tools/list response; stderr:\n${stderr.slice(-600)}`).toBeDefined();
    expect(listRes.result.tools).toHaveLength(8);

    // 3) call a tool over the wire
    send({
      jsonrpc: '2.0',
      id: 3,
      method: 'tools/call',
      params: { name: 'get_training_load', arguments: { demo: true } },
    });
    const callRes = await waitForId(3, 10000);
    expect(callRes, `no tools/call response; stderr:\n${stderr.slice(-600)}`).toBeDefined();
    expect(callRes.result.content[0].text).toContain('CTL');
  }, 30000);
});
