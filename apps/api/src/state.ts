import { type AppState, loadAppState } from '@stride/core';

/** The API's startup state: the shared app state (validated config, store, LLM). */
export type ApiState = AppState;

export function loadApiState(): ApiState {
  return loadAppState();
}
