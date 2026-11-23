import test from 'node:test';
import assert from 'node:assert/strict';
import {isUuid,isVisibleRoom,publishRoom,attachMoment,setRoomSubscription,setAuthorSubscription,getSubscriptions,getSubscriptionUpdates,getPublicRoom} from '../src/live-vlogs.js';
const A='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const B='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const ROOM='cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const MOMENT='dddddddd-dddd-4ddd-8ddd-dddddddddddd';
const result=(rows=[])=>({rows,rowCount:rows.length});
test('UUID path and ownership checks reject malformed and private public access',()=>{
 assert.equal(isUuid('../etc/passwd'),false);assert.equal(isUuid(ROOM),true);
 assert.equal(isVisibleRoom({owner_id:A,viewer_id:B,visibility:'PRIVATE',published_at:null}),false);
 assert.equal(isVisibleRoom({owner_id:A,viewer_id:B,visibility:'PUBLIC',published_at:null}),false);
 assert.equal(isVisibleRoom({owner_id:A,viewer_id:B,visibility:'PUBLIC',published_at:new Date()}),true);
});
test('publishing requires owned public room containing ready video',async()=>{
 let sql='';const db={query:async(q,params)=>{sql=q;if(q.includes('UPDATE spaces')) {assert.deepEqual(params,[ROOM,A]);return result([{id:ROOM,published_at:'now'}]);}assert.deepEqual(params,[ROOM]);return result();}};
 assert.deepEqual(await publishRoom(db,A,ROOM),{room:{id:ROOM,published_at:'now'}});
 assert.match(sql,/room_updates/);
 assert.deepEqual(await publishRoom({query:async()=>result()},A,ROOM),{error:'ROOM_NOT_PUBLISHABLE'});
});
test('attach is transactional, owner-bound, idempotent and emits public update',async()=>{
 const queries=[];const client={query:async(q)=>{queries.push(q);return q.includes('SELECT s.id')?result([{visibility:'PUBLIC',published_at:'now'}]):result();},release(){}};
 const db={connect:async()=>client};
 assert.deepEqual(await attachMoment(db,A,ROOM,MOMENT),{spaceId:ROOM,momentId:MOMENT});
 assert.ok(queries.some(q=>q.includes('ON CONFLICT DO NOTHING')));
 assert.ok(queries.some(q=>q.includes('room_updates')));
 assert.equal(queries.at(-1),'COMMIT');
});
test('private room moment cannot be attached by another account',async()=>{
 const queries=[];const client={query:async(q)=>{queries.push(q);return result();},release(){}};
 assert.deepEqual(await attachMoment({connect:async()=>client},B,ROOM,MOMENT),{error:'FORBIDDEN_OR_MISSING'});
 assert.equal(queries.at(-1),'ROLLBACK');
});
test('follow and unfollow room/author are idempotent and access-scoped',async()=>{
 const sql=[];const db={query:async(q)=>{sql.push(q);return q.includes('RETURNING')?result([{id:ROOM}]):result();}};
 assert.deepEqual(await setRoomSubscription(db,B,ROOM,true),{following:true});
 assert.deepEqual(await setRoomSubscription(db,B,ROOM,false),{following:false});
 assert.deepEqual(await setAuthorSubscription(db,B,A,true),{following:true});
 assert.deepEqual(await setAuthorSubscription(db,B,A,false),{following:false});
 assert.deepEqual(await setAuthorSubscription(db,A,A,true),{error:'SELF_SUBSCRIPTION'});
 assert.ok(sql.some(q=>q.includes('user_blocks')));
});
test('subscriber feeds and room details scope reads to viewer and publication',async()=>{
 const sql=[];const db={query:async(q,params)=>{sql.push(q);assert.equal(params[0],B);return result();}};
 assert.deepEqual(await getSubscriptions(db,B),{rooms:[],authors:[]});
 assert.deepEqual(await getSubscriptionUpdates(db,B),{items:[]});
 assert.deepEqual(await getPublicRoom(db,B,ROOM),{error:'ROOM_UNAVAILABLE'});
 assert.ok(sql.some(q=>q.includes('published_at IS NOT NULL')));
});
