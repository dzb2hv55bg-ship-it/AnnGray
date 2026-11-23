import { createHash, randomBytes } from 'node:crypto';

export function createInviteToken() {
  // 32 cryptographically random bytes = 256 bits of entropy.
  return randomBytes(32).toString('base64url');
}

export function hashInviteToken(token) {
  return createHash('sha256').update(token, 'utf8').digest();
}

export function parseBearer(header) {
  if (typeof header !== 'string') return null;
  const match = /^Bearer\s+([A-Za-z0-9._~-]+)$/i.exec(header);
  return match?.[1] ?? null;
}

export function boundedInteger(value, fallback, min, max) {
  if (value === undefined || value === null) return fallback;
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < min || parsed > max) return null;
  return parsed;
}
