import test from 'node:test';
import assert from 'node:assert/strict';
import { createInviteToken, hashInviteToken, parseBearer, boundedInteger } from '../src/security.js';
import { discoverSpaces, joinPublicSpace, reviewJoinRequest, revokeInvite, validateCreateSpace, validateTimelineCursor } from '../src/spaces.js';

test('invite tokens have 256 bits of random material and hash deterministically', () => {
  const token = createInviteToken();
  assert.match(token, /^[A-Za-z0-9_-]{43}$/);
  assert.equal(hashInviteToken(token).length, 32);
  assert.deepEqual(hashInviteToken(token), hashInviteToken(token));
  assert.notEqual(createInviteToken(), token);
});

test('bearer parser rejects malformed authorization headers', () => {
  assert.equal(parseBearer('Bearer abc.def_123'), 'abc.def_123');
  assert.equal(parseBearer('Basic abc'), null);
  assert.equal(parseBearer(undefined), null);
});

test('integer validation enforces explicit bounds', () => {
  assert.equal(boundedInteger(undefined, 168, 1, 720), 168);
  assert.equal(boundedInteger('720', 168, 1, 720), 720);
  assert.equal(boundedInteger('721', 168, 1, 720), null);
  assert.equal(boundedInteger('1.5', 168, 1, 720), null);
});

test('room validation enforces name and private invite-only policy', () => {
  assert.equal(validateCreateSpace({ name: 'Семья', kind: 'CIRCLE', visibility: 'PRIVATE', joinPolicy: 'INVITE_ONLY' }).value.name, 'Семья');
  assert.ok(validateCreateSpace({ name: ' ', kind: 'CIRCLE', visibility: 'PRIVATE', joinPolicy: 'INVITE_ONLY' }).error);
  assert.ok(validateCreateSpace({ name: 'Public', kind: 'CIRCLE', visibility: 'PRIVATE', joinPolicy: 'OPEN' }).error);
  assert.ok(validateCreateSpace({ name: 'x'.repeat(61), kind: 'CIRCLE', visibility: 'PUBLIC', joinPolicy: 'OPEN' }).error);
});


test('timeline cursor requires a complete timestamp and UUID pair', () => {
  assert.deepEqual(validateTimelineCursor(null, null).value, { before: null, beforeId: null });
  assert.ok(validateTimelineCursor('2026-11-20T10:00:00.000Z', null).error);
  assert.ok(validateTimelineCursor(null, '550e8400-e29b-41d4-a716-446655440000').error);
  assert.ok(validateTimelineCursor('not-a-date', '550e8400-e29b-41d4-a716-446655440000').error);
  assert.deepEqual(
    validateTimelineCursor('2026-11-20T10:00:00.000Z', '550e8400-e29b-41d4-a716-446655440000').value,
    { before: '2026-11-20T10:00:00.000Z', beforeId: '550e8400-e29b-41d4-a716-446655440000' },
  );
});


test('OIDC identity mapping does not constrain provider subject to UUID syntax', async () => {
  const serverSource = await (await import('node:fs/promises')).readFile(new URL('../src/server.js', import.meta.url), 'utf8');
  assert.match(serverSource, /OIDC `sub` is an opaque/);
  assert.match(serverSource, /auth_identities/);
  assert.match(serverSource, /pg_advisory_xact_lock/);
  assert.doesNotMatch(serverSource, /payload\.sub.*test\(\)/);
});

test('room validation allows public mixed-format rooms with open or approval join policy', () => {
  assert.equal(validateCreateSpace({
    name: 'Кино', kind: 'INTEREST_ROOM', visibility: 'PUBLIC', joinPolicy: 'OPEN',
  }).value.joinPolicy, 'OPEN');
  assert.equal(validateCreateSpace({
    name: 'Книги', kind: 'INTEREST_ROOM', visibility: 'PUBLIC', joinPolicy: 'APPROVAL',
  }).value.joinPolicy, 'APPROVAL');
});

test('private rooms cannot be configured for open joining', () => {
  assert.ok(validateCreateSpace({
    name: 'Семья', kind: 'CIRCLE', visibility: 'PRIVATE', joinPolicy: 'OPEN',
  }).error);
});


