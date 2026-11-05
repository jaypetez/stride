/**
 * @stride/core — Stride's shared domain logic: the deterministic sports-science
 * engine, the Strava client, the local store, and the Claude coach. Every app
 * (CLI, API, web, MCP) is a thin adapter over this package.
 */

import pkg from '../package.json' with { type: 'json' };

export * from './app-state';
export * from './coach/index';
export * from './config';
export * from './fixtures';
export * from './log';
export * from './science/index';
export * from './store/index';
export * from './strava/index';
export * from './sync';

/** The release version (all @stride packages share one fixed version group). */
export const STRIDE_CORE_VERSION: string = pkg.version;
