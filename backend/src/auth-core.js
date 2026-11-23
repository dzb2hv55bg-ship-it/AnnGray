import { createHmac, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';

export const AUTH_PROVIDERS = Object.freeze(['yandex', 'google', 'vk', 'email', 'phone']);

export function normalizeEmail(value) {
  if (typeof value !== 'string') throw new TypeError('Email must be a string');
  const normalized = value.trim().toLowerCase();
  if (normalized.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized)) throw new TypeError('Invalid email address');
  return normalized;
}

export function normalizePhone(value) {
  if (typeof value !== 'string') throw new TypeError('Phone must be a string');
  const normalized = value.replace(/[\s()-]/g, '');
  if (!/^\+[1-9]\d{7,14}$/.test(normalized)) throw new TypeError('Phone must be in E.164 format, for example +79991234567');
  return normalized;
}

export function createOtp() { return randomInt(0, 1_000_000).toString().padStart(6, '0'); }

function requireSecret(secret, name) {
  if (typeof secret !== 'string' || Buffer.byteLength(secret) < 32) throw new TypeError(`${name} must contain at least 32 bytes`);
}

export function hashOtp(challengeId, otp, secret) {
  if (typeof challengeId !== 'string' || typeof otp !== 'string' || !/^\d{6}$/.test(otp)) throw new TypeError('Invalid OTP challenge input');
  requireSecret(secret, 'OTP_HMAC_SECRET');
  return createHmac('sha256', secret).update(`${challengeId}:${otp}`, 'utf8').digest();
}

export function verifyOtp(challengeId, otp, expectedHash, secret) {
  if (!Buffer.isBuffer(expectedHash) || expectedHash.length !== 32) return false;
  try { return timingSafeEqual(hashOtp(challengeId, otp, secret), expectedHash); } catch { return false; }
}

export function createRefreshToken() { return randomBytes(32).toString('base64url'); }

export function hashRefreshToken(token, pepper) {
  requireSecret(pepper, 'REFRESH_TOKEN_PEPPER');
  if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{40,64}$/.test(token)) throw new TypeError('Invalid refresh token');
  return createHmac('sha256', pepper).update(token, 'utf8').digest();
}

const b64url = (value) => Buffer.from(value).toString('base64url');
export function createAccessToken({ userId, sessionId, secret, issuer, audience, now = Date.now(), ttlSeconds = 900 }) {
  requireSecret(secret, 'MOMENTO_JWT_SECRET');
  if (!userId || !sessionId || !issuer || !audience) throw new TypeError('Missing access token claims');
  const issuedAt = Math.floor(now / 1000);
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64url(JSON.stringify({ sub: userId, sid: sessionId, iss: issuer, aud: audience, iat: issuedAt, exp: issuedAt + ttlSeconds }));
  const input = `${header}.${payload}`;
  const signature = createHmac('sha256', secret).update(input).digest('base64url');
  return `${input}.${signature}`;
}

export function verifyAccessToken(token, { secret, issuer, audience, now = Date.now() }) {
  requireSecret(secret, 'MOMENTO_JWT_SECRET');
  if (typeof token !== 'string' || token.length > 4096) throw new Error('Invalid access token');
  const parts = token.split('.');
  if (parts.length !== 3) throw new Error('Invalid access token');
  const input = `${parts[0]}.${parts[1]}`;
  const expected = createHmac('sha256', secret).update(input).digest();
  let actual;
  try { actual = Buffer.from(parts[2], 'base64url'); } catch { throw new Error('Invalid access token'); }
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) throw new Error('Invalid access token');
  let header; let claims;
  try { header = JSON.parse(Buffer.from(parts[0], 'base64url').toString('utf8')); claims = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8')); } catch { throw new Error('Invalid access token'); }
  const nowSeconds = Math.floor(now / 1000);
  if (!header || typeof header !== 'object' || header.alg !== 'HS256' || header.typ !== 'JWT' || !claims || typeof claims !== 'object' || claims.iss !== issuer || claims.aud !== audience ||
      typeof claims.sub !== 'string' || typeof claims.sid !== 'string' || !Number.isInteger(claims.exp) ||
      !Number.isInteger(claims.iat) || claims.exp <= nowSeconds || claims.iat > nowSeconds + 60 || claims.exp <= claims.iat) throw new Error('Invalid access token');
  return claims;
}

/** Validate untrusted refresh tokens before invoking the database. */
export function isRefreshTokenShape(value) {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{40,64}$/.test(value);
}
