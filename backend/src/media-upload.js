import { createHash, randomUUID } from 'node:crypto';
import { mkdir, writeFile, rename, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);
export const MAX_VIDEO_BYTES = 25 * 1024 * 1024;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function validateClientRequestId(value) {
  return typeof value === 'string' && UUID.test(value) ? value.toLowerCase() : null;
}

export function validateUploadHeaders(headers) {
  if (headers['content-type'] !== 'video/mp4') return { error: 'UNSUPPORTED_MEDIA_TYPE', status: 415 };
  if (!validateClientRequestId(headers['x-momento-request-id'])) return { error: 'INVALID_CLIENT_REQUEST_ID', status: 422 };
  const raw = headers['content-length'];
  if (typeof raw !== 'string' || !/^[1-9]\d*$/.test(raw)) return { error: 'CONTENT_LENGTH_REQUIRED', status: 411 };
  const length = Number(raw);
  if (!Number.isSafeInteger(length) || length > MAX_VIDEO_BYTES) return { error: 'VIDEO_TOO_LARGE', status: 413 };
  return { length };
}

export async function readVideoBytes(req, length) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_VIDEO_BYTES || size > length) throw Object.assign(new Error('VIDEO_TOO_LARGE'), { status: 413 });
    chunks.push(chunk);
  }
  if (size !== length) throw Object.assign(new Error('VIDEO_LENGTH_MISMATCH'), { status: 422 });
  return Buffer.concat(chunks, size);
}

export function validateVideoProbe(probe) {
  const streams = Array.isArray(probe?.streams) ? probe.streams : [];
  const video = streams.filter(x => x.codec_type === 'video');
  if (video.length !== 1 || streams.some(x => !['video', 'audio'].includes(x.codec_type))) return { error: 'INVALID_VIDEO_STREAMS' };
  if (!['h264', 'hevc', 'av1'].includes(video[0].codec_name)) return { error: 'UNSUPPORTED_VIDEO_CODEC' };
  const duration = Number(probe?.format?.duration);
  const durationMs = Math.round(duration * 1000);
  if (!Number.isFinite(duration) || durationMs < 2000 || durationMs > 5000) return { error: 'INVALID_VIDEO_DURATION' };
  const width = Number(video[0].width), height = Number(video[0].height);
  if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1 || width > 4096 || height > 4096) return { error: 'INVALID_VIDEO_DIMENSIONS' };
  return { durationMs, width, height };
}

export async function storeMomentUpload(pool, userId, bytes, { storageDir, capturedAt, probeFn, clientRequestId } = {}) {
  if (!UUID.test(userId)) return { error: 'INVALID_USER' };
  if (!validateClientRequestId(clientRequestId)) return { error: 'INVALID_CLIENT_REQUEST_ID', status: 422 };
  const date = new Date(capturedAt);
  if (!capturedAt || Number.isNaN(date.getTime()) || date.getTime() > Date.now() + 5 * 60_000) return { error: 'INVALID_CAPTURE_DATE' };
  const dir = storageDir || process.env.MOMENTO_MEDIA_DIR;
  if (!dir) return { error: 'MEDIA_STORAGE_NOT_CONFIGURED', status: 503 };
  const id = randomUUID();
  const key = `${id}.mp4`;
  // Keep staging and final files on the same volume so rename stays atomic.
  const tmp = join(dir, `.${id}.uploading`);
  const target = join(dir, key);
  let published = false;
  let committed = false;
  try {
    await mkdir(dir, { recursive: true, mode: 0o700 });
    await writeFile(tmp, bytes, { flag: 'wx', mode: 0o600 });
    const probe = probeFn ? await probeFn(tmp) : JSON.parse((await execFileAsync('ffprobe', ['-v','error','-show_entries','format=duration:stream=codec_type,codec_name,width,height','-of','json',tmp], {timeout: 10000, maxBuffer: 64 * 1024})).stdout);
    const checked = validateVideoProbe(probe);
    if (checked.error) return checked;
    await rename(tmp, target);
    published = true;
    const checksum = createHash('sha256').update(bytes).digest();
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      // Claim a per-user request ID; a concurrent retry waits for the first transaction.
      await client.query(`INSERT INTO upload_idempotency (user_id, client_request_id)
        VALUES ($1,$2) ON CONFLICT DO NOTHING`, [userId, clientRequestId]);
      const claim = await client.query(`SELECT media_asset_id FROM upload_idempotency
        WHERE user_id=$1 AND client_request_id=$2 FOR UPDATE`, [userId, clientRequestId]);
      if (!claim.rows.length) throw new Error('IDEMPOTENCY_CLAIM_MISSING');
      if (claim.rows[0].media_asset_id) {
        const existing = await client.query(`SELECT m.id, m.media_asset_id, m.captured_at, m.duration_ms,
          a.checksum_sha256 FROM moments m JOIN media_assets a ON a.id=m.media_asset_id
          WHERE m.owner_id=$1 AND m.media_asset_id=$2`, [userId, claim.rows[0].media_asset_id]);
        if (!existing.rows.length) throw new Error('IDEMPOTENCY_ASSET_MISSING');
        if (!Buffer.from(existing.rows[0].checksum_sha256).equals(checksum)) {
          await client.query('COMMIT');
          return { error: 'IDEMPOTENCY_CONFLICT', status: 409 };
        }
        await client.query('COMMIT');
        const { checksum_sha256: ignored, ...moment } = existing.rows[0];
        return { moment, replayed: true };
      }
      await client.query(`INSERT INTO media_assets (id, owner_id, storage_key, mime_type, byte_size, checksum_sha256, duration_ms, width, height, status, uploaded_at)
        VALUES ($1,$2,$3,'video/mp4',$4,$5,$6,$7,$8,'READY',now())`, [id,userId,key,bytes.length,checksum,checked.durationMs,checked.width,checked.height]);
      const moment = await client.query(`INSERT INTO moments (owner_id, media_asset_id, captured_at, duration_ms)
        VALUES ($1,$2,$3,$4) RETURNING id, media_asset_id, captured_at, duration_ms`, [userId,id,date.toISOString(),checked.durationMs]);
      await client.query('UPDATE upload_idempotency SET media_asset_id=$3 WHERE user_id=$1 AND client_request_id=$2', [userId,clientRequestId,id]);
      await client.query('COMMIT');
      committed = true;
      return { moment: moment.rows[0] };
    } catch (error) { await client.query('ROLLBACK'); throw error; }
    finally { client.release(); }
  } catch (error) {
    return { error: 'VIDEO_UPLOAD_FAILED', status: 503 };
  } finally {
    await rm(tmp, {force:true}).catch(()=>{});
    // Keep files only when both DB inserts committed.
    // The route's success result is the only indicator of a committed upload.
    // On validation or DB failure remove the published file.
    if (published && !committed) await rm(target, {force:true}).catch(()=>{});
  }
}
