import test from 'node:test';
import assert from 'node:assert/strict';
import {calendarRange,validateVideoMoment,validateCapsuleRelease,validateRoomFilmSelection} from '../src/memory-features.js';
test('calendar all includes all history',()=>assert.equal(calendarRange('all',new Date('2026-11-21T00:00:00Z')).start,null));
test('calendar week uses seven UTC days',()=>assert.equal(calendarRange('week',new Date('2026-11-21T00:00:00Z')).start,'2026-11-14T00:00:00.000Z'));
test('calendar month uses calendar months',()=>assert.equal(calendarRange('month',new Date('2026-03-15T00:00:00Z')).start,'2026-02-15T00:00:00.000Z'));
test('unknown period rejected',()=>assert.throws(()=>calendarRange('yesterday'),/INVALID_PERIOD/));
test('video length is two to five seconds',()=>{assert.equal(validateVideoMoment(1999),false);assert.equal(validateVideoMoment(2000),true);assert.equal(validateVideoMoment(5000),true);assert.equal(validateVideoMoment(5001),false)});
test('capsule requires at least one calendar year',()=>{assert.equal(validateCapsuleRelease('2026-01-01','2026-12-31'),false);assert.equal(validateCapsuleRelease('2026-01-01','2027-01-01'),true)});
test('film requires explicit consent and no duplicates',()=>{const a='123e4567-e89b-12d3-a456-426614174000',b='123e4567-e89b-12d3-a456-426614174001';assert.equal(validateRoomFilmSelection([{momentId:a,consent:true,durationMs:2000},{momentId:b,consent:true,durationMs:5000}]),true);assert.equal(validateRoomFilmSelection([{momentId:a,consent:false,durationMs:2000},{momentId:b,consent:true,durationMs:5000}]),false);assert.equal(validateRoomFilmSelection([{momentId:a,consent:true,durationMs:2000},{momentId:a,consent:true,durationMs:5000}]),false)});

test('month range clamps at short months',()=>assert.equal(calendarRange('month',new Date('2026-03-31T00:00:00Z')).start,'2026-02-28T00:00:00.000Z'));
