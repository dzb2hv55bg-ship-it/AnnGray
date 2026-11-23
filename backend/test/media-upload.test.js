import test from 'node:test';
import assert from 'node:assert/strict';
import { validateUploadHeaders, validateVideoProbe, readVideoBytes, storeMomentUpload, MAX_VIDEO_BYTES, validateClientRequestId } from '../src/media-upload.js';

const probe = { format:{duration:'3.5'}, streams:[{codec_type:'video',codec_name:'h264',width:1080,height:1920}] };
test('accepts bounded mp4 uploads',()=>assert.equal(validateUploadHeaders({'content-type':'video/mp4','content-length':'1024','x-momento-request-id':'123e4567-e89b-42d3-a456-426614174000'}).length,1024));
test('rejects missing content length',()=>assert.equal(validateUploadHeaders({'content-type':'video/mp4','x-momento-request-id':'123e4567-e89b-42d3-a456-426614174000'}).status,411));
test('rejects oversized uploads',()=>assert.equal(validateUploadHeaders({'content-type':'video/mp4','content-length':String(MAX_VIDEO_BYTES+1),'x-momento-request-id':'123e4567-e89b-42d3-a456-426614174000'}).status,413));
test('rejects other media types',()=>assert.equal(validateUploadHeaders({'content-type':'image/jpeg','content-length':'100','x-momento-request-id':'123e4567-e89b-42d3-a456-426614174000'}).status,415));
test('accepts 3.5s video',()=>assert.equal(validateVideoProbe(probe).durationMs,3500));
test('rejects long videos',()=>assert.equal(validateVideoProbe({...probe,format:{duration:'5.01'}}).error,'INVALID_VIDEO_DURATION'));
test('rejects audio-only',()=>assert.equal(validateVideoProbe({format:{duration:'3'},streams:[{codec_type:'audio'}]}).error,'INVALID_VIDEO_STREAMS'));
test('rejects malformed video dimensions',()=>assert.equal(validateVideoProbe({...probe,streams:[{...probe.streams[0],width:0}]}).error,'INVALID_VIDEO_DIMENSIONS'));
test('rejects unconfigured storage without writing',async()=>assert.equal((await storeMomentUpload({},'123e4567-e89b-42d3-a456-426614174000',Buffer.from('x'),{capturedAt:new Date().toISOString(),storageDir:'',clientRequestId:'123e4567-e89b-42d3-a456-426614174000'})).error,'MEDIA_STORAGE_NOT_CONFIGURED'));
test('rejects truncated stream',async()=>{const req=(async function*(){yield Buffer.from('abc')})();await assert.rejects(readVideoBytes(req,4),/VIDEO_LENGTH_MISMATCH/)});

test('rejects uploads without a stable idempotency key',()=>assert.equal(validateUploadHeaders({'content-type':'video/mp4','content-length':'12'}).error,'INVALID_CLIENT_REQUEST_ID'));
test('rejects malformed idempotency keys',()=>assert.equal(validateClientRequestId('bad'),null));
test('normalizes valid idempotency keys',()=>assert.equal(validateClientRequestId('123E4567-E89B-42D3-A456-426614174000'),'123e4567-e89b-42d3-a456-426614174000'));

// Behavioral test of a lost HTTP acknowledgement: the same request ID must
// return the previously committed moment without inserting a second asset.
test('upload retry after lost acknowledgement reuses the committed moment', async () => {
  const { mkdtemp, rm } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os');
  const { join } = await import('node:path');
  const dir = await mkdtemp(join(tmpdir(), 'momento-idempotency-'));
  const owner = '123e4567-e89b-42d3-a456-426614174000';
  const request = '123e4567-e89b-42d3-a456-426614174001';
  const asset = '123e4567-e89b-42d3-a456-426614174002';
  const momentId = '123e4567-e89b-42d3-a456-426614174003';
  const saved = { assetId: null, moment: null, checksum: null };
  let inserts = 0;
  const client = {
    async query(sql, params) {
      if (sql.startsWith('SELECT media_asset_id FROM upload_idempotency')) return { rows: [{ media_asset_id: saved.assetId }] };
      if (sql.startsWith('SELECT m.id, m.media_asset_id')) return { rows: [{ ...saved.moment, checksum_sha256: saved.checksum }] };
      if (sql.startsWith('INSERT INTO media_assets')) { inserts++; saved.checksum = params[4]; return { rows: [] }; }
      if (sql.startsWith('INSERT INTO moments')) { saved.moment = { id: momentId, media_asset_id: params[1], captured_at: params[2], duration_ms: params[3] }; return { rows: [saved.moment] }; }
      if (sql.startsWith('UPDATE upload_idempotency')) saved.assetId = params[2];
      return { rows: [] };
    },
    release() {}
  };
  const pool = { async connect() { return client; } };
  const args = { storageDir: dir, capturedAt: new Date().toISOString(), clientRequestId: request, probeFn: async () => probe };
  try {
    const bytes = Buffer.from('sample-mp4-fixture');
    const first = await storeMomentUpload(pool, owner, bytes, args);
    const second = await storeMomentUpload(pool, owner, bytes, args);
    assert.equal(first.moment?.id, momentId);
    assert.equal(second.moment?.id, momentId);
    assert.equal(second.replayed, true);
    assert.equal(inserts, 1);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

// Behavioral ownership boundary: an idempotency key is scoped to the verified
// server user, so another account must never inherit the first user's asset.
test('same upload request ID for two accounts never reuses the other account moment', async () => {
  const { mkdtemp, rm } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os');
  const { join } = await import('node:path');
  const { randomUUID } = await import('node:crypto');
  const dir = await mkdtemp(join(tmpdir(), 'momento-account-isolation-'));
  const ownerA = randomUUID(), ownerB = randomUUID(), request = randomUUID();
  const claims = new Map();
  const moments = new Map();
  let owner = null;
  let assets = 0;
  const client = {
    async query(sql, params = []) {
      if (sql.startsWith('INSERT INTO upload_idempotency')) {
        owner = params[0];
        if (!claims.has(owner)) claims.set(owner, null);
      }
      if (sql.startsWith('SELECT media_asset_id FROM upload_idempotency'))
        return {rows: [{media_asset_id: claims.get(params[0])}]};
      if (sql.startsWith('INSERT INTO media_assets')) assets++;
      if (sql.startsWith('INSERT INTO moments')) {
        const moment = {id: randomUUID(), media_asset_id: params[1], captured_at: params[2], duration_ms: params[3]};
        moments.set(owner, moment);
        return {rows: [moment]};
      }
      if (sql.startsWith('UPDATE upload_idempotency')) claims.set(params[0], params[2]);
      if (sql.startsWith('SELECT m.id, m.media_asset_id')) return {rows: []};
      return {rows: []};
    }, release() {}
  };
  const pool = {async connect() {return client;}};
  const args = {storageDir: dir, capturedAt: new Date().toISOString(), clientRequestId: request, probeFn: async () => probe};
  try {
    const bytes = Buffer.from('test-camera-video');
    const a = await storeMomentUpload(pool, ownerA, bytes, args);
    const b = await storeMomentUpload(pool, ownerB, bytes, args);
    assert.ok(a.moment?.id);
    assert.ok(b.moment?.id);
    assert.notEqual(a.moment.id, b.moment.id);
    assert.equal(assets, 2);
  } finally {await rm(dir, {recursive: true, force: true});}
});
