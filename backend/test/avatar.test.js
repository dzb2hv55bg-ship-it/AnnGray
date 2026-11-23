import test from 'node:test';
import assert from 'node:assert/strict';
import { validateAvatarRequest, updateAvatar } from '../src/avatar.js';
const owner = 'bbf829d5-329e-4db7-a840-6df728af1234';
const media = 'd437c4ba-95bc-41fd-9252-fcb198a51234';
test('avatar accepts only UUID or explicit null', () => {
  assert.deepEqual(validateAvatarRequest({mediaAssetId: media}), {mediaAssetId: media});
  assert.deepEqual(validateAvatarRequest({mediaAssetId: null}), {mediaAssetId: null});
  for (const body of [{}, [], null, {mediaAssetId:'https://example.com/evil.mp4'}, {mediaAssetId:5}]) {
    assert.equal(validateAvatarRequest(body).error, 'INVALID_AVATAR_MEDIA');
  }
});
test('avatar change checks ownership, readiness, duration, storage and deletion atomically', async () => {
  let captured;
  const pool = {query: async (sql,args) => { captured={sql,args}; return {rowCount:1,rows:[{id:owner,avatar_media_id:media}]}; }};
  const result=await updateAvatar(pool,owner,{mediaAssetId:media});
  assert.equal(result.user.avatar_media_id,media);
  assert.deepEqual(captured.args,[owner,media]);
  for (const phrase of ['a.owner_id = u.id', "a.status = 'READY'", 'a.deleted_at IS NULL', 'a.duration_ms BETWEEN 2000 AND 5000', 'a.storage_key IS NOT NULL', 'u.deleted_at IS NULL']) assert.ok(captured.sql.includes(phrase),phrase);
});
test('avatar may be cleared with null', async () => {
  const pool={query:async (_sql,args)=>({rowCount:1,rows:[{id:args[0],avatar_media_id:args[1]}]})};
  assert.equal((await updateAvatar(pool,owner,{mediaAssetId:null})).user.avatar_media_id,null);
});
test('unavailable asset does not leak ownership or existence', async () => {
  const pool={query:async()=>({rowCount:0,rows:[]})};
  assert.equal((await updateAvatar(pool,owner,{mediaAssetId:media})).error,'AVATAR_UNAVAILABLE');
});
test('malformed avatar never queries database', async () => {
  const pool={query:async()=>{throw Error('unexpected query')}};
  assert.equal((await updateAvatar(pool,owner,{mediaAssetId:'bad'})).error,'INVALID_AVATAR_MEDIA');
});