test('invite revocation requires owner/admin permission and commits the revoke', async () => {
  const statements = [];
  const client = {
    async query(sql) {
      statements.push(sql.trim().split(/\s+/).slice(0, 3).join(' '));
      if (sql.includes('SELECT sm.role')) return { rowCount: 1, rows: [{ role: 'ADMIN' }] };
      if (sql.includes('UPDATE space_invites')) return { rowCount: 1, rows: [{ id: 'invite-1', revoked_at: '2026-11-20T10:00:00Z' }] };
      return { rowCount: 0, rows: [] };
    },
    release() {},
  };
  const result = await revokeInvite({ connect: async () => client }, 'user-1', 'space-1', 'invite-1');
  assert.equal(result.inviteId, 'invite-1');
  assert.ok(statements.includes('COMMIT'));
  assert.ok(statements.some((statement) => statement.startsWith('UPDATE space_invites')));
});

test('invite revocation is denied to ordinary members', async () => {
  const statements = [];
  const client = {
    async query(sql) {
      statements.push(sql.trim());
      if (sql.includes('SELECT sm.role')) return { rowCount: 1, rows: [{ role: 'MEMBER' }] };
      return { rowCount: 0, rows: [] };
    },
    release() {},
  };
  const result = await revokeInvite({ connect: async () => client }, 'user-1', 'space-1', 'invite-1');
  assert.equal(result.error, 'FORBIDDEN');
  assert.ok(statements.includes('ROLLBACK'));
  assert.ok(!statements.some((statement) => statement.includes('UPDATE space_invites')));
});


test('public discovery returns only public rooms and passes bounded search text as a parameter', async () => {
  let captured;
  const pool = { async query(sql, params) { captured = { sql, params }; return { rows: [] }; } };
  await discoverSpaces(pool, 'user-1', 'film_%', 30);
  assert.match(captured.sql, /s.visibility = 'PUBLIC'/);
  assert.match(captured.sql, /s.archived_at IS NULL/);
  assert.equal(captured.params[1], 'film\\_\\%');
  assert.equal(captured.params[2], 30);
});

test('open public room join checks age profiles before adding membership', async () => {
  const statements = [];
  const client = {
    async query(sql) {
      statements.push(sql.trim());
      if (sql.includes('SELECT visibility, join_policy')) return { rowCount: 1, rows: [{ visibility: 'PUBLIC', join_policy: 'OPEN', archived_at: null }] };
      if (sql.includes('SELECT 1 FROM space_members')) return { rowCount: 0, rows: [] };
      if (sql.includes('FROM user_blocks')) return { rowCount: 0, rows: [] };
      if (sql.includes('LEFT JOIN user_age_profiles')) return { rowCount: 2, rows: [{ user_id: 'user-1', age_band: 'ADULT' }, { user_id: 'owner-1', age_band: 'ADULT' }] };
      if (sql.includes('INSERT INTO space_members')) return { rowCount: 1, rows: [] };
      return { rowCount: 0, rows: [] };
    },
    release() {},
  };
  const result = await joinPublicSpace({ connect: async () => client }, 'user-1', 'space-1');
  assert.deepEqual(result, { spaceId: 'space-1', joined: true, alreadyMember: false });
  assert.ok(statements.includes('COMMIT'));
  assert.ok(statements.some((sql) => sql.includes('INSERT INTO space_members')));
});

test('public room join fails closed when age profile is missing', async () => {
  const statements = [];
  const client = {
    async query(sql) {
      statements.push(sql.trim());
      if (sql.includes('SELECT visibility, join_policy')) return { rowCount: 1, rows: [{ visibility: 'PUBLIC', join_policy: 'OPEN', archived_at: null }] };
      if (sql.includes('SELECT 1 FROM space_members')) return { rowCount: 0, rows: [] };
      if (sql.includes('FROM user_blocks')) return { rowCount: 0, rows: [] };
      if (sql.includes('LEFT JOIN user_age_profiles')) return { rowCount: 1, rows: [{ user_id: 'user-1', age_band: null }] };
      return { rowCount: 0, rows: [] };
    },
    release() {},
  };
  const result = await joinPublicSpace({ connect: async () => client }, 'user-1', 'space-1');
  assert.equal(result.error, 'AGE_PROFILE_REQUIRED');
  assert.ok(statements.includes('ROLLBACK'));
  assert.ok(!statements.some((sql) => sql.includes('INSERT INTO space_members')));
});


