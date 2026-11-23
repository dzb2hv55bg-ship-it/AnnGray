import { createInviteToken, hashInviteToken } from './security.js';

const SPACE_KINDS = new Set(['CIRCLE', 'INTEREST_ROOM']);
const VISIBILITIES = new Set(['PRIVATE', 'PUBLIC']);
const JOIN_POLICIES = new Set(['INVITE_ONLY', 'OPEN', 'APPROVAL']);

export function validateCreateSpace(body) {
  const name = typeof body?.name === 'string' ? body.name.trim() : '';
  const description = typeof body?.description === 'string' ? body.description.trim() : '';
  const kind = body?.kind;
  const visibility = body?.visibility;
  const joinPolicy = body?.joinPolicy;
  if (name.length < 2 || name.length > 60) return { error: 'name must contain 2–60 characters' };
  if (description.length > 500) return { error: 'description must be at most 500 characters' };
  if (!SPACE_KINDS.has(kind) || !VISIBILITIES.has(visibility) || !JOIN_POLICIES.has(joinPolicy)) {
    return { error: 'invalid room kind, visibility or join policy' };
  }
  if (visibility === 'PRIVATE' && joinPolicy !== 'INVITE_ONLY') {
    return { error: 'private rooms must use INVITE_ONLY' };
  }
  return { value: { name, description, kind, visibility, joinPolicy } };
}

export async function createSpace(pool, userId, input, clientRoomId = null) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    if (clientRoomId) {
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))', [userId+':'+clientRoomId]);
      const existing = await client.query(`SELECT s.id,s.name,s.description,s.kind,s.visibility,s.join_policy,s.created_at
        FROM room_creation_keys k JOIN spaces s ON s.id=k.space_id
        WHERE k.user_id=$1 AND k.client_room_id=$2 FOR UPDATE OF k`, [userId,clientRoomId]);
      if (existing.rowCount) { await client.query('COMMIT'); return {...existing.rows[0],memberCount:1,replayed:true}; }
    }
    const result = await client.query(
      `INSERT INTO spaces (owner_id, name, description, kind, visibility, join_policy)
       VALUES ($1, $2, $3, $4::space_kind, $5::space_visibility, $6::join_policy)
       RETURNING id, name, description, kind, visibility, join_policy, created_at`,
      [userId, input.name, input.description, input.kind, input.visibility, input.joinPolicy],
    );
    const space = result.rows[0];
    await client.query(
      `INSERT INTO space_members (space_id, user_id, role) VALUES ($1, $2, 'OWNER')`,
      [space.id, userId],
    );
    if (clientRoomId) await client.query(`INSERT INTO room_creation_keys(user_id,client_room_id,space_id) VALUES($1,$2,$3)`,[userId,clientRoomId,space.id]);
    await client.query('COMMIT');
    return { ...space, memberCount: 1 };
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}

export async function listSpaces(pool, userId) {
  const result = await pool.query(
    `SELECT s.id, s.name, s.description, s.kind, s.visibility, s.join_policy,
            s.created_at, COUNT(m.user_id)::int AS member_count
       FROM spaces s
       JOIN space_members mine ON mine.space_id = s.id AND mine.user_id = $1
       JOIN space_members m ON m.space_id = s.id
      WHERE s.archived_at IS NULL
      GROUP BY s.id
      ORDER BY s.created_at DESC
      LIMIT 100`,
    [userId],
  );
  return result.rows.map((row) => ({ ...row, memberCount: row.member_count }));
}

export async function createInvite(pool, userId, spaceId, expiresInHours, maxUses, publicBaseUrl) {
  const token = createInviteToken();
  const tokenHash = hashInviteToken(token);
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const membership = await client.query(
      `SELECT sm.role FROM space_members sm JOIN spaces s ON s.id = sm.space_id
        WHERE sm.space_id = $1 AND sm.user_id = $2 AND s.archived_at IS NULL FOR SHARE OF sm`,
      [spaceId, userId],
    );
    if (!membership.rowCount || !['OWNER', 'ADMIN', 'MODERATOR'].includes(membership.rows[0].role)) {
      await client.query('ROLLBACK');
      return { error: 'FORBIDDEN' };
    }
    const inserted = await client.query(
      `INSERT INTO space_invites (space_id, created_by, token_hash, expires_at, max_uses)
       VALUES ($1, $2, $3, now() + ($4::int * interval '1 hour'), $5)
       RETURNING id, expires_at`,
      [spaceId, userId, tokenHash, expiresInHours, maxUses],
    );
    await client.query('COMMIT');
    return { inviteId: inserted.rows[0].id, inviteUrl: `${publicBaseUrl.replace(/\/$/, '')}/invite/${token}`, expiresAt: inserted.rows[0].expires_at };
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}

