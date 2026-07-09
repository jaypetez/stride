/**
 * @stride/schemas — the single source of truth for Stride's domain types.
 *
 * Every schema is a Zod object; the inferred TypeScript type is exported
 * alongside it under the same name. Reused by core, the CLI, the API
 * (validation), the MCP server (tool schemas), and the web UI.
 */

export * from './activity';
export * from './athlete';
export * from './coach';
export * from './enums';
export * from './metrics';

export const STRIDE_SCHEMAS_VERSION = '0.1.0';
