/** Live vlog operations. All SQL parameters are bound, and visibility is checked on each read. */
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export const isUuid = value => typeof value === 'string' && UUID.test(value);
export const isVisibleRoom = row => Boolean(row && !row.archived_at && (row.owner_id === row.viewer_id || row.is_member || (row.visibility === 'PUBLIC' && row.published_at)));

export async function publishRoom(pool, userId, spaceId) {
  const result = await pool.query(`UPDATE spaces s SET published_at=COALESCE(published_at,now())
    WHERE s.id=$1 AND s.owner_id=$2 AND s.visibility='PUBLIC' AND s.archived_at IS NULL
      AND s.completed_at IS NULL AND EXISTS (
        SELECT 1 FROM moment_audiences ma JOIN moments m ON m.id=ma.moment_id
        JOIN media_assets a ON a.id=m.media_asset_id
        WHERE ma.space_id=s.id AND m.deleted_at IS NULL AND a.status='READY')
    RETURNING id,published_at`, [spaceId,userId]);
  if (!result.rowCount) return {error:'ROOM_NOT_PUBLISHABLE'};
  await pool.query(`INSERT INTO room_updates(space_id,moment_id,author_id)
    SELECT ma.space_id,m.id,m.owner_id FROM moment_audiences ma
    JOIN moments m ON m.id=ma.moment_id AND m.deleted_at IS NULL
    JOIN media_assets a ON a.id=m.media_asset_id AND a.status='READY'
    WHERE ma.space_id=$1 ON CONFLICT(moment_id) DO NOTHING`,[spaceId]);
  return {room:result.rows[0]};
}

export async function attachMoment(pool,userId,spaceId,momentId) {
  const client=await pool.connect();
  try {
    await client.query('BEGIN');
    const permitted=await client.query(`SELECT s.id,s.visibility,s.published_at,s.owner_id FROM spaces s
      JOIN moments m ON m.id=$3 AND m.owner_id=$2 AND m.deleted_at IS NULL
      JOIN media_assets a ON a.id=m.media_asset_id AND a.status='READY'
      WHERE s.id=$1 AND s.owner_id=$2 AND s.archived_at IS NULL AND s.completed_at IS NULL
      FOR UPDATE OF s`,[spaceId,userId,momentId]);
    if (!permitted.rowCount) {await client.query('ROLLBACK');return {error:'FORBIDDEN_OR_MISSING'};}
    await client.query(`INSERT INTO moment_audiences(moment_id,space_id,granted_by)
      VALUES($1,$2,$3) ON CONFLICT DO NOTHING`,[momentId,spaceId,userId]);
    // Publishing a new moment is separate from storing it on the device or uploading the asset.
    if(permitted.rows[0].visibility==='PUBLIC' && permitted.rows[0].published_at) {
      await client.query(`INSERT INTO room_updates(space_id,moment_id,author_id)
        VALUES($1,$2,$3) ON CONFLICT(moment_id) DO NOTHING`,[spaceId,momentId,userId]);
    }
    await client.query('COMMIT');
    return {spaceId,momentId};
  } catch(e) {await client.query('ROLLBACK');throw e;} finally {client.release();}
}

export async function setRoomSubscription(pool,userId,spaceId,follow) {
  if(follow) {
    const r=await pool.query(`INSERT INTO room_subscriptions(user_id,space_id)
      SELECT $1,s.id FROM spaces s WHERE s.id=$2 AND s.visibility='PUBLIC'
        AND s.published_at IS NOT NULL AND s.archived_at IS NULL
        AND s.owner_id<>$1 AND NOT EXISTS (
          SELECT 1 FROM user_blocks b WHERE (b.blocker_id=$1 AND b.blocked_id=s.owner_id)
          OR (b.blocker_id=s.owner_id AND b.blocked_id=$1))
      ON CONFLICT(user_id,space_id) DO NOTHING RETURNING space_id`,[userId,spaceId]);
    // Already followed is a valid idempotent result.
    if(r.rowCount) return {following:true};
    const existing=await pool.query(`SELECT 1 FROM room_subscriptions WHERE user_id=$1 AND space_id=$2`,[userId,spaceId]);
    return existing.rowCount ? {following:true} : {error:'ROOM_UNAVAILABLE'};
  }
  await pool.query('DELETE FROM room_subscriptions WHERE user_id=$1 AND space_id=$2',[userId,spaceId]);
  return {following:false};
}

