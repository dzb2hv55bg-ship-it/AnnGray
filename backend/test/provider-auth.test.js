import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeContact, hashContact, encryptContact, deliverOtp, reserveOtpChallenge, assertOtpChallengePurpose } from '../src/auth-otp.js';
import { verifyYandexAccessToken, verifyVkAccessToken } from '../src/provider-http.js';

test('contact normalization and keyed hashes are deterministic but secret-scoped', () => {
  const email = normalizeContact('EMAIL', ' Test@Example.com ');
  assert.equal(email, 'test@example.com');
  assert.equal(normalizeContact('PHONE', '+7 (999) 123-45-67'), '+79991234567');
  const a = hashContact('EMAIL', email, 'a'.repeat(32));
  const b = hashContact('EMAIL', email, 'b'.repeat(32));
  assert.notDeepEqual(a, b);
  assert.throws(() => normalizeContact('PHONE', '89991234567'));
});

test('contact encryption uses AES-GCM with 32-byte key and randomized IV', () => {
  const key = Buffer.alloc(32, 7).toString('base64');
  const a = encryptContact('test@example.com', key);
  const b = encryptContact('test@example.com', key);
  assert.equal(a.length, 12 + 16 + Buffer.byteLength('test@example.com'));
  assert.notDeepEqual(a, b);
  assert.throws(() => encryptContact('test@example.com', 'bad'));
});

test('Yandex identity is resolved from server-verified OAuth userinfo, not client profile fields', async (t) => {
  process.env.YANDEX_CLIENT_ID = 'app-id';
  const identity = await verifyYandexAccessToken('x'.repeat(32), {
    fetchImpl: async (url, options) => {
      assert.match(String(url), /login\.yandex\.ru\/info/);
      assert.equal(options.headers.Authorization, `OAuth ${'x'.repeat(32)}`);
      return { ok: true, json: async () => ({ id: '12345', first_name: 'Test', last_name: 'User', client_id: 'app-id' }) };
    },
  });
  assert.equal(identity.issuer, 'https://login.yandex.ru');
  assert.equal(identity.subject, '12345');
  assert.equal(identity.displayName, 'Test User');
  delete process.env.YANDEX_CLIENT_ID;
});

test('VK identity is resolved from VK ID user_info endpoint', async (t) => {
  process.env.VK_CLIENT_ID = 'vk-client';
  const identity = await verifyVkAccessToken('v'.repeat(32), {
    fetchImpl: async (url, options) => {
      assert.equal(String(url), 'https://id.vk.ru/oauth2/user_info?client_id=vk-client');
      assert.equal(new URLSearchParams(options.body).get('access_token'), 'v'.repeat(32));
      return { ok: true, json: async () => ({ user: { user_id: 77, first_name: 'VK', last_name: 'User' } }) };
    },
  });
  assert.equal(identity.issuer, 'https://id.vk.ru');
  assert.equal(identity.subject, '77');
  delete process.env.VK_CLIENT_ID;
});

test('OTP delivery refuses insecure URLs and requires explicit provider configuration', async () => {
  await assert.rejects(() => deliverOtp('EMAIL', 'a@b.com', '123456', {}, fetch));
  await assert.rejects(() => deliverOtp('EMAIL', 'a@b.com', '123456', {
    EMAIL_DELIVERY_URL: 'http://mailer.example/send',
    EMAIL_DELIVERY_API_KEY: 'secret',
  }, fetch), /HTTPS/);
});


test('OTP reservation locks the contact before checking limits and inserting', async () => {
  const calls = [];
  let released = false;
  const pool = {
    async connect() {
      return {
        async query(sql, params = []) {
          calls.push({ sql, params });
          if (sql.includes('SELECT count(*)::int AS count')) {
            return { rows: [{ count: 1, last_created: new Date(Date.now() - 120_000) }] };
          }
          return { rows: [] };
        },
        release() { released = true; },
      };
    },
  };
  await reserveOtpChallenge(pool, {
    id: 'challenge-1', userId: null, purpose: 'SIGN_IN', contactType: 'EMAIL',
    contactHash: Buffer.from('hash'), contactCiphertext: Buffer.from('cipher'),
    otpHash: Buffer.from('otp-hash'),
  });
  const sql = calls.map((entry) => entry.sql);
  assert.equal(sql[0], 'BEGIN');
  assert.match(sql[1], /pg_advisory_xact_lock/);
  assert.match(sql[2], /SELECT count\(\*\)::int AS count/);
  assert.match(sql[3], /INSERT INTO auth_challenges/);
  assert.equal(sql[4], 'COMMIT');
  assert.equal(released, true);
});

test('OTP reservation rolls back when the hourly limit is reached', async () => {
  const calls = [];
  const pool = {
    async connect() {
      return {
        async query(sql) {
          calls.push(sql);
          if (sql.includes('SELECT count(*)::int AS count')) {
            return { rows: [{ count: 5, last_created: new Date(Date.now() - 120_000) }] };
          }
          return { rows: [] };
        },
        release() {},
      };
    },
  };
  await assert.rejects(
    () => reserveOtpChallenge(pool, {
      id: 'challenge-2', contactType: 'PHONE', contactHash: Buffer.from('phone-hash'),
      contactCiphertext: Buffer.from('cipher'), otpHash: Buffer.from('otp-hash'),
    }),
    (error) => error.status === 429 && error.message === 'OTP_RATE_LIMITED',
  );
  assert.ok(calls.includes('ROLLBACK'));
  assert.equal(calls.some((sql) => sql.includes('INSERT INTO auth_challenges')), false);
});


test('OTP challenge purpose is strictly bound to the endpoint', () => {
  assert.doesNotThrow(() => assertOtpChallengePurpose('SIGN_IN', 'SIGN_IN'));
  assert.doesNotThrow(() => assertOtpChallengePurpose('LINK_CONTACT', 'LINK_CONTACT'));
  assert.throws(() => assertOtpChallengePurpose('SIGN_IN', 'LINK_CONTACT'),
    (error) => error.status === 403 && error.message === 'OTP_PURPOSE_MISMATCH');
  assert.throws(() => assertOtpChallengePurpose('LINK_CONTACT', 'SIGN_IN'),
    (error) => error.status === 403 && error.message === 'OTP_PURPOSE_MISMATCH');
  assert.throws(() => assertOtpChallengePurpose('SIGN_IN', 'UNKNOWN'), TypeError);
});
