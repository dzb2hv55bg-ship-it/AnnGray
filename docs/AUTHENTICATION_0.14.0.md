# MOMENTO authentication — 0.14.0

## Security correction
- OTP challenges are bound to their intended endpoint purpose (`SIGN_IN` or `LINK_CONTACT`).
- The purpose is checked immediately after row locking and before consuming the OTP, creating a user, or linking a contact.
- Previously, a valid sign-in challenge submitted to the link-verification endpoint could trigger unintended account creation before the endpoint rejected the purpose mismatch.
- Added positive and negative unit tests for the purpose guard.

## Remaining blockers
- Android SDK/Gradle APK build and device login are not verified.
- Live PostgreSQL migration/integration, provider credentials, email/SMS adapters, and provider sign-in require external infrastructure.
- VK client-secret distribution needs a release security review.
