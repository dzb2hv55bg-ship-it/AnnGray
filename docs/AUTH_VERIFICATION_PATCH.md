# MOMENTO: OTP UI and live authentication verification

Source: user-provided `MOMENTO-0.54.3-fixed-sources.zip` (not an invented new application).

Changes: email/phone format checks, friendly transport/HTTP errors, resend cooldown, explicit live-API Gradle gate (`-PmomentoRequireLiveAuth=true`) and CI gate using repository variable `MOMENTO_API_URL`.

A valid HTTPS hostname is a *build-time syntactic requirement*, not proof of reachable service, email delivery, or SMS delivery. Real verification requires a deployed backend, database, `EMAIL_DELIVERY_URL`/`EMAIL_DELIVERY_API_KEY`, `SMS_DELIVERY_URL`/`SMS_DELIVERY_API_KEY`, and accessible test email/phone. Configure provider secrets on the server, never in the APK or repository. GitHub repository variable `MOMENTO_API_URL` is a public base URL (not a secret).

Auth endpoints: `POST /v1/auth/otp/request` with `{ "contactType": "EMAIL|PHONE", "value": "..." }`; `POST /v1/auth/otp/verify` with `{ "challengeId": "...", "code": "..." }`. Confirm session persistence on an actual device. OAuth credentials are configured separately.

The supplied `IMG_1488.jpeg` depicts the current, defective auth screen. The available approved mockups show main/camera/rooms/vlog screens; exact approved mockups of OTP method selection, contact entry, and code confirmation are **not provided**. This patch does not claim pixel-accurate comparison or produce fabricated emulator screenshots.

The initial build attempt in another environment stopped at the Gradle Wrapper download (`UnknownHostException`). With the cached Gradle distribution and Android SDK, compilation exposed a reference to a nonexistent `error` state in the profile contact-linking form. The reference was replaced with the form's `message` state. The sign-in form now also takes a snapshot of the request inputs and prevents switching contacts during an OTP request. Phone numbers pasted with spaces, parentheses or hyphens are normalized before validation.

Validation after these follow-up fixes:

- `./gradlew --offline assembleDebug :app:testDebugUnitTest`: successful; 10 unit tests passed.
- Backend `npm test`: 94 passed; 2 PostgreSQL integration tests skipped.
- The live-authentication Gradle gate rejected six missing or invalid configurations and accepted a syntactically valid HTTPS URL without contacting it.

The local APK is a signed-out UI preview with `MOMENTO_API_CONFIGURED=false`. Neither device installation nor live OTP delivery was tested. Set `MOMENTO_API_URL` in CI or follow `BUILD_ANDROID.md` to build with the deployed backend, then test delivery and sign-in separately for email and SMS. This is not a design approval or a claim that the screen matches the missing OTP mockups.
