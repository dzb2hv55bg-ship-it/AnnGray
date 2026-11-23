import test from 'node:test';
import assert from 'node:assert/strict';
import { createAccessToken, createOtp, createRefreshToken, hashOtp, hashRefreshToken, normalizeEmail, normalizePhone, verifyAccessToken, verifyOtp } from '../src/auth-core.js';

const secret = 'test-only-secret-that-is-at-least-32-bytes';
const pepper = 'test-only-refresh-pepper-at-least-32-bytes';

test('normalizes email and rejects malformed values', () => {
  assert.equal(normalizeEmail('  Test.User@Example.COM '), 'test.user@example.com');
  assert.throws(() => normalizeEmail('not-an-email'));
});

test('normalizes E.164 phone and rejects ambiguous local numbers', () => {
  assert.equal(normalizePhone('+7 (999) 123-45-67'), '+79991234567');
  assert.throws(() => normalizePhone('89991234567'));
});

test('creates six-digit OTPs and verifies only the matching challenge', () => {
  const otp = createOtp();
  assert.match(otp, /^\d{6}$/);
  const hash = hashOtp('challenge-1', otp, secret);
  assert.equal(verifyOtp('challenge-1', otp, hash, secret), true);
  assert.equal(verifyOtp('challenge-2', otp, hash, secret), false);
});

test('refresh tokens are opaque, unique and peppered', () => {
  const a = createRefreshToken(); const b = createRefreshToken();
  assert.notEqual(a, b);
  assert.notDeepEqual(hashRefreshToken(a, pepper), hashRefreshToken(a, 'another-refresh-pepper-with-32-bytes'));
  assert.throws(() => hashRefreshToken(a, 'short'));
});

test('MOMENTO access tokens are signed, scoped and expire', () => {
  const token = createAccessToken({ userId: 'user-1', sessionId: 'session-1', secret, issuer: 'https://api.momento.app', audience: 'momento-api', now: 1_800_000_000_000 });
  const claims = verifyAccessToken(token, { secret, issuer: 'https://api.momento.app', audience: 'momento-api', now: 1_800_000_001_000 });
  assert.equal(claims.sub, 'user-1');
  assert.equal(claims.sid, 'session-1');
  assert.throws(() => verifyAccessToken(`${token.slice(0, -1)}x`, { secret, issuer: 'https://api.momento.app', audience: 'momento-api', now: 1_800_000_001_000 }));
  assert.throws(() => verifyAccessToken(token, { secret, issuer: 'https://wrong.example', audience: 'momento-api', now: 1_800_000_001_000 }));
  assert.throws(() => verifyAccessToken(token, { secret, issuer: 'https://api.momento.app', audience: 'momento-api', now: 1_800_001_000_000 }));
});

// Regression: malformed refresh tokens must be rejected at the HTTP boundary.
test('refresh token shape rejects malformed untrusted values', async () => {
  const { isRefreshTokenShape } = await import('../src/auth-core.js');
  for (const value of [null, {}, '', 'short', 'x'.repeat(100), 'x'.repeat(40) + '!']) {
    assert.equal(isRefreshTokenShape(value), false);
  }
  assert.equal(isRefreshTokenShape('a'.repeat(43)), true);
});
