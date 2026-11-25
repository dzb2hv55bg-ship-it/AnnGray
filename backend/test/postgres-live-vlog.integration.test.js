import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {acceptInvite, changeSpecialRoom, createInvite, createSpace, getRoomTimeline, listSpecialRoomMembers, validateCreateSpace} from '../src/spaces.js';
import {storeMomentUpload} from '../src/media-upload.js';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {attachMoment,authorizedVideoKey,publishRoom,setRoomSubscription,getSubscriptionUpdates,getPublicRoom} from '../src/live-vlogs.js';

// This test MUST run only against a disposable database with all migrations applied.
// CI sets MOMENTO_TEST_DATABASE_URL; it is never pointed at the user's database.
test('PostgreSQL: A publishes a room, B follows, sees new moments, private room stays private',
 {skip:!process.env.MOMENTO_TEST_DATABASE_URL}, async()=>{
 const {Pool}=await import('pg');
 const pool=new Pool({connectionString:process.env.MOMENTO_TEST_DATABASE_URL});
 const a=randomUUID(), b=randomUUID(), outsider=randomUUID(), key=randomUUID();
 try {
  for(const [id,name] of [[a,'A'],[b,'B'],[outsider,'C']])
   await pool.query('INSERT INTO users(id,display_name) VALUES($1,$2)',[id,name]);
  const room=await createSpace(pool,a,{name:'Live vlog',description:'Day 1',kind:'CIRCLE',visibility:'PUBLIC',joinPolicy:'OPEN'},key);
  const replay=await createSpace(pool,a,{name:'Live vlog',description:'Day 1',kind:'CIRCLE',visibility:'PUBLIC',joinPolicy:'OPEN'},key);
  assert.equal(room.id,replay.id);
  const privateRoom=await createSpace(pool,a,{name:'Private diary',description:'',kind:'CIRCLE',visibility:'PRIVATE',joinPolicy:'INVITE_ONLY'},randomUUID());
  assert.equal((await getPublicRoom(pool,outsider,privateRoom.id)).error,'ROOM_UNAVAILABLE');
  const addMoment=async()=>{
   const asset=randomUUID();
   await pool.query(`INSERT INTO media_assets(id,owner_id,storage_key,mime_type,byte_size,duration_ms,status)
     VALUES($1,$2,$3,'video/mp4',100,3000,'READY')`,[asset,a,asset+'.mp4']);
   const result=await pool.query(`INSERT INTO moments(owner_id,media_asset_id,captured_at,duration_ms)
     VALUES($1,$2,now(),3000) RETURNING id`,[a,asset]);
   return result.rows[0].id;
  };
  const m1=await addMoment();
  assert.equal((await attachMoment(pool,a,room.id,m1)).momentId,m1);
  assert.equal((await attachMoment(pool,a,room.id,m1)).momentId,m1);
  assert.ok((await publishRoom(pool,a,room.id)).room);
  assert.deepEqual(await setRoomSubscription(pool,b,room.id,true),{following:true});
  const m2=await addMoment();
  assert.equal((await attachMoment(pool,a,room.id,m2)).momentId,m2);
  assert.equal((await attachMoment(pool,b,room.id,m2)).error,'FORBIDDEN_OR_MISSING');
  const updates=await getSubscriptionUpdates(pool,b);
  assert.ok(updates.items.some(i=>i.moment_id===m2));
  assert.equal((await getPublicRoom(pool,b,room.id)).room.id,room.id);
  assert.equal((await getPublicRoom(pool,b,privateRoom.id)).error,'ROOM_UNAVAILABLE');
  const links=await pool.query('SELECT count(*)::int AS n FROM moment_audiences WHERE moment_id=$1',[m2]);
  assert.equal(links.rows[0].n,1);
 } finally {
  await pool.query('DELETE FROM users WHERE id=ANY($1::uuid[])',[ [a,b,outsider] ]).catch(()=>{});
  await pool.end();
 }
});

