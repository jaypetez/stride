import { type AppState, loadAppState } from '@stride/core';

/** The MCP server's startup state: the shared app state (validated config, store, LLM). */
export type McpState = AppState;

export function loadMcpState(): McpState {
  return loadAppState();
}
