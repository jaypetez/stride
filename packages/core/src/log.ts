/**
 * Tiny zero-dependency structured logger. Writes to STDERR only — stdout is
 * reserved for CLI output and the MCP protocol channel. Level is read from
 * STRIDE_LOG (silent|error|warn|info|debug, default "warn") at call time, so an
 * app can raise verbosity (e.g. `--verbose` → STRIDE_LOG=debug) before running.
 * Set STRIDE_LOG_FORMAT=json for machine-readable lines.
 */
export type LogLevel = 'silent' | 'error' | 'warn' | 'info' | 'debug';

const ORDER: Record<LogLevel, number> = { silent: 0, error: 1, warn: 2, info: 3, debug: 4 };

function currentLevel(): LogLevel {
  const raw = (process.env.STRIDE_LOG ?? '').toLowerCase();
  return raw in ORDER ? (raw as LogLevel) : 'warn';
}

export type LogFields = Record<string, unknown>;

export interface Logger {
  error(msg: string, fields?: LogFields): void;
  warn(msg: string, fields?: LogFields): void;
  info(msg: string, fields?: LogFields): void;
  debug(msg: string, fields?: LogFields): void;
  child(scope: string): Logger;
}

function emit(
  level: Exclude<LogLevel, 'silent'>,
  scope: string,
  msg: string,
  fields?: LogFields,
): void {
  if (ORDER[level] > ORDER[currentLevel()]) return;
  const json = (process.env.STRIDE_LOG_FORMAT ?? '').toLowerCase() === 'json';
  if (json) {
    process.stderr.write(`${JSON.stringify({ level, scope, msg, ...fields })}\n`);
    return;
  }
  const suffix = fields && Object.keys(fields).length > 0 ? ` ${JSON.stringify(fields)}` : '';
  process.stderr.write(`[${level}]${scope ? ` ${scope}:` : ''} ${msg}${suffix}\n`);
}

export function createLogger(scope = ''): Logger {
  return {
    error: (msg, fields) => emit('error', scope, msg, fields),
    warn: (msg, fields) => emit('warn', scope, msg, fields),
    info: (msg, fields) => emit('info', scope, msg, fields),
    debug: (msg, fields) => emit('debug', scope, msg, fields),
    child: (childScope) => createLogger(scope ? `${scope}.${childScope}` : childScope),
  };
}

/** The root logger. Prefer `logger.child('scope')` in each module. */
export const logger = createLogger('stride');
