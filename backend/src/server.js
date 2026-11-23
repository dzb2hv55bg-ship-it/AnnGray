import http from 'node:http';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID, randomBytes, createHash } from 'node:crypto';
import { createRemoteJWKSet, jwtVerify } from 'jose';
import pg from 'pg';
import { parseBearer, boundedInteger } from './security.js';
import { createAccessToken, createRefreshToken, hashRefreshToken, verifyAccessToken, isRefreshTokenShape, hashOtp, createOtp } from './auth-core.js';
import { resolveProviderIdentity } from './provider-auth.js';
import { readJsonObject } from './request-json.js';
import { validateDisplayName } from './profile.js';
import { updateAvatar } from './avatar.js';
import { validateUploadHeaders, readVideoBytes, storeMomentUpload } from './media-upload.js';
import { parseCalendarOptions, getPrivateCalendar } from './calendar.js';
import { createCapsule, listCapsules, cancelCapsule } from './capsules.js';
import { normalizeContact, hashContact, encryptContact, deliverOtp, isValidOtp, reserveOtpChallenge, assertOtpChallengePurpose } from './auth-otp.js';
import { isUuid, publishRoom, attachMoment, setRoomSubscription, setAuthorSubscription, getSubscriptions, getSubscriptionUpdates, getPublicRoom } from './live-vlogs.js';
import { acceptInvite, createInvite, createSpace, discoverSpaces, getRoomTimeline, joinPublicSpace, listJoinRequests, listSpaces, reviewJoinRequest, revokeInvite, validateCreateSpace, validateTimelineCursor } from './spaces.js';

const { Pool } = pg;
const required = ['DATABASE_URL', 'PUBLIC_APP_URL', 'MOMENTO_JWT_SECRET', 'MOMENTO_JWT_ISSUER', 'MOMENTO_JWT_AUDIENCE', 'REFRESH_TOKEN_PEPPER'];
for (const key of required) if (!process.env[key]) throw new Error(`Missing required environment variable: ${key}`);
for (const key of ['MOMENTO_JWT_SECRET', 'REFRESH_TOKEN_PEPPER']) {
  if (Buffer.byteLength(process.env[key]) < 32 || process.env[key].startsWith('replace-with-')) throw new Error(`${key} must be replaced with an independent random secret of at least 32 bytes`);
}
const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 10, connectionTimeoutMillis: 5000, idleTimeoutMillis: 30000 });
const jwks = process.env.JWT_JWKS_URL ? createRemoteJWKSet(new URL(process.env.JWT_JWKS_URL)) : null;
const port = Number(process.env.PORT || 8080);
const ACCESS_TOKEN_TTL_SECONDS = 15 * 60;
const REFRESH_TOKEN_TTL_DAYS = 30;

function issueSessionTokens(userId, sessionId) {
  const refreshToken = createRefreshToken();
  return {
    accessToken: createAccessToken({ userId, sessionId, secret: process.env.MOMENTO_JWT_SECRET, issuer: process.env.MOMENTO_JWT_ISSUER, audience: process.env.MOMENTO_JWT_AUDIENCE, ttlSeconds: ACCESS_TOKEN_TTL_SECONDS }),
    refreshToken,
    tokenType: 'Bearer',
    expiresIn: ACCESS_TOKEN_TTL_SECONDS,
  };
}

async function createSession(userId, deviceLabel = null) {
  const sessionId = randomUUID();
  const tokens = issueSessionTokens(userId, sessionId);
  await pool.query(
    `INSERT INTO auth_sessions (id, user_id, refresh_token_hash, device_label, expires_at)
     VALUES ($1, $2, $3, $4, now() + ($5 * interval '1 day'))`,
    [sessionId, userId, hashRefreshToken(tokens.refreshToken, process.env.REFRESH_TOKEN_PEPPER), typeof deviceLabel === 'string' ? deviceLabel.trim().slice(0, 100) || null : null, REFRESH_TOKEN_TTL_DAYS],
  );
  return tokens;
}

async function authenticate(req) {
  const token = parseBearer(req.headers.authorization);
  if (!token) return null;
  let claims;
  try {
    claims = verifyAccessToken(token, { secret: process.env.MOMENTO_JWT_SECRET, issuer: process.env.MOMENTO_JWT_ISSUER, audience: process.env.MOMENTO_JWT_AUDIENCE });
  } catch { return null; }
  const active = await pool.query(
    `SELECT s.user_id FROM auth_sessions s JOIN users u ON u.id = s.user_id
      WHERE s.id = $1 AND s.user_id = $2 AND s.revoked_at IS NULL
        AND s.expires_at > now() AND u.deleted_at IS NULL`,
    [claims.sid, claims.sub],
  );
  return active.rowCount ? claims.sub : null;
}