export async function revokeInvite(pool, userId, spaceId, inviteId) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const permission = await client.query(
      `SELECT sm.role FROM space_members sm JOIN spaces s ON s.id = sm.space_id
        WHERE sm.space_id = $1 AND sm.user_id = $2 AND s.archived_at IS NULL
        FOR SHARE OF sm`,
      [spaceId, userId],
    );
    if (!permission.rowCount || !['OWNER', 'ADMIN'].includes(permission.rows[0].role)) {
      await client.query('ROLLBACK');
      return { error: 'FORBIDDEN' };
    }
    const revoked = await client.query(
      `UPDATE space_invites SET revoked_at = COALESCE(revoked_at, now())
        WHERE id = $1 AND space_id = $2 RETURNING id, revoked_at`,
      [inviteId, spaceId],
    );
    if (!revoked.rowCount) {
      await client.query('ROLLBACK');
      return { error: 'INVITE_INVALID' };
    }
    await client.query('COMMIT');
    return { inviteId: revoked.rows[0].id, revokedAt: revoked.rows[0].revoked_at };
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}

export async function acceptInvite(pool, userId, token) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const inviteResult = await client.query(
      `SELECT i.id, i.space_id, i.expires_at, i.max_uses, i.use_count, i.revoked_at,
              s.age_policy_key, s.archived_at
         FROM space_invites i JOIN spaces s ON s.id = i.space_id
        WHERE i.token_hash = $1 FOR UPDATE OF i`,
      [hashInviteToken(token)],
    );
    if (!inviteResult.rowCount) {
      await client.query('ROLLBACK');
      return { error: 'INVITE_INVALID' };
    }
    const invite = inviteResult.rows[0];
    if (invite.archived_at || invite.revoked_at || new Date(invite.expires_at) <= new Date() ||
        (invite.max_uses !== null && invite.use_count >= invite.max_uses)) {
      await client.query('ROLLBACK');
      return { error: 'INVITE_UNAVAILABLE' };
    }

    const block = await client.query(
      `SELECT 1 FROM user_blocks b
        JOIN space_members sm ON sm.space_id = $2
         AND ((b.blocker_id = $1 AND b.blocked_id = sm.user_id)
           OR (b.blocked_id = $1 AND b.blocker_id = sm.user_id))
       LIMIT 1`,
      [userId, invite.space_id],
    );
    if (block.rowCount) {
      await client.query('ROLLBACK');
      return { error: 'ACCESS_RESTRICTED' };
    }

    // Fail closed until all participants have a configured, compatible age band.
    const age = await checkAgeCompatibility(client, userId, invite.space_id);
    if (age.error) {
      await client.query('ROLLBACK');
      return age;
    }

    const membershipInsert = await client.query(
      `INSERT INTO space_members (space_id, user_id, role) VALUES ($1, $2, 'MEMBER')
       ON CONFLICT (space_id, user_id) DO NOTHING`,
      [invite.space_id, userId],
    );
    if (membershipInsert.rowCount) {
      await client.query(
        `UPDATE space_invites SET use_count = use_count + 1 WHERE id = $1`,
        [invite.id],
      );
    }
    await client.query('COMMIT');
    return { spaceId: invite.space_id, joined: membershipInsert.rowCount === 1, alreadyMember: membershipInsert.rowCount === 0 };
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}


export async function discoverSpaces(pool, userId, query = '', limit = 30) {
  const cleanQuery = query.trim().slice(0, 80).replace(/[\\%_]/g, (character) => `\\${character}`);
  const result = await pool.query(
    `SELECT s.id, s.name, s.description, s.kind, s.visibility, s.join_policy,
            s.created_at, COUNT(sm.user_id)::int AS member_count,
            EXISTS (SELECT 1 FROM space_members mine WHERE mine.space_id = s.id AND mine.user_id = $1) AS is_member,
            EXISTS (SELECT 1 FROM join_requests jr WHERE jr.space_id = s.id AND jr.user_id = $1 AND jr.status = 'PENDING') AS request_pending
       FROM spaces s
       LEFT JOIN space_members sm ON sm.space_id = s.id
      WHERE s.visibility = 'PUBLIC' AND s.published_at IS NOT NULL AND s.archived_at IS NULL
        AND ($2 = '' OR s.name ILIKE '%' || $2 || '%' ESCAPE '\\' OR s.description ILIKE '%' || $2 || '%' ESCAPE '\\')
      GROUP BY s.id
      ORDER BY s.created_at DESC, s.id DESC
      LIMIT $3`,
    [userId, cleanQuery, limit],
  );
  return result.rows.map((row) => ({ ...row, memberCount: row.member_count, isMember: Boolean(row.is_member), requestPending: Boolean(row.request_pending) }));
}

