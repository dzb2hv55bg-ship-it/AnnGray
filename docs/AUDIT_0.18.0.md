# MOMENTO 0.18.0 — HTTP JSON media type hardening

- Reject explicitly non-JSON Content-Type on JSON endpoints with HTTP 415.
- Allow application/json with charset parameter.
- Keep headerless in-process test iterables compatible.
- Add two regression tests.
- Android APK, PostgreSQL and live provider authentication are NOT validated by these tests.
- Google Credential Manager reference: https://developer.android.com/identity/credential-manager
- Google backend token validation: https://developers.google.com/identity/sign-in/android/backend-auth