test('approval room creates a pending join request instead of membership', async () => {
  const statements = [];
  const client = {
    async query(sql) {
      statements.push(sql.trim());
      if (sql.includes('SELECT visibility, join_policy')) return { rowCount: 1, rows: [{ visibility: 'PUBLIC', join_policy: 'APPROVAL', archived_at: null }] };
      if (sql.includes('SELECT 1 FROM space_members')) return { rowCount: 0, rows: [] };
      if (sql.includes('FROM user_blocks')) return { rowCount: 0, rows: [] };
      if (sql.includes('LEFT JOIN user_age_profiles')) return { rowCount: 2, rows: [{ user_id: 'user-1', age_band: 'ADULT' }, { user_id: 'owner-1', age_band: 'ADULT' }] };
      if (sql.includes('INSERT INTO join_requests')) return { rowCount: 1, rows: [{ id: 'request-1', status: 'PENDING' }] };
      return { rowCount: 0, rows: [] };
    },
    release() {},
  };
  const result = await joinPublicSpace({ connect: async () => client }, 'user-1', 'space-1');
  assert.deepEqual(result, { spaceId: 'space-1', requestId: 'request-1', requestPending: true });
  assert.ok(statements.includes('COMMIT'));
  assert.ok(!statements.some((sql) => sql.includes('INSERT INTO space_members')));
});

test('moderator approval rechecks safety and adds membership transactionally', async () => {
  const statements = [];
  const client = {
    async query(sql) {
      statements.push(sql.trim());
      if (sql.includes('SELECT role FROM space_members')) return { rowCount: 1, rows: [{ role: 'MODERATOR' }] };
      if (sql.includes('SELECT jr.user_id')) return { rowCount: 1, rows: [{ user_id: 'applicant-1', status: 'PENDING', visibility: 'PUBLIC', join_policy: 'APPROVAL', archived_at: null }] };
      if (sql.includes('FROM user_blocks b')) return { rowCount: 0, rows: [] };
      if (sql.includes('LEFT JOIN user_age_profiles')) return { rowCount: 2, rows: [{ user_id: 'applicant-1', age_band: 'ADULT' }, { user_id: 'owner-1', age_band: 'ADULT' }] };
      if (sql.includes('INSERT INTO space_members')) return { rowCount: 1, rows: [] };
      if (sql.includes('UPDATE join_requests')) return { rowCount: 1, rows: [] };
      return { rowCount: 0, rows: [] };
    },
    release() {},
  };
  const result = await reviewJoinRequest({ connect: async () => client }, 'moderator-1', 'space-1', 'request-1', 'APPROVED');
  assert.deepEqual(result, { requestId: 'request-1', status: 'APPROVED', joined: true });
  assert.ok(statements.includes('COMMIT'));
  assert.ok(statements.some((sql) => sql.includes('LEFT JOIN user_age_profiles')));
  assert.ok(statements.some((sql) => sql.includes('FROM user_blocks b')));
});

test('ordinary member cannot review a join request', async () => {
  const statements = [];
  const client = {
    async query(sql) {
      statements.push(sql.trim());
      if (sql.includes('SELECT role FROM space_members')) return { rowCount: 0, rows: [] };
      return { rowCount: 0, rows: [] };
    },
    release() {},
  };
  const result = await reviewJoinRequest({ connect: async () => client }, 'member-1', 'space-1', 'request-1', 'APPROVED');
  assert.equal(result.error, 'FORBIDDEN');
  assert.ok(statements.includes('ROLLBACK'));
  assert.ok(!statements.some((sql) => sql.includes('UPDATE join_requests')));
});
