import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {createSpace} from '../src/spaces.js';
import {storeMomentUpload} from '../src/media-upload.js';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {attachMoment,publishRoom,setRoomSubscription,getSubscriptionUpdates,getPublicRoom} from '../src/live-vlogs.js';

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
