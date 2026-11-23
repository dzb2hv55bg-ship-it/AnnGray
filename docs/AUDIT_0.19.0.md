# MOMENTO 0.19.0 — profile API
- PATCH /v1/auth/me updates only authenticated user's display_name.
- Input is NFC-normalized, trimmed, 1–60 Unicode codepoints, control chars rejected.
- Fixed external OIDC legacy login overwriting a user-edited display name.
- No claim of Android build or real OAuth provider tests.
