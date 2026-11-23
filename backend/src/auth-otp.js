import { createCipheriv, createHmac, randomBytes } from 'node:crypto';
import { normalizeEmail, normalizePhone, verifyOtp } from './auth-core.js';

export function normalizeContact(type, value) {
  if (type === 'EMAIL') return normalizeEmail(value);
  if (type === 'PHONE') return normalizePhone(value);
  throw new TypeError('Unsupported contact type');
}

export function hashContact(type, value, secret) {
  if (typeof secret !== 'string' || Buffer.byteLength(secret) < 32) throw new TypeError('CONTACT_HASH_SECRET must contain at least 32 bytes');
  return createHmac('sha256', secret).update(`${type}:${value}`, 'utf8').digest();
}

export function encryptContact(value, key) {
  const rawKey = Buffer.from(key || '', 'base64');
  if (rawKey.length !== 32) throw new TypeError('CONTACT_ENCRYPTION_KEY must be a base64-encoded 32-byte key');
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', rawKey, iv);
  const encrypted = Buffer.concat([cipher.update(value, 'utf8'), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), encrypted]);
}



/**
 * Reserve an OTP challenge atomically with per-contact rate-limit checks.
 * The advisory transaction lock makes concurrent requests for the same
 * normalized contact observe the preceding reservation before inserting.
 */
export async function reserveOtpChallenge(pool, {
  id, userId = null, purpose = 'SIGN_IN', contactType, contactHash,
  contactCiphertext, otpHash,
}) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const lockKey = `OTP:${contactType}:${Buffer.from(contactHash).toString('hex')}`;
    await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [lockKey]);
    const recent = await client.query(
      `SELECT count(*)::int AS count, max(created_at) AS last_created
         FROM auth_challenges WHERE contact_type = $1 AND contact_hash = $2
          AND created_at > now() - interval '1 hour'`,
      [contactType, contactHash],
    );
    if (recent.rows[0].count >= 5) {
      throw Object.assign(new Error('OTP_RATE_LIMITED'), { status: 429 });
    }
    if (recent.rows[0].last_created && Date.now() - new Date(recent.rows[0].last_created).getTime() < 60_000) {
      throw Object.assign(new Error('OTP_RESEND_TOO_SOON'), { status: 429 });
    }
    await client.query(
      `INSERT INTO auth_challenges
        (id, user_id, purpose, contact_type, contact_hash, contact_ciphertext, encryption_key_version, otp_hash, expires_at)
       VALUES ($1, $2, $3, $4, $5, $6, 'v1', $7, now() + interval '5 minutes')`,
      [id, userId, purpose, contactType, contactHash, contactCiphertext, otpHash],
    );
    await client.query('COMMIT');
  } catch (error) {
    try { await client.query('ROLLBACK'); } catch {}
    throw error;
  } finally {
    client.release();
  }
}

export async function deliverOtp(contactType, contact, otp, env = process.env, fetchImpl = fetch) {
  const isEmail = contactType === 'EMAIL';
  const endpoint = env[isEmail ? 'EMAIL_DELIVERY_URL' : 'SMS_DELIVERY_URL'];
  const apiKey = env[isEmail ? 'EMAIL_DELIVERY_API_KEY' : 'SMS_DELIVERY_API_KEY'];
  if (!endpoint || !apiKey) throw new Error(`${isEmail ? 'EMAIL' : 'SMS'}_DELIVERY_NOT_CONFIGURED`);
  const parsed = new URL(endpoint);
  if (parsed.protocol !== 'https:') throw new Error('DELIVERY_ENDPOINT_MUST_USE_HTTPS');
  const response = await fetchImpl(parsed, {
    method: 'POST',
    headers: { authorization: `Bearer ${apiKey}`, 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify({
      to: contact,
      channel: isEmail ? 'email' : 'sms',
      template: 'momento-sign-in-code',
      code: otp,
      expiresInMinutes: 5,
    }),
    signal: AbortSignal.timeout(8000),
  });
  if (!response.ok) throw new Error(`OTP_DELIVERY_FAILED_${response.status}`);
}

/** Enforce endpoint-specific challenge purpose before any state changes. */
export function assertOtpChallengePurpose(actualPurpose, expectedPurpose) {
  if (!['SIGN_IN', 'LINK_CONTACT'].includes(expectedPurpose)) {
    throw new TypeError('Unsupported expected OTP purpose');
  }
  if (actualPurpose !== expectedPurpose) {
    throw Object.assign(new Error('OTP_PURPOSE_MISMATCH'), { status: 403 });
  }
}

export function isValidOtp(challengeId, otp, storedHash, secret) {
  return verifyOtp(challengeId, otp, storedHash, secret);
}
