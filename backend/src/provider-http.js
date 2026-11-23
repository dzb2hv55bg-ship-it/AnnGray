async function fetchJson(url, options = {}, fetchImpl = fetch) {
  const response = await fetchImpl(url, { ...options, signal: AbortSignal.timeout(8000) });
  if (!response.ok) throw new Error(`IDENTITY_PROVIDER_HTTP_${response.status}`);
  const body = await response.json();
  if (!body || typeof body !== 'object') throw new Error('IDENTITY_PROVIDER_BAD_RESPONSE');
  return body;
}

export function cleanSubject(value) {
  if (typeof value !== 'string' && typeof value !== 'number') throw new Error('IDENTITY_SUBJECT_MISSING');
  const subject = String(value);
  if (!subject || subject.length > 255) throw new Error('IDENTITY_SUBJECT_INVALID');
  return subject;
}

export async function verifyYandexAccessToken(accessToken, { fetchImpl = fetch } = {}) {
  if (typeof accessToken !== 'string' || accessToken.length < 20 || accessToken.length > 8192) {
    throw new Error('YANDEX_ACCESS_TOKEN_INVALID');
  }
  if (!process.env.YANDEX_CLIENT_ID) throw new Error('YANDEX_CLIENT_ID_NOT_CONFIGURED');
  const profile = await fetchJson('https://login.yandex.ru/info?format=json', {
    headers: { Authorization: `OAuth ${accessToken}`, Accept: 'application/json' },
  }, fetchImpl);
  if (profile.client_id !== process.env.YANDEX_CLIENT_ID) {
    throw new Error('YANDEX_CLIENT_ID_MISMATCH');
  }
  const subject = cleanSubject(profile.id ?? profile.psuid);
  const displayName = [profile.first_name, profile.last_name].filter(Boolean).join(' ').trim();
  return {
    issuer: 'https://login.yandex.ru',
    subject,
    displayName: displayName || (typeof profile.login === 'string' ? profile.login : 'MOMENTO user'),
    email: typeof profile.default_email === 'string' ? profile.default_email : null,
  };
}

export async function verifyVkAccessToken(accessToken, { fetchImpl = fetch } = {}) {
  if (typeof accessToken !== 'string' || accessToken.length < 20 || accessToken.length > 8192) {
    throw new Error('VK_ACCESS_TOKEN_INVALID');
  }
  const clientId = process.env.VK_CLIENT_ID;
  if (!clientId) throw new Error('VK_CLIENT_ID_NOT_CONFIGURED');
  const profile = await fetchJson(`https://id.vk.ru/oauth2/user_info?client_id=${encodeURIComponent(clientId)}`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
    body: new URLSearchParams({ access_token: accessToken }),
  }, fetchImpl);
  const user = profile.user ?? profile;
  const subject = cleanSubject(user.user_id ?? user.id);
  const displayName = [user.first_name, user.last_name].filter(Boolean).join(' ').trim();
  return {
    issuer: 'https://id.vk.ru',
    subject,
    displayName: displayName || 'MOMENTO user',
    email: typeof user.email === 'string' ? user.email : null,
  };
}

