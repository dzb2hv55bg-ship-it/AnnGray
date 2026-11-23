import { createRemoteJWKSet, jwtVerify } from 'jose';
import { cleanSubject, verifyYandexAccessToken, verifyVkAccessToken } from './provider-http.js';

const GOOGLE_ISSUERS = ['https://accounts.google.com', 'accounts.google.com'];
const GOOGLE_JWKS = createRemoteJWKSet(new URL('https://www.googleapis.com/oauth2/v3/certs'));

export async function verifyGoogleIdToken(idToken, { audience, expectedNonce } = {}) {
  if (!audience) throw new Error('GOOGLE_CLIENT_ID_NOT_CONFIGURED');
  if (typeof idToken !== 'string' || idToken.length > 8192) throw new Error('GOOGLE_ID_TOKEN_INVALID');
  const { payload } = await jwtVerify(idToken, GOOGLE_JWKS, {
    issuer: GOOGLE_ISSUERS,
    audience,
    algorithms: ['RS256'],
  });
  if (typeof expectedNonce !== 'string' || expectedNonce.length < 16 || payload.nonce !== expectedNonce) throw new Error('GOOGLE_NONCE_INVALID');
  return {
    issuer: 'https://accounts.google.com',
    subject: cleanSubject(payload.sub),
    displayName: typeof payload.name === 'string' ? payload.name : 'MOMENTO user',
    email: typeof payload.email === 'string' && payload.email_verified === true ? payload.email : null,
  };
}

export async function resolveProviderIdentity(provider, credentials, options = {}) {
  if (provider === 'google') return verifyGoogleIdToken(credentials.idToken, options);
  if (provider === 'yandex') return verifyYandexAccessToken(credentials.accessToken, options);
  if (provider === 'vk') return verifyVkAccessToken(credentials.accessToken, options);
  throw new Error('AUTH_PROVIDER_UNSUPPORTED');
}
