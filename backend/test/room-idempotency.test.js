import test from 'node:test';
import assert from 'node:assert/strict';
import {createSpace} from '../src/spaces.js';
const A='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const KEY='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const ROOM='cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const input={name:'My live day',description:'',kind:'CIRCLE',visibility:'PUBLIC',joinPolicy:'OPEN'};
function database() {
 let stored=false, inserts=0, locks=0;
 const query=async(sql,params=[])=>{
  if(sql.includes('pg_advisory_xact_lock')) {locks++;return {rowCount:1,rows:[{}]};}
  if(sql.includes('FROM room_creation_keys')) return {rowCount:stored?1:0,rows:stored?[{id:ROOM,name:input.name}]:[]};
  if(sql.includes('INSERT INTO spaces')) {inserts++;return {rowCount:1,rows:[{id:ROOM,name:input.name}]};}
  if(sql.includes('INSERT INTO room_creation_keys')) {assert.deepEqual(params,[A,KEY,ROOM]);stored=true;}
  return {rowCount:1,rows:[{}]};
 };
 return {pool:{connect:async()=>({query,release(){}})},stats:()=>({stored,inserts,locks})};
}
test('replaying room creation returns same remote room without duplicate inserts',async()=>{
 const db=database();
 const first=await createSpace(db.pool,A,input,KEY);
 const second=await createSpace(db.pool,A,input,KEY);
 assert.equal(first.id,ROOM);assert.equal(second.id,ROOM);
 assert.equal(second.replayed,true);
 assert.deepEqual(db.stats(),{stored:true,inserts:1,locks:2});
});
test('room creation without client key remains supported',async()=>{
 const db=database();const result=await createSpace(db.pool,A,input);
 assert.equal(result.id,ROOM);assert.equal(db.stats().locks,0);
});
