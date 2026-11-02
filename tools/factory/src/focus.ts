import type { Phase } from './types.ts';

/**
 * The phase the factory is currently focused on. The dispatcher only hands out
 * issues in this phase or earlier (unless `--any-phase`), and the seeder parks
 * everything later in Status=Backlog. Move the factory forward by changing this
 * one line (e.g. to 'P0' once the Solo Garmin MVP ships).
 */
export const FOCUS_PHASE: Phase = 'MVP';
