# MOMENTO 0.34.0 — Offline account-bound archive

- A server-confirmed account UUID is encrypted inside the existing Android Keystore-backed session vault.
- Local archives may be opened offline only if a previously verified account identity is present in the same encrypted session.
- Successful online verification binds the identity; mismatched online identity invalidates the session (fail closed).
- Refresh token rotation preserves the bound identity; a fresh login without an identity resets the binding.
- No automatic background upload is enabled; offline recording and local archive viewing remain distinct from sync.

## Unverified
Android Gradle compilation, physical-device behavior, live PostgreSQL and identity-provider integration.
