import { DetailedError } from 'hono/client';
import { describe, expect, it } from 'vitest';
import { errorMessage, errorStatus } from './api';

const detailed = (status: number, data: unknown) =>
  new DetailedError(`${status} Error`, { statusCode: status, detail: { data } });

describe('errorMessage', () => {
  it("returns the API's { error } message", () => {
    expect(errorMessage(detailed(400, { error: 'Invalid request — weeks', requestId: 'r' }))).toBe(
      'Invalid request — weeks',
    );
  });

  it('falls back when the server sent no usable message', () => {
    expect(errorMessage(detailed(502, 'Bad Gateway'))).toBe('API unavailable.');
    expect(errorMessage(detailed(500, { error: '' }))).toBe('API unavailable.');
    expect(errorMessage(new TypeError('Failed to fetch'), 'Offline.')).toBe('Offline.');
    expect(errorMessage(null)).toBe('API unavailable.');
  });
});

describe('errorStatus', () => {
  it('reads the HTTP status of a failed call', () => {
    expect(errorStatus(detailed(404, { error: 'nope' }))).toBe(404);
    expect(errorStatus(new Error('x'))).toBeUndefined();
  });
});
