#!/usr/bin/env node

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  DEMO_ROUTES,
  adjustedMysqlConnectionDelta,
  classifyBusinessRequests,
  staticSelfTest,
} from './verify-wave3-demo-isolation.mjs';

const origin = 'http://127.0.0.1:4175';

test('static self-test fixes the route and marker contract', () => {
  assert.deepEqual(staticSelfTest(), {
    marker: 'W3_DEMO_ISOLATION_HARNESS_SELF_TEST=PASS tests=4 routes=13',
  });
  assert.equal(new Set(DEMO_ROUTES).size, 13);
});

test('source paths containing api are ordinary static traffic', () => {
  const result = classifyBusinessRequests([
    request('GET', `${origin}/src/shared/api/apiClient.ts?t=1`),
    request('GET', `${origin}/assets/index.js`),
  ], origin);
  assert.equal(result.static.length, 2);
  assert.equal(result.business.length, 0);
});

test('root API and cross-origin HTTP traffic fail the demo boundary', () => {
  const result = classifyBusinessRequests([
    request('GET', `${origin}/api/v1/session`),
    request('POST', 'http://127.0.0.1:8080/api/v1/auth/login'),
  ], origin);
  assert.equal(result.api.length, 1);
  assert.equal(result.crossOrigin.length, 1);
  assert.equal(result.business.length, 2);
});

test('connection delta excludes exactly the second sampling connection', () => {
  assert.equal(
    adjustedMysqlConnectionDelta(
      { connections: 41 },
      { connections: 42 },
    ),
    0,
  );
  assert.equal(
    adjustedMysqlConnectionDelta(
      { connections: 41 },
      { connections: 44 },
    ),
    2,
  );
  assert.throws(
    () => adjustedMysqlConnectionDelta(
      { connections: 41 },
      { connections: 41 },
    ),
    /connection counters are invalid/,
  );
});

function request(method, url) {
  return { request: { method, url } };
}
