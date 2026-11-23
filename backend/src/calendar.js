// Private life calendar: no room/public grants are consulted.
export function parseCalendarOptions(params, now = new Date()) {
  const period = params.get('period');
  const offsetRaw = params.get('offsetMinutes') ?? '0';
  if (!['week', 'month', 'all'].includes(period) || !/^-?\d{1,4}$/.test(offsetRaw)) return { error: 'INVALID_CALENDAR_OPTIONS' };
  const offsetMinutes = Number(offsetRaw);
  if (!Number.isInteger(offsetMinutes) || offsetMinutes < -840 || offsetMinutes > 840 || !Number.isFinite(now.getTime())) return { error: 'INVALID_CALENDAR_OPTIONS' };
  const localNow = new Date(now.getTime() + offsetMinutes * 60000);
  const localStart = new Date(Date.UTC(localNow.getUTCFullYear(), localNow.getUTCMonth(), localNow.getUTCDate()));
  if (period === 'week') localStart.setUTCDate(localStart.getUTCDate() - 6);
  if (period === 'month') localStart.setUTCDate(1);
  const start = period === 'all' ? null : new Date(localStart.getTime() - offsetMinutes * 60000).toISOString();
  return { period, offsetMinutes, start, end: now.toISOString() };
}
export async function getPrivateCalendar(pool, userId, options) {
  const result = await pool.query(`
    SELECT to_char((captured_at + ($2::int * interval '1 minute')) AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day,
           count(*)::int AS count
    FROM moments
    WHERE owner_id = $1 AND deleted_at IS NULL AND moderation = 'VISIBLE'
      AND ($3::timestamptz IS NULL OR captured_at >= $3::timestamptz)
      AND captured_at <= $4::timestamptz
    GROUP BY day ORDER BY day DESC`, [userId, options.offsetMinutes, options.start, options.end]);
  return { period: options.period, days: result.rows };
}
