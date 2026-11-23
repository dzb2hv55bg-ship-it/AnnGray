/** Strict JSON object body parser; rejects arrays, null, scalars and oversized bodies. */
export async function readJsonObject(req, maxBytes = 32768) {
  // A supplied Content-Type must identify JSON; reject form/text bodies before parsing.
  // Headerless async iterables remain supported for internal callers and unit tests.
  const contentType = req.headers?.['content-type'];
  if (contentType !== undefined && String(contentType).split(';', 1)[0].trim().toLowerCase() !== 'application/json') {
    throw Object.assign(new Error('UNSUPPORTED_MEDIA_TYPE'), { status: 415 });
  }
  const chunks = [];
  let bytes = 0;
  for await (const chunk of req) {
    const data = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    bytes += data.length;
    if (bytes > maxBytes) throw Object.assign(new Error('REQUEST_BODY_TOO_LARGE'), { status: 413 });
    chunks.push(data);
  }
  if (bytes === 0) return {};
  let value;
  try { value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks))); }
  catch { throw Object.assign(new Error('INVALID_JSON'), { status: 400 }); }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw Object.assign(new Error('JSON_OBJECT_REQUIRED'), { status: 400 });
  }
  return value;
}
