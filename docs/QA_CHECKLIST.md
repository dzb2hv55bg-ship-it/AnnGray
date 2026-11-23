# MOMENTO MVP QA checklist

## Moment capture
- [ ] Recording stops at or before 5,000 ms even if UI timer is interrupted.
- [ ] Recorded file metadata duration is verified; server rejects duration > 5,000 ms.
- [ ] Front/rear camera, rotation, permission denial and camera-in-use are handled.
- [ ] Low storage and app process death do not produce a falsely successful moment.
- [ ] User can preview, discard or save a recorded clip before sharing.

## Offline and upload
- [ ] No network: save file locally and mark it queued; never lose the only copy.
- [ ] Network returns: queue resumes without user reopening the app.
- [ ] Wi-Fi/mobile switch, metered network and signed URL expiry are handled.
- [ ] Retry and repeated completion requests do not create duplicate media/moments.
- [ ] Checksum and byte size are verified before media becomes READY.
- [ ] Failed uploads remain retryable and user can remove them explicitly.

## Rooms and access
- [ ] Private room is accessible only to members.
- [ ] Invite expiry, revocation and use limit are enforced server-side.
- [ ] Public room does not make any private archive content public.
- [ ] A moment shared to two rooms has one media object and two audience grants.
- [ ] Removed member loses playback access after existing signed URL expiry.
- [ ] Blocked users cannot interact through comments/invites where policy forbids it.

## Privacy and moderation
- [ ] New moment is private unless the owner explicitly chooses room(s).
- [ ] Playback URL is issued only after authorization check and has short expiry.
- [ ] Reports, blocks, room owner/admin/moderator actions are auditable.
- [ ] Age policy is enforced by API on room discovery, joining, comments and playback.
- [ ] User deletion triggers defined retention and media deletion workflow.

## Camera and local archive — first implementation pass
- [ ] Grant camera permission on first entry to the capture screen.
- [ ] Deny camera permission; app must remain stable and explain why capture is unavailable.
- [ ] Verify rear camera preview starts on low-, mid- and high-range devices.
- [ ] Record; confirm automatic stop at or before 5 seconds (measure output duration, not only UI timer).
- [ ] Verify saved MP4 is non-empty and playable.
- [ ] Leave capture screen during recording; verify recording finalizes safely and no camera resource leaks remain.
- [ ] Force-stop/relaunch app; verify previously saved clips are rediscovered from app-private storage.
- [ ] Test low storage, camera busy, interrupted lifecycle, rotation/orientation and repeated recordings.
- [ ] Confirm no microphone permission is requested in this silent-video prototype.

## Backend 0.7.0 checks
- [ ] If the optional legacy OIDC bridge is enabled, configure its issuer/audience/JWKS and verify expired, wrong-issuer, wrong-audience and invalid-signature JWTs are rejected.
- [ ] Apply migration to a disposable PostgreSQL 17 database and verify `/health`.
- [ ] Verify room creation and owner membership are atomic.
- [ ] Verify only room owners/admins/moderators can issue invitations.
- [ ] Verify only room owners/admins can revoke invitations; ordinary members and moderators are denied.
- [ ] Verify archived rooms cannot issue or accept invitations.
- [ ] Verify repeat acceptance by an existing member does not increment invite use count and returns `alreadyMember: true`.
- [ ] Verify invite token hash only is stored; raw token never appears in logs/database.
- [ ] Verify expired, revoked, exhausted and malformed invites are rejected under concurrent requests.
- [ ] Verify missing age profiles and incompatible age bands fail closed; replace placeholder with reviewed age policy before launch.
- [ ] Verify both directions of user blocking prevent joining.
- [ ] Verify non-members cannot query room timelines.
- [ ] Verify timeline excludes deleted, hidden, quarantined and non-ready media.
- [ ] Add rate limiting, idempotency persistence, audit logging and abuse monitoring before public deployment.

## Android API transport 0.9.0 checks
- [ ] Verify `getSpaceTimeline` sends the authenticated request only over HTTPS.
- [ ] Verify page limits outside 1–50 are rejected before the request is sent.
- [ ] Verify the first page omits cursor parameters and later pages send `before` and `beforeId` together.
- [ ] Verify timestamp query encoding preserves fractional seconds and timezone offsets.
- [ ] Verify malformed room IDs and cursor UUIDs are rejected locally.
- [ ] Verify a null `nextCursor` is represented as the end of the timeline.
- [ ] Verify server-side membership checks reject non-members even if they call the endpoint directly.


## Authentication 0.13.0 — required before production
- [ ] Apply migrations 001–004 to a clean PostgreSQL test database.
- [ ] Verify Google ID token signature, issuer, audience, expiry, matching one-time nonce and replay rejection.
- [ ] Verify Yandex access tokens through Yandex ID user-info and reject mismatched `client_id`.
- [ ] Verify VK ID access tokens through VK ID user-info and reject tokens issued for another app.
- [ ] Verify email and phone normalization, five-minute OTP expiry, 60-second resend cooldown, five sends/hour and five verification attempts.
- [ ] Verify OTP is never stored or logged in plaintext; contact values are AES-256-GCM encrypted and keyed hashes are used for lookup.
- [ ] Verify sign-in for unknown and existing email/phone contacts returns the same response shape and does not reveal account existence.
- [ ] Verify email and phone can be linked to the same MOMENTO user only from an active session after OTP confirmation.
- [ ] Verify Google/Yandex/VK identity linking rejects identities already attached to another MOMENTO user.
- [ ] Verify matching email claims from different social providers do not auto-merge accounts.
- [ ] Verify access token expiry, refresh rotation, replay-family revocation and logout.
- [ ] Verify Android Keystore token encryption, app restart, refresh, logout and account switching.
- [ ] Verify provider login flows on physical Android devices with real test apps and signing fingerprints.
- [ ] Verify OTP delivery with selected email and SMS vendors; add IP/device-level abuse throttling and monitoring before public release.


### Authentication 0.13.0 additional regression checks
- [ ] Run concurrent OTP requests for the same normalized email/phone; only one challenge reservation may pass the 60-second cooldown.
- [ ] Verify the per-contact five-per-hour cap remains correct under concurrent requests.
- [ ] Simulate provider HTTP 429 and 5xx responses; client must receive a temporary-unavailable response, not an invalid-credentials response.
- [ ] Keep the app open beyond 15 minutes and verify MOMENTO refresh runs before access-token expiry.
- [ ] Simulate refresh network/5xx failure; the app must retain the encrypted session and retry rather than logging the user out.
- [ ] Simulate refresh HTTP 401; the local session must be cleared.
