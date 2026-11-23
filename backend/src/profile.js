// Minimal profile editing: no age or privacy policy changes through this endpoint.
export function validateDisplayName(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body) ||
      typeof body.displayName !== 'string') return { error: 'INVALID_DISPLAY_NAME' };
  const name = body.displayName.trim().normalize('NFC');
  if (!name || [...name].length > 60 || /[\\u0000-\\u001f\\u007f]/u.test(name)) {
    return { error: 'INVALID_DISPLAY_NAME' };
  }
  return { value: name };
}
