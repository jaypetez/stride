/**
 * @stride/core — Stride's shared domain logic: the deterministic sports-science
 * engine, the Strava client, the local store, and the Claude coach. Every app
 * (CLI, API, web, MCP) is a thin adapter over this package.
 */

export * from './coach/index';
export * from './config';
export * from './fixtures';
export * from './science/index';
export * from './store/index';
export * from './strava/index';
export * from './sync';

export const STRIDE_CORE_VERSION = '0.1.0';