export async function setAuthorSubscription(pool,userId,authorId,follow) {
  if(userId===authorId) return {error:'SELF_SUBSCRIPTION'};
  if(follow) {
    const r=await pool.query(`INSERT INTO author_subscriptions(user_id,author_id)
      SELECT $1,u.id FROM users u WHERE u.id=$2 AND u.deleted_at IS NULL
        AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE
          (b.blocker_id=$1 AND b.blocked_id=$2) OR (b.blocker_id=$2 AND b.blocked_id=$1))
      ON CONFLICT(user_id,author_id) DO NOTHING RETURNING author_id`,[userId,authorId]);
    if(r.rowCount) return {following:true};
    const existing=await pool.query('SELECT 1 FROM author_subscriptions WHERE user_id=$1 AND author_id=$2',[userId,authorId]);
    return existing.rowCount ? {following:true} : {error:'AUTHOR_UNAVAILABLE'};
  }
  await pool.query('DELETE FROM author_subscriptions WHERE user_id=$1 AND author_id=$2',[userId,authorId]);
  return {following:false};
}

export async function getSubscriptions(pool,userId) {
  const rooms=await pool.query(`SELECT s.id,s.name,s.description,s.visibility,s.kind,s.join_policy,
      s.created_at, u.display_name AS author_name,s.owner_id,
      (SELECT COUNT(*)::int FROM room_subscriptions rs2 WHERE rs2.space_id=s.id) AS subscriber_count
    FROM room_subscriptions rs JOIN spaces s ON s.id=rs.space_id
    JOIN users u ON u.id=s.owner_id
    WHERE rs.user_id=$1 AND s.visibility='PUBLIC' AND s.published_at IS NOT NULL
      AND s.archived_at IS NULL AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE
        (b.blocker_id=$1 AND b.blocked_id=s.owner_id) OR (b.blocker_id=s.owner_id AND b.blocked_id=$1))
    ORDER BY rs.created_at DESC LIMIT 100`,[userId]);
  const authors=await pool.query(`SELECT u.id,u.display_name FROM author_subscriptions a
    JOIN users u ON u.id=a.author_id WHERE a.user_id=$1 AND u.deleted_at IS NULL
    ORDER BY a.created_at DESC LIMIT 100`,[userId]);
  return {rooms:rooms.rows,authors:authors.rows};
}

export async function getSubscriptionUpdates(pool,userId,limit=30) {
  const result=await pool.query(`SELECT DISTINCT ON (ru.id) ru.id,ru.space_id,ru.moment_id,
      ru.created_at,s.name AS room_name,u.display_name AS author_name
    FROM room_updates ru JOIN spaces s ON s.id=ru.space_id
    JOIN users u ON u.id=ru.author_id
    JOIN moments m ON m.id=ru.moment_id AND m.deleted_at IS NULL AND m.moderation='VISIBLE'
    WHERE s.visibility='PUBLIC' AND s.published_at IS NOT NULL AND s.archived_at IS NULL
      AND (EXISTS(SELECT 1 FROM room_subscriptions rs WHERE rs.space_id=s.id AND rs.user_id=$1)
        OR EXISTS(SELECT 1 FROM author_subscriptions a WHERE a.author_id=ru.author_id AND a.user_id=$1))
      AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE
        (b.blocker_id=$1 AND b.blocked_id=ru.author_id) OR (b.blocker_id=ru.author_id AND b.blocked_id=$1))
    ORDER BY ru.id DESC LIMIT $2`,[userId,limit]);
  return {items:result.rows};
}

export async function getPublicRoom(pool,userId,spaceId) {
  const r=await pool.query(`SELECT s.id,s.name,s.description,s.kind,s.visibility,s.join_policy,
      s.owner_id,s.created_at,s.published_at,s.completed_at,u.display_name AS author_name,
      (SELECT COUNT(*)::int FROM room_subscriptions rs WHERE rs.space_id=s.id) AS subscriber_count
    FROM spaces s JOIN users u ON u.id=s.owner_id
    WHERE s.id=$2 AND s.archived_at IS NULL AND
      (s.owner_id=$1 OR EXISTS(SELECT 1 FROM space_members sm WHERE sm.space_id=s.id AND sm.user_id=$1)
       OR (s.visibility='PUBLIC' AND s.published_at IS NOT NULL))
      AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE
        (b.blocker_id=$1 AND b.blocked_id=s.owner_id) OR (b.blocker_id=s.owner_id AND b.blocked_id=$1))`,[userId,spaceId]);
  return r.rowCount ? {room:r.rows[0]} : {error:'ROOM_UNAVAILABLE'};
}
