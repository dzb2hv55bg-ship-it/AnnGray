import { validateCapsuleRelease, validateVideoMoment } from './memory-features.js';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function validateCapsuleRequest(body, now = new Date()) {
  if (!body || typeof body !== 'object' || Array.isArray(body) ||
      typeof body.momentId !== 'string' || !UUID.test(body.momentId) ||
      typeof body.releaseAt !== 'string' ||
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(body.releaseAt)) {
    return { error: 'INVALID_CAPSULE' };
  }
  const release = new Date(body.releaseAt);
  if (!Number.isFinite(release.getTime()) || release.toISOString() !== new Date(body.releaseAt).toISOString() ||
      !validateCapsuleRelease(now, release)) return { error: 'INVALID_RELEASE_DATE' };
  return { value: { momentId: body.momentId, releaseAt: release.toISOString() } };
}

export async function createCapsule(pool, userId, body, now = new Date()) {
  const checked = validateCapsuleRequest(body, now);
  if (checked.error) return checked;
  const { momentId, releaseAt } = checked.value;
  // One SQL statement checks ownership, visibility, moderation and duration at insertion time.
  // Database clock determines the minimum release time to prevent clock-skew bypass.
  const result = await pool.query(`
    INSERT INTO time_capsules (owner_id, moment_id, release_at)
    SELECT $1, m.id, $3::timestamptz FROM moments m
    WHERE m.id = $2 AND m.owner_id = $1 AND m.deleted_at IS NULL
      AND m.moderation = 'VISIBLE' AND m.duration_ms BETWEEN 2000 AND 5000
      AND $3::timestamptz >= (now() + interval '1 year')
    ON CONFLICT (owner_id, moment_id, release_at) DO NOTHING
    RETURNING id, release_at, created_at`, [userId, momentId, releaseAt]);
  return result.rowCount ? { capsule: result.rows[0] } : { error: 'MOMENT_UNAVAILABLE_OR_CAPSULE_EXISTS' };
}

export async function listCapsules(pool, userId) {
  // No media identifiers or URLs are returned before release.
  const result = await pool.query(`
    SELECT c.id, c.release_at, c.created_at, c.opened_at,
      CASE WHEN c.release_at <= now() THEN c.moment_id ELSE NULL END AS moment_id,
      (c.release_at <= now()) AS unlocked
    FROM time_capsules c
    JOIN moments m ON m.id = c.moment_id AND m.owner_id = $1
    WHERE c.owner_id = $1 AND c.cancelled_at IS NULL AND m.deleted_at IS NULL
      AND m.moderation = 'VISIBLE' AND m.duration_ms BETWEEN 2000 AND 5000
    ORDER BY c.release_at ASC, c.id ASC LIMIT 200`, [userId]);
  return { items: result.rows };
}


// Cancellation is intentionally allowed only while the capsule is locked.
// A non-owner, unknown or already unlocked capsule yields the same response.
export async function cancelCapsule(pool, userId, capsuleId) {
  if (typeof capsuleId !== 'string' || !UUID.test(capsuleId)) {
    return { error: 'INVALID_CAPSULE_ID' };
  }
  const result = await pool.query(`
    UPDATE time_capsules
    SET cancelled_at = now()
    WHERE id = $1 AND owner_id = $2
      AND cancelled_at IS NULL AND release_at > now()
    RETURNING id, cancelled_at`, [capsuleId, userId]);
  return result.rowCount
    ? { capsule: result.rows[0] }
    : { error: 'CAPSULE_NOT_FOUND_OR_LOCKED' };
}
