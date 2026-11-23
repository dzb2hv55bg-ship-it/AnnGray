// Pure validation/planning utilities; these do not expose media or grant access.
export const CALENDAR_PERIODS = Object.freeze(['week', 'month', 'all']);
export function calendarRange(period, now = new Date()) {
  if (!CALENDAR_PERIODS.includes(period) || !(now instanceof Date) || !Number.isFinite(now.getTime())) throw new RangeError('INVALID_PERIOD');
  const end = new Date(now);
  if (period === 'all') return { start: null, end: end.toISOString() };
  const start = new Date(end);
  if (period === 'week') start.setUTCDate(start.getUTCDate() - 7);
  else {
    const day = start.getUTCDate();
    start.setUTCDate(1);
    start.setUTCMonth(start.getUTCMonth() - 1);
    const lastDay = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + 1, 0)).getUTCDate();
    start.setUTCDate(Math.min(day, lastDay));
  }
  return { start: start.toISOString(), end: end.toISOString() };
}
export function validateVideoMoment(durationMs) {
  return Number.isInteger(durationMs) && durationMs >= 2000 && durationMs <= 5000;
}
export function validateCapsuleRelease(createdAt, releaseAt) {
  const created = new Date(createdAt), release = new Date(releaseAt);
  if (!Number.isFinite(created.getTime()) || !Number.isFinite(release.getTime())) return false;
  const min = new Date(created);
  min.setUTCFullYear(min.getUTCFullYear() + 1);
  return release.getTime() >= min.getTime();
}
export function validateRoomFilmSelection(clips) {
  return Array.isArray(clips) && clips.length >= 2 && clips.length <= 100 &&
    clips.every(c => c && typeof c.momentId === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(c.momentId) && c.consent === true && validateVideoMoment(c.durationMs)) &&
    new Set(clips.map(c => c.momentId)).size === clips.length;
}