// Deliberately synthetic media bytes and a stubbed probe: tests durable PostgreSQL
// idempotency, NOT the integrity or camera origin of an actual video file.
test('PostgreSQL: lost upload response retries to the same moment and rejects changed bytes',
 {skip:!process.env.MOMENTO_TEST_DATABASE_URL}, async()=>{
 const {Pool}=await import('pg');
 const pool=new Pool({connectionString:process.env.MOMENTO_TEST_DATABASE_URL});
 const user=randomUUID(), request=randomUUID();
 const storageDir=await mkdtemp(join(tmpdir(),'momento-upload-ci-'));
 const opts={storageDir,capturedAt:new Date().toISOString(),clientRequestId:request,
  probeFn:async()=>({format:{duration:'3.0'},streams:[{codec_type:'video',codec_name:'h264',width:640,height:480}]})};
 try {
  await pool.query('INSERT INTO users(id,display_name) VALUES($1,$2)',[user,'Upload CI']);
  const first=await storeMomentUpload(pool,user,Buffer.from('synthetic-video-a'),opts);
  assert.ok(first.moment?.id,JSON.stringify(first));
  const retry=await storeMomentUpload(pool,user,Buffer.from('synthetic-video-a'),opts);
  assert.equal(retry.moment?.id,first.moment.id);
  assert.equal(retry.replayed,true);
  const conflict=await storeMomentUpload(pool,user,Buffer.from('synthetic-video-b'),opts);
  assert.equal(conflict.error,'IDEMPOTENCY_CONFLICT');
  const rows=await pool.query('SELECT COUNT(*)::int AS n FROM moments WHERE owner_id=$1',[user]);
  assert.equal(rows.rows[0].n,1);
 } finally {
  await pool.query('DELETE FROM users WHERE id=$1',[user]).catch(()=>{});
  await pool.end();
  await rm(storageDir,{recursive:true,force:true});
 }
});

test('PostgreSQL: simultaneous secret invitations leave one guest and revocation removes access',
 {skip: !process.env.MOMENTO_TEST_DATABASE_URL}, async () => {
  const {Pool} = await import('pg');
  const pool = new Pool({connectionString: process.env.MOMENTO_TEST_DATABASE_URL});
  const users = [randomUUID(), randomUUID(), randomUUID()];
  const [owner, guest, other] = users;
  let room;
  try {
    for (const user of users) {
      await pool.query("INSERT INTO users(id, display_name) VALUES($1, 'CI participant')", [user]);
      await pool.query(`INSERT INTO user_age_profiles(user_id, birth_date, age_band, policy_version)
        VALUES($1, '1990-01-01', 'ADULT', 'test')`, [user]);
    }
    const input = validateCreateSpace({name: 'Secret room', kind: 'SECRET'}).value;
    room = await createSpace(pool, owner, input);
    assert.equal(room.visibility, 'PRIVATE');
    const issue = async () => {
      const invite = await createInvite(pool, owner, room.id, 1, 50, 'https://example.test');
      return invite.inviteUrl.split('/').at(-1);
    };
    const [firstToken, secondToken] = await Promise.all([issue(), issue()]);
    const results = await Promise.all([acceptInvite(pool, guest, firstToken), acceptInvite(pool, other, secondToken)]);
    const winnerIndex = results.findIndex(result => result.joined);
    assert.notEqual(winnerIndex, -1);
    const winner = [guest, other][winnerIndex];
    const loser = [other, guest][winnerIndex];
    const usedToken = [firstToken, secondToken][winnerIndex];
    assert.equal(results[1 - winnerIndex].error, 'INVITE_UNAVAILABLE');
    assert.equal((await listSpecialRoomMembers(pool, winner, room.id)).items.length, 2);
    assert.equal((await acceptInvite(pool, loser, usedToken)).error, 'INVITE_UNAVAILABLE');
    assert.equal((await getRoomTimeline(pool, loser, room.id, null, null, 20)).error, 'FORBIDDEN');
    assert.equal((await changeSpecialRoom(pool, loser, room.id, 'remove', winner)).error, 'ROOM_UNAVAILABLE');
    assert.equal((await changeSpecialRoom(pool, owner, room.id, 'remove', winner)).success, true);
    assert.equal((await getRoomTimeline(pool, winner, room.id, null, null, 20)).error, 'FORBIDDEN');
    assert.equal((await changeSpecialRoom(pool, owner, room.id, 'archive')).success, true);
    assert.equal((await getRoomTimeline(pool, owner, room.id, null, null, 20)).error, 'FORBIDDEN');
  } finally {
    if (room) await pool.query('DELETE FROM spaces WHERE id = $1', [room.id]);
    await pool.query('DELETE FROM users WHERE id = ANY($1::uuid[])', [users]);
    await pool.end();
  }
});

