import test from 'node:test';
import assert from 'node:assert/strict';
import { discoverSpaces, getRoomTimeline } from '../src/spaces.js';

// These exercise real query builders against a recording pool. They do not replace PostgreSQL integration tests.
test('anonymous discovery only selects published public rooms', async () => {
  const calls = [];
  const pool = { query: async (sql, params) => { calls.push({sql, params}); return { rows: [] }; } };
  const rooms = await discoverSpaces(pool, null, 'travel');
  assert.deepEqual(rooms, []);
  assert.match(calls[0].sql, /visibility = 'PUBLIC'/);
  assert.match(calls[0].sql, /published_at IS NOT NULL/);
  assert.match(calls[0].sql, /archived_at IS NULL/);
  assert.equal(calls[0].params[0], null);
});

test('anonymous room timeline denies non-public room', async () => {
  const calls = [];
  const pool = { query: async (sql, params) => { calls.push({sql, params}); return { rowCount: 0, rows: [] }; } };
  const response = await getRoomTimeline(pool, null, '00000000-0000-4000-8000-000000000001', null, null, 20);
  assert.deepEqual(response, { error: 'FORBIDDEN' });
  assert.equal(calls.length, 1);
  assert.match(calls[0].sql, /visibility='PUBLIC'/);
  assert.match(calls[0].sql, /published_at IS NOT NULL/);
});