async function rotateRefreshToken(rawToken) {
  const tokenHash = hashRefreshToken(rawToken, process.env.REFRESH_TOKEN_PEPPER);
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const found = await client.query(
      `SELECT id, user_id, device_label, expires_at, revoked_at FROM auth_sessions
        WHERE refresh_token_hash = $1 FOR UPDATE`, [tokenHash],
    );
    if (!found.rowCount || new Date(found.rows[0].expires_at) <= new Date()) {
      await client.query('ROLLBACK');
      return null;
    }
    if (found.rows[0].revoked_at) {
      // A previously rotated refresh token was replayed. Revoke every active
      // descendant in its rotation chain instead of allowing the stolen token
      // to race the legitimate client indefinitely.
      await client.query(
        `WITH RECURSIVE descendants(id) AS (
           SELECT replaced_by FROM auth_sessions WHERE id = $1 AND replaced_by IS NOT NULL
           UNION ALL
           SELECT s.replaced_by FROM auth_sessions s JOIN descendants d ON s.id = d.id
            WHERE s.replaced_by IS NOT NULL
         )
         UPDATE auth_sessions SET revoked_at = COALESCE(revoked_at, now()), revoked_reason = 'REFRESH_REPLAY'
          WHERE id IN (SELECT id FROM descendants) AND revoked_at IS NULL`,
        [found.rows[0].id],
      );
      await client.query('COMMIT');
      return null;
    }
    const old = found.rows[0];
    const newId = randomUUID();
    const tokens = issueSessionTokens(old.user_id, newId);
    await client.query(
      `INSERT INTO auth_sessions (id, user_id, refresh_token_hash, device_label, expires_at, rotated_from)
       VALUES ($1, $2, $3, $4, now() + ($5 * interval '1 day'), $6)`,
      [newId, old.user_id, hashRefreshToken(tokens.refreshToken, process.env.REFRESH_TOKEN_PEPPER), old.device_label, REFRESH_TOKEN_TTL_DAYS, old.id],
    );
    await client.query("UPDATE auth_sessions SET revoked_at = now(), revoked_reason = 'ROTATED', replaced_by = $2, last_used_at = now() WHERE id = $1", [old.id, newId]);
    await client.query('COMMIT');
    return tokens;
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally { client.release(); }
}

function send(res, status, payload) {
  const body = payload === undefined ? '' : JSON.stringify(payload);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(body), 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' });
  res.end(body);
}

const readJson = readJsonObject;

async function authenticateExternal(req) {
  if (!jwks || !process.env.JWT_ISSUER || !process.env.JWT_AUDIENCE) return null;
  const token = parseBearer(req.headers.authorization);
  if (!token) return null;
  const { payload } = await jwtVerify(token, jwks, { issuer: process.env.JWT_ISSUER, audience: process.env.JWT_AUDIENCE });
  // OIDC `sub` is an opaque, issuer-scoped identifier. Do not assume it is a UUID.
  if (typeof payload.sub !== 'string' || payload.sub.length < 1 || payload.sub.length > 255 ||
      typeof payload.iss !== 'string') return null;
  const issuer = payload.iss;
  const subject = payload.sub;
  const displayName = typeof payload.name === 'string' ? payload.name.trim().slice(0, 60) : 'MOMENTO user';
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    // Serialize first-login provisioning for this issuer/subject pair. This avoids
    // duplicate internal users when two API requests arrive simultaneously.
    await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [JSON.stringify([issuer, subject])]);
    const identity = await client.query(
      `SELECT ai.user_id, u.deleted_at
         FROM auth_identities ai JOIN users u ON u.id = ai.user_id
        WHERE ai.issuer = $1 AND ai.subject = $2`,
      [issuer, subject],
    );
    let userId;
    if (identity.rowCount) {
      if (identity.rows[0].deleted_at) {
        await client.query('ROLLBACK');
        return null;
      }
      userId = identity.rows[0].user_id;
      await client.query(
        `UPDATE auth_identities SET last_seen_at = now() WHERE issuer = $1 AND subject = $2`,
        [issuer, subject],
      );
    } else {
      const created = await client.query(
        `INSERT INTO users (display_name) VALUES ($1) RETURNING id`,
        [displayName || 'MOMENTO user'],
      );
      userId = created.rows[0].id;
      await client.query(
        `INSERT INTO auth_identities (issuer, subject, user_id) VALUES ($1, $2, $3)`,
        [issuer, subject, userId],
      );
    }
    await client.query('COMMIT');
    return userId;
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}


async function issueGoogleNonce() {
  await pool.query(
    `DELETE FROM auth_provider_nonces
      WHERE expires_at < now() - interval '1 day'
         OR consumed_at < now() - interval '1 day'`,
  );
  const nonce = randomBytes(32).toString('base64url');
  const digest = createHash('sha256').update(nonce, 'utf8').digest();
  await pool.query(
    `INSERT INTO auth_provider_nonces (nonce_hash, provider, expires_at)
     VALUES ($1, 'google', now() + interval '5 minutes')`,
    [digest],
  );
  return nonce;
}

async function consumeGoogleNonce(nonce) {
  if (typeof nonce !== 'string' || !/^[A-Za-z0-9_-]{40,64}$/.test(nonce)) return false;
  const digest = createHash('sha256').update(nonce, 'utf8').digest();
  const result = await pool.query(
    `UPDATE auth_provider_nonces SET consumed_at = now()
      WHERE nonce_hash = $1 AND provider = 'google'
        AND consumed_at IS NULL AND expires_at > now()
      RETURNING id`,
    [digest],
  );
  return result.rowCount === 1;
}

