import test from 'node:test';
import assert from 'node:assert/strict';
import { readJsonObject } from '../src/request-json.js';
async function *body(...parts) { for (const part of parts) yield Buffer.from(part); }
test('JSON request accepts an object split across chunks', async () => {
  assert.deepEqual(await readJsonObject(body('{"contact', 'Type":"EMAIL"}')), {contactType:'EMAIL'});
});
test('empty JSON body defaults to an object', async () => {
  assert.deepEqual(await readJsonObject(body()), {});
});
test('rejects null, arrays, strings and numbers as request bodies', async () => {
  for (const input of ['null', '[]', '"text"', '42']) {
    await assert.rejects(readJsonObject(body(input)), e => e.status === 400 && e.message === 'JSON_OBJECT_REQUIRED');
  }
});
test('rejects invalid JSON with HTTP 400', async () => {
  await assert.rejects(readJsonObject(body('{')), e => e.status === 400 && e.message === 'INVALID_JSON');
});
test('rejects oversized request before parsing with HTTP 413', async () => {
  await assert.rejects(readJsonObject(body('1234','5678'), 7), e => e.status === 413);
});

test('rejects malformed UTF-8 rather than silently replacing bytes', async () => {
  await assert.rejects(readJsonObject(body(Buffer.from([0x7b,0x22,0x78,0x22,0x3a,0x22,0xc3,0x28,0x22,0x7d]))),
    e => e.status === 400 && e.message === 'INVALID_JSON');
});

test('rejects non-JSON media types with HTTP 415', async () => {
  const req = body('{"x":1}');
  req.headers = {'content-type':'text/plain'};
  await assert.rejects(readJsonObject(req), e => e.status === 415 && e.message === 'UNSUPPORTED_MEDIA_TYPE');
});
test('accepts JSON media type with charset parameter', async () => {
  const req = body('{"x":1}');
  req.headers = {'content-type':'application/json; charset=utf-8'};
  assert.deepEqual(await readJsonObject(req), {x:1});
});
