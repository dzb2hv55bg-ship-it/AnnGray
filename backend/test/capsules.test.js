import test from 'node:test';
import assert from 'node:assert/strict';
import { validateCapsuleRequest, createCapsule, listCapsules, cancelCapsule } from '../src/capsules.js';
const id = '123e4567-e89b-12d3-a456-426614174000';
const now = new Date('2026-11-21T12:00:00.000Z');
test('valid one-year capsule', () => assert.equal(validateCapsuleRequest({momentId:id,releaseAt:'2027-11-21T12:00:00.000Z'},now).error,undefined));
test('rejects early release', () => assert.equal(validateCapsuleRequest({momentId:id,releaseAt:'2027-11-20T12:00:00.000Z'},now).error,'INVALID_RELEASE_DATE'));
test('rejects non-UUID identifiers', () => assert.equal(validateCapsuleRequest({momentId:'bad',releaseAt:'2027-11-21T12:00:00Z'},now).error,'INVALID_CAPSULE'));
test('rejects timezone-free date', () => assert.equal(validateCapsuleRequest({momentId:id,releaseAt:'2027-11-21'},now).error,'INVALID_CAPSULE'));
test('rejects invalid dates', () => assert.equal(validateCapsuleRequest({momentId:id,releaseAt:'not-a-date'},now).error,'INVALID_CAPSULE'));
test('create capsule uses owner and duration filters', async () => { let query; const pool={query:async(sql,args)=>{query={sql,args};return {rowCount:1,rows:[{id}]}}};const res=await createCapsule(pool,id,{momentId:id,releaseAt:'2027-11-21T12:00:00Z'},now);assert.ok(res.capsule);assert.match(query.sql,/m.owner_id = \$1/);assert.match(query.sql,/duration_ms BETWEEN 2000 AND 5000/);assert.match(query.sql,/now\(\) \+ interval '1 year'/); });
test('duplicate or unavailable moment rejected without disclosure', async () => {const pool={query:async()=>({rowCount:0,rows:[]})};assert.equal((await createCapsule(pool,id,{momentId:id,releaseAt:'2027-11-21T12:00:00Z'},now)).error,'MOMENT_UNAVAILABLE_OR_CAPSULE_EXISTS');});
test('list only unlocks media after release and is owner-scoped', async()=>{let sql;const pool={query:async(s,args)=>{sql=s;assert.deepEqual(args,[id]);return {rows:[]}}};assert.deepEqual(await listCapsules(pool,id),{items:[]});assert.match(sql,/CASE WHEN c.release_at <= now\(\) THEN c.moment_id ELSE NULL END/);assert.match(sql,/c.owner_id = \$1/);});

test('cancel only affects an owned locked capsule', async () => {
  let captured;
  const pool = {query: async (sql, args) => {
    captured = {sql, args};
    return {rowCount: 1, rows: [{id, cancelled_at: now.toISOString()}]};
  }};
  const result = await cancelCapsule(pool, id, id);
  assert.equal(result.capsule.id, id);
  assert.deepEqual(captured.args, [id,id]);
  assert.match(captured.sql, /owner_id = \$2/);
  assert.match(captured.sql, /release_at > now\(\)/);
  assert.match(captured.sql, /cancelled_at IS NULL/);
});
test('cancel rejects malformed identifier without querying database', async () => {
  const pool = {query: () => {throw Error('must not query');}};
  assert.equal((await cancelCapsule(pool, id, 'invalid')).error, 'INVALID_CAPSULE_ID');
});
test('cancel does not disclose non-owner or already unlocked capsules', async () => {
  const pool = {query: async () => ({rowCount:0, rows:[]})};
  assert.equal((await cancelCapsule(pool, id, id)).error, 'CAPSULE_NOT_FOUND_OR_LOCKED');
});