async function provisionProviderIdentity(identity) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [JSON.stringify([identity.issuer, identity.subject])]);
    const existing = await client.query(
      `SELECT ai.user_id, u.deleted_at FROM auth_identities ai
       JOIN users u ON u.id = ai.user_id WHERE ai.issuer = $1 AND ai.subject = $2`,
      [identity.issuer, identity.subject],
    );
    let userId;
    if (existing.rowCount) {
      if (existing.rows[0].deleted_at) throw Object.assign(new Error('ACCOUNT_UNAVAILABLE'), { status: 403 });
      userId = existing.rows[0].user_id;
      await client.query('UPDATE auth_identities SET last_seen_at = now() WHERE issuer = $1 AND subject = $2', [identity.issuer, identity.subject]);
    } else {
      const created = await client.query('INSERT INTO users (display_name) VALUES ($1) RETURNING id', [(identity.displayName || 'MOMENTO user').slice(0, 60)]);
      userId = created.rows[0].id;
      await client.query('INSERT INTO auth_identities (issuer, subject, user_id) VALUES ($1, $2, $3)', [identity.issuer, identity.subject, userId]);
    }
    await client.query('COMMIT');
    return userId;
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally { client.release(); }
}

async function requestContactOtp(body, purpose = 'SIGN_IN', userId = null) {
  const contactType = body.contactType;
  if (!['EMAIL', 'PHONE'].includes(contactType)) throw Object.assign(new Error('CONTACT_TYPE_INVALID'), { status: 422 });
  let contact;
  try { contact = normalizeContact(contactType, body.value); }
  catch { throw Object.assign(new Error('CONTACT_INVALID'), { status: 422 }); }
  const contactHash = hashContact(contactType, contact, process.env.CONTACT_HASH_SECRET);
  const contactCiphertext = encryptContact(contact, process.env.CONTACT_ENCRYPTION_KEY);
  if (purpose === 'LINK_CONTACT') {
    const owner = await pool.query(
      `SELECT c.user_id, u.deleted_at FROM user_contacts c
         JOIN users u ON u.id = c.user_id
        WHERE c.contact_type = $1 AND c.value_hash = $2 AND c.verified_at IS NOT NULL`,
      [contactType, contactHash],
    );
    if (owner.rowCount && owner.rows[0].deleted_at) throw Object.assign(new Error('CONTACT_ALREADY_IN_USE'), { status: 409 });
    if (owner.rowCount && owner.rows[0].user_id !== userId) throw Object.assign(new Error('CONTACT_ALREADY_IN_USE'), { status: 409 });
    if (owner.rowCount) throw Object.assign(new Error('CONTACT_ALREADY_LINKED'), { status: 409 });
  }
  const otp = createOtp();
  const id = randomUUID();
  const otpHash = hashOtp(id, otp, process.env.OTP_HMAC_SECRET);
  await reserveOtpChallenge(pool, {
    id, userId, purpose, contactType, contactHash, contactCiphertext, otpHash,
  });
  try {
    await deliverOtp(contactType, contact, otp);
  } catch (error) {
    await pool.query('DELETE FROM auth_challenges WHERE id = $1 AND consumed_at IS NULL', [id]);
    throw Object.assign(new Error(error.message), { status: 503 });
  }
  return { challengeId: id, expiresIn: 300 };
}