test('event creation defaults to private and validates dates and secret visibility', () => {
  const dates = {eventStartsAt: '2026-11-25T10:00:00+03:00', eventEndsAt: '2026-11-25T08:00:00Z'};
  const event = validateCreateSpace({name: 'Event room', kind: 'EVENT', ...dates}).value;
  assert.equal(event.eventStartsAt, '2026-11-25T07:00:00.000Z');
  assert.equal(event.visibility, 'PRIVATE');
  assert.equal(event.joinPolicy, 'INVITE_ONLY');
  assert.ok(validateCreateSpace({name: 'Event room', kind: 'EVENT', ...dates, eventStartsAt: '2026-02-30T00:00Z'}).error);
  assert.ok(validateCreateSpace({name: 'Event room', kind: 'EVENT', ...dates, eventStartsAt: dates.eventEndsAt}).error);
  assert.ok(validateCreateSpace({name: 'Secret room', kind: 'SECRET', visibility: 'PUBLIC'}).error);
});

test('PostgreSQL: event members contribute chronologically and completion preserves existing history',
 {skip: !process.env.MOMENTO_TEST_DATABASE_URL}, async () => {
  const {Pool} = await import('pg');
  const pool = new Pool({connectionString: process.env.MOMENTO_TEST_DATABASE_URL});
  const users = [randomUUID(), randomUUID(), randomUUID()];
  const [owner, guest, outsider] = users;
  let room;
  try {
    for (const user of users) {
      await pool.query("INSERT INTO users(id, display_name) VALUES($1, 'CI participant')", [user]);
      await pool.query(`INSERT INTO user_age_profiles(user_id, birth_date, age_band, policy_version)
        VALUES($1, '1990-01-01', 'ADULT', 'test')`, [user]);
    }
    const now = Date.now();
    const input = validateCreateSpace({
      name: 'Event room', kind: 'EVENT',
      eventStartsAt: new Date(now - 3_600_000).toISOString(),
      eventEndsAt: new Date(now + 3_600_000).toISOString(),
    }).value;
    room = await createSpace(pool, owner, input);
    const invitation = await createInvite(pool, owner, room.id, 1, 1, 'https://example.test');
    assert.equal((await acceptInvite(pool, guest, invitation.inviteUrl.split('/').at(-1))).joined, true);
    const addMoment = async (author, capturedAt) => {
      const asset = randomUUID();
      await pool.query(`INSERT INTO media_assets(id,owner_id,storage_key,mime_type,byte_size,duration_ms,status)
        VALUES($1,$2,$3,'video/mp4',100,3000,'READY')`, [asset, author, asset + '.mp4']);
      const moment = await pool.query(`INSERT INTO moments(owner_id,media_asset_id,captured_at,duration_ms)
        VALUES($1,$2,$3,3000) RETURNING id`, [author, asset, capturedAt]);
      return moment.rows[0].id;
    };
    const first = await addMoment(owner, new Date(now - 10_000));
    const second = await addMoment(guest, new Date(now - 1_000));
    const third = await addMoment(guest, new Date(now));
    assert.equal((await attachMoment(pool, guest, room.id, second)).momentId, second);
    assert.equal((await attachMoment(pool, owner, room.id, first)).momentId, first);
    assert.equal((await changeSpecialRoom(pool, guest, room.id, 'complete')).error, 'ROOM_UNAVAILABLE');
    assert.equal((await changeSpecialRoom(pool, owner, room.id, 'complete')).success, true);
    assert.equal((await attachMoment(pool, guest, room.id, third)).error, 'FORBIDDEN_OR_MISSING');
    const timeline = await getRoomTimeline(pool, guest, room.id, null, null, 50);
    assert.deepEqual(timeline.items.map(moment => moment.id), [second, first]);
    assert.ok(await authorizedVideoKey(pool, guest, first));
    assert.equal(await authorizedVideoKey(pool, outsider, first), null);
    assert.equal((await getRoomTimeline(pool, outsider, room.id, null, null, 50)).error, 'FORBIDDEN');
    assert.equal((await changeSpecialRoom(pool, guest, room.id, 'remove')).success, true);
    assert.equal(await authorizedVideoKey(pool, guest, first), null);
    assert.ok(await authorizedVideoKey(pool, guest, second));
    assert.equal((await getRoomTimeline(pool, guest, room.id, null, null, 50)).error, 'FORBIDDEN');
  } finally {
    if (room) await pool.query('DELETE FROM spaces WHERE id = $1', [room.id]);
    await pool.query('DELETE FROM moments WHERE owner_id = ANY($1::uuid[])', [users]);
    await pool.query('DELETE FROM media_assets WHERE owner_id = ANY($1::uuid[])', [users]);
    await pool.query('DELETE FROM users WHERE id = ANY($1::uuid[])', [users]);
    await pool.end();
  }
});
