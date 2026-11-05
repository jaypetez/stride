import assert from 'node:assert/strict';
import { test } from 'node:test';
import { evaluate } from './ci-gate.mjs';

test('skipped android passes, skipped core job fails', () => {
  assert.equal(evaluate({ lint: { result: 'success' }, android: { result: 'skipped' } }).ok, true);
  assert.equal(evaluate({ lint: { result: 'skipped' } }).ok, false);
});

test('any failure or cancellation fails the gate', () => {
  assert.equal(evaluate({ android: { result: 'failure' } }).ok, false);
  assert.equal(evaluate({ test: { result: 'cancelled' } }).ok, false);
});
