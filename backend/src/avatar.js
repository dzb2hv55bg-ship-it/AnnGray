// The avatar can only reference the user's own ready, moderated 2–5 second video.
// No external URL or arbitrary file path is accepted.
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function validateAvatarRequest(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body) ||
      !Object.hasOwn(body, 'mediaAssetId') ||
      (body.mediaAssetId !== null && (typeof body.mediaAssetId !== 'string' || !UUID.test(body.mediaAssetId)))) {
    return { error: 'INVALID_AVATAR_MEDIA' };
  }
  return { mediaAssetId: body.mediaAssetId };
}

export async function updateAvatar(pool, userId, body) {
  const checked = validateAvatarRequest(body);
  if (checked.error) return checked;
  // Atomic owner check prevents TOCTOU when an asset is deleted or quarantined.
  const result = await pool.query(`
    UPDATE users u SET avatar_media_id = $2
    WHERE u.id = $1 AND u.deleted_at IS NULL AND (
      $2::uuid IS NULL OR EXISTS (
        SELECT 1 FROM media_assets a
        WHERE a.id = $2::uuid AND a.owner_id = u.id
          AND a.status = 'READY' AND a.deleted_at IS NULL
          AND a.mime_type IN ('video/mp4', 'video/webm')
          AND a.duration_ms BETWEEN 2000 AND 5000
          AND a.storage_key IS NOT NULL
      )
    )
    RETURNING u.id, u.display_name, u.avatar_media_id, u.created_at`, [userId, checked.mediaAssetId]);
  return result.rowCount ? { user: result.rows[0] } : { error: 'AVATAR_UNAVAILABLE' };
}