async function verifyContactOtp(body, expectedUserId = null, expectedPurpose = 'SIGN_IN') {
  if (typeof body.challengeId !== 'string' || !/^[0-9a-f-]{36}$/i.test(body.challengeId)) {
    throw Object.assign(new Error('CHALLENGE_INVALID'), { status: 422 });
  }
  if (typeof body.code !== 'string' || !/^\d{6}$/.test(body.code)) {
    throw Object.assign(new Error('OTP_INVALID'), { status: 422 });
  }
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const found = await client.query(
      `SELECT id, user_id, purpose, contact_type, contact_hash, contact_ciphertext, encryption_key_version,
              otp_hash, expires_at, attempts, consumed_at
         FROM auth_challenges WHERE id = $1 FOR UPDATE`,
      [body.challengeId],
    );
    if (!found.rowCount) throw Object.assign(new Error('OTP_INVALID_OR_EXPIRED'), { status: 401 });
    const challenge = found.rows[0];
    assertOtpChallengePurpose(challenge.purpose, expectedPurpose);
    if (challenge.purpose === 'LINK_CONTACT' && !expectedUserId) {
      throw Object.assign(new Error('LINK_SESSION_REQUIRED'), { status: 401 });
    }
    if (challenge.purpose === 'LINK_CONTACT' && challenge.user_id !== expectedUserId) {
      throw Object.assign(new Error('LINK_SESSION_MISMATCH'), { status: 403 });
    }
    if (challenge.consumed_at || new Date(challenge.expires_at) <= new Date() || challenge.attempts >= 5) {
      throw Object.assign(new Error('OTP_INVALID_OR_EXPIRED'), { status: 401 });
    }
    const valid = isValidOtp(challenge.id, body.code, challenge.otp_hash, process.env.OTP_HMAC_SECRET);
    if (!valid) {
      await client.query('UPDATE auth_challenges SET attempts = attempts + 1 WHERE id = $1', [challenge.id]);
      await client.query('COMMIT');
      throw Object.assign(new Error('OTP_INVALID_OR_EXPIRED'), { status: 401 });
    }
    await client.query('UPDATE auth_challenges SET consumed_at = now() WHERE id = $1', [challenge.id]);
    await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [`${challenge.contact_type}:${Buffer.from(challenge.contact_hash).toString('hex')}`]);
    const known = await client.query(
      `SELECT c.user_id, u.deleted_at FROM user_contacts c
         JOIN users u ON u.id = c.user_id
        WHERE c.contact_type = $1 AND c.value_hash = $2 AND c.verified_at IS NOT NULL`,
      [challenge.contact_type, challenge.contact_hash],
    );
    if (known.rowCount && known.rows[0].deleted_at) {
      throw Object.assign(new Error('ACCOUNT_UNAVAILABLE'), { status: 403 });
    }
    let resolvedUserId;
    if (challenge.purpose === 'LINK_CONTACT') {
      if (!challenge.user_id) throw Object.assign(new Error('LINK_SESSION_REQUIRED'), { status: 401 });
      if (known.rowCount && known.rows[0].user_id !== challenge.user_id) {
        throw Object.assign(new Error('CONTACT_ALREADY_IN_USE'), { status: 409 });
      }
      if (!known.rowCount) {
        await client.query(
          `INSERT INTO user_contacts (user_id, contact_type, value_hash, value_ciphertext, encryption_key_version, is_primary, verified_at)
           VALUES ($1, $2, $3, $4, $5, FALSE, now())`,
          [challenge.user_id, challenge.contact_type, challenge.contact_hash, challenge.contact_ciphertext, challenge.encryption_key_version],
        );
      }
      resolvedUserId = challenge.user_id;
    } else {
      if (known.rowCount) {
        resolvedUserId = known.rows[0].user_id;
      } else {
        const user = await client.query('INSERT INTO users (display_name) VALUES ($1) RETURNING id', ['MOMENTO user']);
        resolvedUserId = user.rows[0].id;
        await client.query(
          `INSERT INTO user_contacts (user_id, contact_type, value_hash, value_ciphertext, encryption_key_version, is_primary, verified_at)
           VALUES ($1, $2, $3, $4, $5, TRUE, now())`,
          [resolvedUserId, challenge.contact_type, challenge.contact_hash, challenge.contact_ciphertext, challenge.encryption_key_version],
        );
      }
    }
    await client.query('COMMIT');
    return { userId: resolvedUserId, purpose: challenge.purpose };
  } catch (error) {
    try { await client.query('ROLLBACK'); } catch {}
    throw error;
  } finally { client.release(); }
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    if (req.method === 'GET' && (url.pathname === '/health' || url.pathname === '/ready')) {
      await pool.query('SELECT 1');
      return send(res, 200, { status: 'ok', database: 'ok' });
    }
    if (req.method === 'POST' && url.pathname === '/v1/auth/logout') {
      const body = await readJson(req);
      if (typeof body.refreshToken !== 'string') return send(res, 400, { error: 'REFRESH_TOKEN_REQUIRED' });
      if (!isRefreshTokenShape(body.refreshToken)) return send(res, 401, { error: 'REFRESH_TOKEN_INVALID' });
      const refreshHash = hashRefreshToken(body.refreshToken, process.env.REFRESH_TOKEN_PEPPER);
      await pool.query(
        `UPDATE auth_sessions SET revoked_at = COALESCE(revoked_at, now()), revoked_reason = 'LOGOUT'
          WHERE refresh_token_hash = $1`,
        [refreshHash],
      );
      return send(res, 200, { success: true });
    }
    if (req.method === 'POST' && url.pathname === '/v1/auth/otp/request') {
      const result = await requestContactOtp(await readJson(req));
      return send(res, 202, result);
    }
    if (req.method === 'POST' && url.pathname === '/v1/auth/otp/verify') {
      const result = await verifyContactOtp(await readJson(req));
      if (result.purpose === 'LINK_CONTACT') return send(res, 200, { success: true, linked: true });
      return send(res, 201, await createSession(result.userId, 'Android'));
    }
    if (req.method === 'POST' && url.pathname === '/v1/auth/providers/google/nonce') {
      return send(res, 201, { nonce: await issueGoogleNonce(), expiresIn: 300 });
    }
    const providerMatch = /^\/v1\/auth\/providers\/(google|yandex|vk)$/i.exec(url.pathname);
    if (req.method === 'POST' && providerMatch) {
      const provider = providerMatch[1].toLowerCase();
      const body = await readJson(req);
      let identity;
      try {
        identity = await resolveProviderIdentity(provider, body, {
          audience: process.env.GOOGLE_SERVER_CLIENT_ID,
          expectedNonce: body.nonce,
        });
      } catch (error) {
        const message = String(error.message || '');
        const notConfigured = message.endsWith('_NOT_CONFIGURED');
        const upstreamUnavailable = /HTTP_(408|425|429|5\d\d)/.test(message) || message.includes('fetch failed') || message.includes('timeout');
        return send(res, notConfigured || upstreamUnavailable ? 503 : 401, {
          error: notConfigured ? 'PROVIDER_NOT_CONFIGURED' : upstreamUnavailable ? 'PROVIDER_TEMPORARILY_UNAVAILABLE' : 'PROVIDER_TOKEN_INVALID',
        });
      }
      if (provider === 'google' && !(await consumeGoogleNonce(body.nonce))) {
        return send(res, 401, { error: 'GOOGLE_NONCE_EXPIRED_OR_REPLAYED' });
      }
      const userId = await provisionProviderIdentity(identity);
      return send(res, 201, await createSession(userId, body.deviceLabel || 'Android'));
    }
    if (req.method === 'POST' && url.pathname === '/v1/auth/session') {
      // The configured upstream OIDC token is used only to establish identity.
      // All subsequent API calls use a MOMENTO-owned session token.
      const externalUserId = await authenticateExternal(req);
      if (!externalUserId) return send(res, 401, { error: 'IDENTITY_TOKEN_INVALID' });
      const body = await readJson(req);
      return send(res, 201, await createSession(externalUserId, body.deviceLabel));
    }
    if (req.method === 'POST' && url.pathname === '/v1/auth/refresh') {
      const body = await readJson(req);
      if (typeof body.refreshToken !== 'string') return send(res, 400, { error: 'REFRESH_TOKEN_REQUIRED' });
      if (!isRefreshTokenShape(body.refreshToken)) return send(res, 401, { error: 'REFRESH_TOKEN_INVALID' });
      const tokens = await rotateRefreshToken(body.refreshToken);
      if (!tokens) return send(res, 401, { error: 'REFRESH_TOKEN_INVALID_OR_REVOKED' });
      return send(res, 200, tokens);
    }

    // Read-only anonymous access is limited to published PUBLIC rooms and their media.
    if (req.method === 'GET' && url.pathname === '/v1/discovery/spaces' && !req.headers.authorization) {
      const query = url.searchParams.get('q') ?? '';
      if (query.length > 120) return send(res, 422, { error: 'INVALID_QUERY' });
      return send(res, 200, { items: await discoverSpaces(pool, null, query, 30) });
    }
    const guestTimeline = /^\/v1\/spaces\/([0-9a-f-]{36})\/moments$/i.exec(url.pathname);
    if (req.method === 'GET' && guestTimeline && !req.headers.authorization) {
      const result = await getRoomTimeline(pool, null, guestTimeline[1], null, null, 50);
      return send(res, result.error ? 404 : 200, result.error ? { error: 'ROOM_UNAVAILABLE' } : result);
    }
    const guestVideo = /^\/v1\/moments\/([0-9a-f-]{36})\/video$/i.exec(url.pathname);
    if (req.method === 'GET' && guestVideo && !req.headers.authorization) {
      const result = await pool.query(`SELECT a.storage_key FROM moments m
        JOIN media_assets a ON a.id=m.media_asset_id AND a.status='READY'
        JOIN moment_audiences ma ON ma.moment_id=m.id
        JOIN spaces s ON s.id=ma.space_id
        WHERE m.id=$1 AND m.deleted_at IS NULL AND m.moderation='VISIBLE'
          AND s.visibility='PUBLIC' AND s.published_at IS NOT NULL AND s.archived_at IS NULL LIMIT 1`, [guestVideo[1]]);
      if (!result.rowCount) return send(res, 404, { error: 'VIDEO_UNAVAILABLE' });
      const storageDir = process.env.MOMENTO_MEDIA_DIR;
      if (!storageDir) return send(res, 503, { error: 'MEDIA_STORAGE_NOT_CONFIGURED' });
      const key = result.rows[0].storage_key;
      if (!/^[0-9a-f-]{36}\.mp4$/i.test(key)) return send(res, 404, { error: 'VIDEO_UNAVAILABLE' });
      const path = join(storageDir, key);
      let info;
      try { info = await stat(path); } catch { return send(res, 404, { error: 'VIDEO_UNAVAILABLE' }); }
      res.writeHead(200, { 'content-type': 'video/mp4', 'content-length': info.size,
        'cache-control': 'private, no-store', 'x-content-type-options': 'nosniff' });
      createReadStream(path).on('error', () => res.destroy()).pipe(res);
      return;
    }
    const userId = await authenticate(req);
    if (!userId) return send(res, 401, { error: 'UNAUTHENTICATED' });
    if (req.method === 'POST' && url.pathname === '/v1/auth/link/otp/request') {
      return send(res, 202, await requestContactOtp(await readJson(req), 'LINK_CONTACT', userId));
    }
    if (req.method === 'POST' && url.pathname === '/v1/auth/link/otp/verify') {
      const result = await verifyContactOtp(await readJson(req), userId, 'LINK_CONTACT');
      if (result.purpose !== 'LINK_CONTACT' || result.userId !== userId) return send(res, 403, { error: 'LINK_SESSION_MISMATCH' });
      return send(res, 200, { success: true, linked: true });
    }
    const linkProviderMatch = /^\/v1\/auth\/link\/providers\/(google|yandex|vk)$/i.exec(url.pathname);
    if (req.method === 'POST' && linkProviderMatch) {
      const provider = linkProviderMatch[1].toLowerCase();
      const body = await readJson(req);
      let identity;
      try {
        identity = await resolveProviderIdentity(provider, body, {
          audience: process.env.GOOGLE_SERVER_CLIENT_ID,
          expectedNonce: body.nonce,
        });
      } catch (error) {
        const message = String(error.message || '');
        const notConfigured = message.endsWith('_NOT_CONFIGURED');
        const upstreamUnavailable = /HTTP_(408|425|429|5\d\d)/.test(message) || message.includes('fetch failed') || message.includes('timeout');
        return send(res, notConfigured || upstreamUnavailable ? 503 : 401, {
          error: notConfigured ? 'PROVIDER_NOT_CONFIGURED' : upstreamUnavailable ? 'PROVIDER_TEMPORARILY_UNAVAILABLE' : 'PROVIDER_TOKEN_INVALID',
        });
      }
      if (provider === 'google' && !(await consumeGoogleNonce(body.nonce))) {
        return send(res, 401, { error: 'GOOGLE_NONCE_EXPIRED_OR_REPLAYED' });
      }
      const client = await pool.connect();
      try {
        await client.query('BEGIN');
        await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [JSON.stringify([identity.issuer, identity.subject])]);
        const existing = await client.query(
          'SELECT user_id FROM auth_identities WHERE issuer = $1 AND subject = $2 FOR UPDATE',
          [identity.issuer, identity.subject],
        );
        if (existing.rowCount && existing.rows[0].user_id !== userId) {
          await client.query('ROLLBACK');
          return send(res, 409, { error: 'IDENTITY_ALREADY_LINKED' });
        }
        if (!existing.rowCount) {
          await client.query(
            'INSERT INTO auth_identities (issuer, subject, user_id) VALUES ($1, $2, $3)',
            [identity.issuer, identity.subject, userId],
          );
        }
        await client.query('COMMIT');
        return send(res, 200, { success: true, linked: true, provider });
      } catch (error) {
        await client.query('ROLLBACK');
        throw error;
      } finally { client.release(); }
    }
    const videoMatch = /^\/v1\/moments\/([0-9a-f-]{36})\/video$/i.exec(url.pathname);
    if (req.method === 'GET' && videoMatch && isUuid(videoMatch[1])) {
      const access=await pool.query(`SELECT a.storage_key FROM moments m
        JOIN media_assets a ON a.id=m.media_asset_id AND a.status='READY'
        WHERE m.id=$2 AND m.deleted_at IS NULL AND m.moderation='VISIBLE'
          AND (m.owner_id=$1 OR EXISTS(
            SELECT 1 FROM moment_audiences ma JOIN spaces s ON s.id=ma.space_id
            WHERE ma.moment_id=m.id AND s.archived_at IS NULL
              AND (EXISTS(SELECT 1 FROM space_members sm WHERE sm.space_id=s.id AND sm.user_id=$1)
                OR (s.visibility='PUBLIC' AND s.published_at IS NOT NULL))
              AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE
                (b.blocker_id=$1 AND b.blocked_id=s.owner_id) OR
                (b.blocker_id=s.owner_id AND b.blocked_id=$1))))`,[userId,videoMatch[1]]);
      if(!access.rowCount) return send(res,404,{error:'VIDEO_UNAVAILABLE'});
      const storageDir=process.env.MOMENTO_MEDIA_DIR;
      if(!storageDir) return send(res,503,{error:'MEDIA_STORAGE_NOT_CONFIGURED'});
      const key=access.rows[0].storage_key;
      if(!/^[0-9a-f-]{36}\.mp4$/i.test(key)) return send(res,404,{error:'VIDEO_UNAVAILABLE'});
      const path=join(storageDir,key);
      let info;
      try {info=await stat(path);} catch {return send(res,404,{error:'VIDEO_UNAVAILABLE'});}
      res.writeHead(200,{'content-type':'video/mp4','content-length':info.size,
        'cache-control':'private, no-store','x-content-type-options':'nosniff'});
      createReadStream(path).on('error',()=>res.destroy()).pipe(res);
      return;
    }
    if (req.method === 'POST' && url.pathname === '/v1/me/moments/upload') {
      const checked = validateUploadHeaders(req.headers);
      if (checked.error) return send(res, checked.status, { error: checked.error });
      const capturedAt = req.headers['x-momento-captured-at'];
      const bytes = await readVideoBytes(req, checked.length);
      const result = await storeMomentUpload(pool, userId, bytes, { capturedAt, clientRequestId: req.headers['x-momento-request-id'] });
      return result.error ? send(res, result.status || 422, { error: result.error }) : send(res, result.replayed ? 200 : 201, result);
    }
    if (req.method === 'PUT' && url.pathname === '/v1/auth/me/avatar') {
      const result = await updateAvatar(pool, userId, await readJson(req));
      return result.error ? send(res, 422, { error: result.error }) : send(res, 200, result);
    }
    if (req.method === 'PATCH' && url.pathname === '/v1/auth/me') {
      const parsed = validateDisplayName(await readJson(req));
      if (parsed.error) return send(res, 422, { error: parsed.error });
      const updated = await pool.query(
        'UPDATE users SET display_name = $2 WHERE id = $1 AND deleted_at IS NULL RETURNING id, display_name, created_at',
        [userId, parsed.value],
      );
      return updated.rowCount ? send(res, 200, { user: updated.rows[0] }) : send(res, 404, { error: 'USER_NOT_FOUND' });
    }
    if (req.method === 'GET' && url.pathname === '/v1/auth/me') {
      const result = await pool.query('SELECT id, display_name, avatar_media_id, created_at FROM users WHERE id = $1 AND deleted_at IS NULL', [userId]);
      return result.rowCount ? send(res, 200, { user: result.rows[0] }) : send(res, 404, { error: 'USER_NOT_FOUND' });
    }

    if (req.method === 'POST' && url.pathname === '/v1/me/capsules') {
      const result = await createCapsule(pool, userId, await readJson(req));
      return result.error ? send(res, 422, { error: result.error }) : send(res, result.replayed ? 200 : 201, result);
    }
    if (req.method === 'DELETE' && /^\/v1\/me\/capsules\/[0-9a-f-]+$/i.test(url.pathname)) {
      const capsuleId = url.pathname.slice('/v1/me/capsules/'.length);
      const result = await cancelCapsule(pool, userId, capsuleId);
      const status = result.error === 'INVALID_CAPSULE_ID' ? 422 : result.error ? 404 : 200;
      return send(res, status, result);
    }
    if (req.method === 'GET' && url.pathname === '/v1/me/capsules') {
      return send(res, 200, await listCapsules(pool, userId));
    }
    if (req.method === 'GET' && url.pathname === '/v1/me/calendar') {
      const options = parseCalendarOptions(url.searchParams);
      if (options.error) return send(res, 422, { error: options.error });
      return send(res, 200, await getPrivateCalendar(pool, userId, options));
    }
    if (req.method === 'GET' && url.pathname === '/v1/spaces') {
      return send(res, 200, { items: await listSpaces(pool, userId) });
    }
    if (req.method === 'GET' && url.pathname === '/v1/discovery/spaces') {
      const query = url.searchParams.get('q') ?? '';
      if (query.length > 120) return send(res, 422, { error: 'INVALID_QUERY' });
      return send(res, 200, { items: await discoverSpaces(pool, userId, query, 30) });
    }
    if (req.method === 'POST' && url.pathname === '/v1/spaces') {
      const body=await readJson(req);
      const validation = validateCreateSpace(body);
      if (validation.error) return send(res, 422, { error: 'VALIDATION_ERROR', message: validation.error });
      if (body.clientRoomId != null && !isUuid(body.clientRoomId)) return send(res,422,{error:'INVALID_CLIENT_ROOM_ID'});
      const created=await createSpace(pool,userId,validation.value,body.clientRoomId || null);
      return send(res,created.replayed?200:201,created);
    }
    if (req.method === 'GET' && url.pathname === '/v1/me/subscriptions') {
      return send(res,200,await getSubscriptions(pool,userId));
    }
    if (req.method === 'GET' && url.pathname === '/v1/me/subscription-updates') {
      return send(res,200,await getSubscriptionUpdates(pool,userId));
    }
    const roomDetails = /^\/v1\/spaces\/([0-9a-f-]{36})$/i.exec(url.pathname);
    if (req.method === 'GET' && roomDetails && isUuid(roomDetails[1])) {
      const result=await getPublicRoom(pool,userId,roomDetails[1]);
      return send(res,result.error?404:200,result);
    }
    const publish = /^\/v1\/spaces\/([0-9a-f-]{36})\/publish$/i.exec(url.pathname);
    if (req.method === 'POST' && publish && isUuid(publish[1])) {
      const result=await publishRoom(pool,userId,publish[1]);
      return send(res,result.error?403:200,result);
    }
    const attach = /^\/v1\/spaces\/([0-9a-f-]{36})\/moments$/i.exec(url.pathname);
    if (req.method === 'POST' && attach && isUuid(attach[1])) {
      const body=await readJson(req);
      if(!isUuid(body.momentId)) return send(res,422,{error:'INVALID_MOMENT_ID'});
      const result=await attachMoment(pool,userId,attach[1],body.momentId);
      return send(res,result.error?403:201,result);
    }
    const roomFollow = /^\/v1\/spaces\/([0-9a-f-]{36})\/subscription$/i.exec(url.pathname);
    if (roomFollow && isUuid(roomFollow[1]) && ['PUT','DELETE'].includes(req.method)) {
      const result=await setRoomSubscription(pool,userId,roomFollow[1],req.method==='PUT');
      return send(res,result.error?403:200,result);
    }
    const authorFollow = /^\/v1\/authors\/([0-9a-f-]{36})\/subscription$/i.exec(url.pathname);
    if (authorFollow && isUuid(authorFollow[1]) && ['PUT','DELETE'].includes(req.method)) {
      const result=await setAuthorSubscription(pool,userId,authorFollow[1],req.method==='PUT');
      return send(res,result.error?403:200,result);
    }
    const inviteMatch = /^\/v1\/spaces\/([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\/invites$/i.exec(url.pathname);
    if (req.method === 'POST' && inviteMatch) {
      const body = await readJson(req);
      const expiresInHours = boundedInteger(body.expiresInHours, 168, 1, 720);
      const maxUses = body.maxUses === undefined ? null : boundedInteger(body.maxUses, null, 1, 500);
      if (expiresInHours === null || (body.maxUses !== undefined && maxUses === null)) return send(res, 422, { error: 'VALIDATION_ERROR' });
      const result = await createInvite(pool, userId, inviteMatch[1], expiresInHours, maxUses, process.env.PUBLIC_APP_URL);
      if (result.error) return send(res, 403, { error: result.error });
      return send(res, 201, result);
    }
    const revokeMatch = /^\/v1\/spaces\/([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\/invites\/([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})$/i.exec(url.pathname);
    if (req.method === 'DELETE' && revokeMatch) {
      const result = await revokeInvite(pool, userId, revokeMatch[1], revokeMatch[2]);
      if (result.error === 'INVITE_INVALID') return send(res, 404, { error: result.error });
      if (result.error) return send(res, 403, { error: result.error });
      return send(res, 200, result);
    }
    const acceptMatch = /^\/v1\/invites\/([A-Za-z0-9_-]{40,64})\/accept$/.exec(url.pathname);
    if (req.method === 'POST' && acceptMatch) {
      const result = await acceptInvite(pool, userId, acceptMatch[1]);
      if (result.error === 'INVITE_INVALID') return send(res, 404, { error: result.error });
      if (result.error === 'INVITE_UNAVAILABLE') return send(res, 410, { error: result.error });
      if (result.error) return send(res, 403, { error: result.error });
      return send(res, 200, result);
    }
    const joinMatch = /^\/v1\/spaces\/([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\/join$/i.exec(url.pathname);
    if (req.method === 'POST' && joinMatch) {
      const result = await joinPublicSpace(pool, userId, joinMatch[1]);
      if (result.error === 'SPACE_UNAVAILABLE') return send(res, 404, { error: result.error });
      if (result.error === 'AGE_PROFILE_REQUIRED' || result.error === 'AGE_POLICY_RESTRICTED' || result.error === 'ACCESS_RESTRICTED') return send(res, 403, { error: result.error });
      if (result.error === 'INVITE_REQUIRED') return send(res, 403, { error: result.error });
      if (result.error) return send(res, 409, { error: result.error });
      return send(res, 200, result);
    }
    const requestsMatch = /^\/v1\/spaces\/([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\/join-requests$/i.exec(url.pathname);
    if (req.method === 'GET' && requestsMatch) {
      const result = await listJoinRequests(pool, userId, requestsMatch[1]);
      if (result.error) return send(res, 403, { error: result.error });
      return send(res, 200, result);
    }
    const reviewMatch = /^\/v1\/spaces\/([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\/join-requests\/([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\/review$/i.exec(url.pathname);
    if (req.method === 'POST' && reviewMatch) {
      const body = await readJson(req);
      const result = await reviewJoinRequest(pool, userId, reviewMatch[1], reviewMatch[2], body.decision);
      if (result.error === 'REQUEST_UNAVAILABLE') return send(res, 404, { error: result.error });
      if (result.error === 'AGE_PROFILE_REQUIRED' || result.error === 'AGE_POLICY_RESTRICTED' || result.error === 'ACCESS_RESTRICTED') return send(res, 403, { error: result.error });
      if (result.error === 'INVALID_DECISION') return send(res, 422, { error: result.error });
      if (result.error) return send(res, 403, { error: result.error });
      return send(res, 200, result);
    }
    const timelineMatch = /^\/v1\/spaces\/([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\/moments$/i.exec(url.pathname);
    if (req.method === 'GET' && timelineMatch) {
      const limit = boundedInteger(url.searchParams.get('limit') ?? undefined, 20, 1, 50);
      if (limit === null) return send(res, 422, { error: 'INVALID_LIMIT' });
      const cursor = validateTimelineCursor(url.searchParams.get('before'), url.searchParams.get('beforeId'));
      if (cursor.error) return send(res, 422, { error: 'INVALID_CURSOR', message: cursor.error });
      const result = await getRoomTimeline(pool, userId, timelineMatch[1], cursor.value.before, cursor.value.beforeId, limit);
      if (result.error) return send(res, 403, { error: result.error });
      return send(res, 200, result);
    }
    return send(res, 404, { error: 'NOT_FOUND' });
  } catch (error) {
    const isAuthError = typeof error.code === 'string' &&
      (error.code.startsWith('ERR_JWT') || error.code.startsWith('ERR_JWS') || error.code.startsWith('ERR_JWKS'));
    const status = error.status || (isAuthError || error.message?.includes('TOKEN_INVALID') ? 401 : error.code === '23503' ? 404 : 500);
    if (status >= 500) console.error('request_failed', { name: error.name, code: error.code });
    return send(res, status, { error: status >= 500 ? 'INTERNAL_ERROR' : status === 401 ? 'UNAUTHENTICATED' : error.message });
  }
});

server.listen(port, '0.0.0.0', () => process.stdout.write(`MOMENTO API listening on ${port}\n`));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(async () => { await pool.end(); process.exit(0); }));
