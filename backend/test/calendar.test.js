import test from 'node:test';
import assert from 'node:assert/strict';
import { parseCalendarOptions, getPrivateCalendar } from '../src/calendar.js';
const now = new Date('2026-11-21T23:30:00Z');
const opts = (q) => parseCalendarOptions(new URLSearchParams(q), now);
test('calendar requires explicit period', () => assert.equal(opts('offsetMinutes=180').error, 'INVALID_CALENDAR_OPTIONS'));
test('calendar week uses seven local calendar dates', () => assert.equal(opts('period=week&offsetMinutes=180').start, '2026-11-15T21:00:00.000Z'));
test('calendar month starts on local first day', () => assert.equal(opts('period=month&offsetMinutes=180').start, '2026-10-31T21:00:00.000Z'));
test('calendar all does not restrict start', () => assert.equal(opts('period=all').start, null));
test('calendar rejects invalid offsets', () => { for(const q of ['period=week&offsetMinutes=900','period=week&offsetMinutes=abc','period=year']) assert.equal(opts(q).error, 'INVALID_CALENDAR_OPTIONS'); });
test('calendar query is user-scoped and parameterized', async () => {
  let captured;
  const db = { query: async (...args) => { captured = args; return {rows:[{day:'2026-11-21',count:2}]}; } };
  const result = await getPrivateCalendar(db, 'user-1', opts('period=all'));
  assert.equal(result.days[0].count,2);
  assert.match(captured[0], /owner_id = \$1/);
  assert.equal(captured[1][0], 'user-1');
});
