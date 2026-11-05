import os from 'node:os';
import path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { LocalStore, loadConfig, MAX_NOTE_LENGTH } from '@stride/core';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { buildServer } from '../src/server';

// In-process MCP round-trip (no stdio): exercises the registered input schemas,
// which is where the free-text `note` length bound lives.
describe('MCP action tools bound the free-text note', () => {
  const dir = path.join(os.tmpdir(), `stride-mcp-note-${process.pid}-${Date.now()}`);
  const server = buildServer({
    config: loadConfig({ STRIDE_NOW: '2026-07-09T00:00:00Z' }),
    store: new LocalStore(dir),
    llm: null,
  });
  const client = new Client({ name: 'note-test', version: '0.0.0' });

  beforeAll(async () => {
    const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
    await Promise.all([server.connect(serverSide), client.connect(clientSide)]);
  });

  afterAll(async () => {
    await client.close();
    await server.close();
  });

  const tooLong = 'x'.repeat(MAX_NOTE_LENGTH + 1);

  for (const name of ['analyze_workout', 'suggest_next_workout', 'generate_plan']) {
    it(`${name} rejects a note over ${MAX_NOTE_LENGTH} characters`, async () => {
      const res = await client.callTool({ name, arguments: { demo: true, note: tooLong } }).then(
        (r) => ({ isError: Boolean(r.isError), text: JSON.stringify(r.content) }),
        (e: unknown) => ({ isError: true, text: String(e) }),
      );
      expect(res.isError).toBe(true);
      expect(res.text).toMatch(/note/i);
    });
  }

  it('accepts a note at the limit', async () => {
    const res = await client.callTool({
      name: 'suggest_next_workout',
      arguments: { demo: true, note: 'x'.repeat(MAX_NOTE_LENGTH) },
    });
    expect(res.isError).toBeFalsy();
  });
});