async function checkAgeCompatibility(client, userId, spaceId) {
  const profiles = await client.query(
    `SELECT u.id AS user_id, ap.age_band
       FROM users u LEFT JOIN user_age_profiles ap ON ap.user_id = u.id
      WHERE u.id = $1 OR u.id IN (SELECT user_id FROM space_members WHERE space_id = $2)`,
    [userId, spaceId],
  );
  const ownProfile = profiles.rows.find((row) => row.user_id === userId);
  if (!ownProfile || profiles.rows.some((row) => !row.age_band)) return { error: 'AGE_PROFILE_REQUIRED' };
  if (profiles.rows.some((row) => row.user_id !== userId && row.age_band !== ownProfile.age_band)) {
    return { error: 'AGE_POLICY_RESTRICTED' };
  }
  return { ok: true };
}

export async function joinPublicSpace(pool, userId, spaceId) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const spaceResult = await client.query(
      `SELECT visibility, join_policy, archived_at FROM spaces WHERE id = $1 FOR UPDATE`, [spaceId],
    );
    if (!spaceResult.rowCount || spaceResult.rows[0].archived_at || spaceResult.rows[0].visibility !== 'PUBLIC') {
      await client.query('ROLLBACK');
      return { error: 'SPACE_UNAVAILABLE' };
    }
    const space = spaceResult.rows[0];
    const existing = await client.query(
      `SELECT 1 FROM space_members WHERE space_id = $1 AND user_id = $2`, [spaceId, userId],
    );
    if (existing.rowCount) {
      await client.query('COMMIT');
      return { spaceId, joined: false, alreadyMember: true };
    }
    const block = await client.query(
      `SELECT 1 FROM user_blocks b JOIN space_members sm ON sm.space_id = $2
        AND ((b.blocker_id = $1 AND b.blocked_id = sm.user_id) OR (b.blocked_id = $1 AND b.blocker_id = sm.user_id)) LIMIT 1`,
      [userId, spaceId],
    );
    if (block.rowCount) {
      await client.query('ROLLBACK');
      return { error: 'ACCESS_RESTRICTED' };
    }
    const age = await checkAgeCompatibility(client, userId, spaceId);
    if (age.error) {
      await client.query('ROLLBACK');
      return age;
    }
    if (space.join_policy === 'INVITE_ONLY') {
      await client.query('ROLLBACK');
      return { error: 'INVITE_REQUIRED' };
    }
    if (space.join_policy === 'APPROVAL') {
      const request = await client.query(
        `INSERT INTO join_requests (space_id, user_id, status) VALUES ($1, $2, 'PENDING')
         ON CONFLICT (space_id, user_id) DO UPDATE SET status = 'PENDING', created_at = now()
         WHERE join_requests.status IN ('REJECTED', 'CANCELLED')
         RETURNING id, status`, [spaceId, userId],
      );
      if (!request.rowCount) {
        const current = await client.query(`SELECT id, status FROM join_requests WHERE space_id = $1 AND user_id = $2`, [spaceId, userId]);
        await client.query('COMMIT');
        return { spaceId, requestId: current.rows[0]?.id ?? null, requestPending: current.rows[0]?.status === 'PENDING' };
      }
      await client.query('COMMIT');
      return { spaceId, requestId: request.rows[0].id, requestPending: true };
    }
    if (space.join_policy !== 'OPEN') {
      await client.query('ROLLBACK');
      return { error: 'JOIN_POLICY_UNAVAILABLE' };
    }
    await client.query(`INSERT INTO space_members (space_id, user_id, role) VALUES ($1, $2, 'MEMBER') ON CONFLICT DO NOTHING`, [spaceId, userId]);
    await client.query('COMMIT');
    return { spaceId, joined: true, alreadyMember: false };
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}

export async function listJoinRequests(pool, userId, spaceId) {
  const permission = await pool.query(
    `SELECT 1 FROM space_members WHERE space_id = $1 AND user_id = $2 AND role IN ('OWNER', 'ADMIN', 'MODERATOR')`,
    [spaceId, userId],
  );
  if (!permission.rowCount) return { error: 'FORBIDDEN' };
  const result = await pool.query(
    `SELECT jr.id, jr.user_id, u.display_name, jr.status, jr.created_at
       FROM join_requests jr JOIN users u ON u.id = jr.user_id
      WHERE jr.space_id = $1 AND jr.status = 'PENDING'
      ORDER BY jr.created_at ASC LIMIT 100`, [spaceId],
  );
  return { items: result.rows };
}

export async function reviewJoinRequest(pool, userId, spaceId, requestId, decision) {
  if (!['APPROVED', 'REJECTED'].includes(decision)) return { error: 'INVALID_DECISION' };
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const permission = await client.query(
      `SELECT role FROM space_members WHERE space_id = $1 AND user_id = $2 AND role IN ('OWNER', 'ADMIN', 'MODERATOR') FOR SHARE`,
      [spaceId, userId],
    );
    if (!permission.rowCount) {
      await client.query('ROLLBACK');
      return { error: 'FORBIDDEN' };
    }
    const request = await client.query(
      `SELECT jr.user_id, jr.status, s.visibility, s.join_policy, s.archived_at
         FROM join_requests jr JOIN spaces s ON s.id = jr.space_id
        WHERE jr.id = $1 AND jr.space_id = $2 FOR UPDATE OF jr, s`, [requestId, spaceId],
    );
    if (!request.rowCount || request.rows[0].status !== 'PENDING' || request.rows[0].archived_at || request.rows[0].visibility !== 'PUBLIC' || request.rows[0].join_policy !== 'APPROVAL') {
      await client.query('ROLLBACK');
      return { error: 'REQUEST_UNAVAILABLE' };
    }
    const applicantId = request.rows[0].user_id;
    if (decision === 'APPROVED') {
      const block = await client.query(
        `SELECT 1 FROM user_blocks b JOIN space_members sm ON sm.space_id = $2
          AND ((b.blocker_id = $1 AND b.blocked_id = sm.user_id) OR (b.blocked_id = $1 AND b.blocker_id = sm.user_id)) LIMIT 1`,
        [applicantId, spaceId],
      );
      if (block.rowCount) {
        await client.query('ROLLBACK');
        return { error: 'ACCESS_RESTRICTED' };
      }
      const age = await checkAgeCompatibility(client, applicantId, spaceId);
      if (age.error) {
        await client.query('ROLLBACK');
        return age;
      }
      await client.query(`INSERT INTO space_members (space_id, user_id, role) VALUES ($1, $2, 'MEMBER') ON CONFLICT DO NOTHING`, [spaceId, applicantId]);
    }
    await client.query(`UPDATE join_requests SET status = $3 WHERE id = $1 AND space_id = $2`, [requestId, spaceId, decision]);
    await client.query('COMMIT');
    return { requestId, status: decision, joined: decision === 'APPROVED' };
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}

export function validateTimelineCursor(before, beforeId) {
  if ((before && !beforeId) || (!before && beforeId)) return { error: 'before and beforeId must be supplied together' };
  if (!before) return { value: { before: null, beforeId: null } };
  if (Number.isNaN(Date.parse(before))) return { error: 'before must be a valid date-time' };
  if (typeof beforeId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(beforeId)) {
    return { error: 'beforeId must be a UUID' };
  }
  return { value: { before, beforeId } };
}

export async function getRoomTimeline(pool, userId, spaceId, before, beforeId, limit) {
  const member = await pool.query(
    `SELECT 1 FROM spaces s WHERE s.id=$1 AND s.archived_at IS NULL AND
       (EXISTS(SELECT 1 FROM space_members sm WHERE sm.space_id=s.id AND sm.user_id=$2)
        OR (s.visibility='PUBLIC' AND s.published_at IS NOT NULL))
       AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE
         (b.blocker_id=$2 AND b.blocked_id=s.owner_id) OR
         (b.blocker_id=s.owner_id AND b.blocked_id=$2))`, [spaceId, userId],
  );
  if (!member.rowCount) return { error: 'FORBIDDEN' };
  const result = await pool.query(
    `SELECT mo.id, mo.owner_id, mo.captured_at::text AS captured_at, mo.duration_ms, mo.caption,
            u.display_name
       FROM moment_audiences ma
       JOIN moments mo ON mo.id = ma.moment_id
       JOIN users u ON u.id = mo.owner_id
       JOIN media_assets media ON media.id = mo.media_asset_id
      WHERE ma.space_id = $1 AND mo.deleted_at IS NULL
        AND mo.moderation = 'VISIBLE' AND media.status = 'READY'
        AND ($2::timestamptz IS NULL OR (mo.captured_at, mo.id) < ($2::timestamptz, $3::uuid))
      ORDER BY mo.captured_at DESC, mo.id DESC
      LIMIT $4`,
    [spaceId, before ?? null, beforeId ?? null, limit],
  );
  const last = result.rows.at(-1);
  return {
    items: result.rows,
    nextCursor: last ? { before: last.captured_at, beforeId: last.id } : null,
  };
}
