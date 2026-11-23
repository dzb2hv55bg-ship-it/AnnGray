#!/usr/bin/env python3
"""Offline structural checks for the MOMENTO Android source tree."""
from pathlib import Path
import sqlite3
import subprocess
import tomllib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
with (ROOT / "gradle/libs.versions.toml").open("rb") as stream:
    tomllib.load(stream)

camera = (ROOT / "app/src/main/java/ru/momento/app/ui/CameraScreen.kt").read_text()
database = (ROOT / "app/src/main/java/ru/momento/app/data/LocalMomentDatabase.kt").read_text()
spaces = (ROOT / "app/src/main/java/ru/momento/app/data/LocalSpaceDatabase.kt").read_text()
ui = (ROOT / "app/src/main/java/ru/momento/app/ui/MomentoApp.kt").read_text()
assert "LocalMomentDatabase.readCaptureDuration(output)" in camera
assert "durationMillis in MIN_DURATION_MS..MAX_DURATION_MS" in database
assert "CREATE TABLE moments" in database
assert "CREATE TABLE spaces" in spaces
assert "CHECK(length(name) BETWEEN 1 AND 60)" in spaces
assert "CreateRoomScreen" in ui and "LiveHomeScreen" in ui
assert "Tab.SPACES" in ui and "Tab.SUBSCRIPTIONS" in ui and "Tab.RECORD" in ui
api_client = (ROOT / "app/src/main/java/ru/momento/app/network/MomentoApiClient.kt").read_text()
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
gradle = (ROOT / "app/build.gradle.kts").read_text()
assert "INTERNET" in manifest and "MOMENTO_API_BASE_URL" in gradle
assert "startsWith(\"https://\")" in api_client
assert "createPrivateCircle" in api_client and "createInvite" in api_client and "acceptInvite" in api_client
assert "discoverSpaces" in api_client and "joinPublicSpace" in api_client and "reviewJoinRequest" in api_client
assert "getSpaceTimeline" in api_client and "TimelineCursor" in api_client
assert "beforeId" in api_client and "durationMs = item.getInt(\"duration_ms\")" in api_client
assert "discoverSpaces" in api_client and "joinPublicSpace" in api_client and "reviewJoinRequest" in api_client

# Backend source-level checks and dependency-free Node unit tests.
backend = ROOT / "backend"
subprocess.run(["node", "--check", "src/server.js"], cwd=backend, check=True)
subprocess.run(["node", "--check", "src/security.js"], cwd=backend, check=True)
subprocess.run(["node", "--check", "src/auth-core.js"], cwd=backend, check=True)
subprocess.run(["node", "--check", "src/auth-otp.js"], cwd=backend, check=True)
subprocess.run(["node", "--check", "src/provider-auth.js"], cwd=backend, check=True)
subprocess.run(["node", "--check", "src/provider-http.js"], cwd=backend, check=True)
subprocess.run(["node", "--check", "src/spaces.js"], cwd=backend, check=True)
subprocess.run(["node", "--test"], cwd=backend, check=True)
security = (backend / "src/security.js").read_text()
spaces_api = (backend / "src/spaces.js").read_text()
server = (backend / "src/server.js").read_text()
assert "randomBytes(32)" in security and "createHash('sha256')" in security
assert "FOR UPDATE OF i" in spaces_api
assert "AGE_PROFILE_REQUIRED" in spaces_api
assert "LEFT JOIN user_age_profiles" in spaces_api
assert "auth_identities" in server and "pg_advisory_xact_lock" in server
assert "beforeId" in server and "validateTimelineCursor" in spaces_api
assert "revokeInvite" in server and "DELETE" in server and "alreadyMember" in spaces_api
assert "discoverSpaces" in server and "joinPublicSpace" in server and "reviewJoinRequest" in server
assert "s.visibility = 'PUBLIC'" in spaces_api and "AGE_PROFILE_REQUIRED" in spaces_api
assert "captured_at::text AS captured_at" in spaces_api
assert "createRemoteJWKSet" in server and "jwtVerify" in server
auth_core = (backend / "src/auth-core.js").read_text()
auth_migration = (backend / "db/migrations/002_auth_channels.sql").read_text()
assert all(provider in auth_core for provider in ["yandex", "google", "vk", "email", "phone"])
assert "normalizeEmail" in auth_core and "normalizePhone" in auth_core
assert "timingSafeEqual" in auth_core and "createHmac" in auth_core
assert "CREATE TABLE auth_challenges" in auth_migration
assert "CREATE TABLE auth_sessions" in auth_migration
assert "CREATE TABLE auth_link_attempts" in auth_migration
assert "CREATE TABLE auth_provider_nonces" in (backend / "db/migrations/004_auth_provider_nonce.sql").read_text()
assert "createAccessToken" in auth_core and "verifyAccessToken" in auth_core
assert "providerMatch" in server and "linkProviderMatch" in server
assert "google/nonce" in server and "consumeGoogleNonce" in server
assert "/v1/auth/link/otp/request" in server and "/v1/auth/link/otp/verify" in server
assert "GOOGLE_SERVER_CLIENT_ID" in server and "JWT_JWKS_URL" in server
assert "vkid" in (ROOT / "gradle/libs.versions.toml").read_text()
assert "yandex-auth" in (ROOT / "gradle/libs.versions.toml").read_text()
assert "credentials" in (ROOT / "gradle/libs.versions.toml").read_text()
assert 'versionName = "0.54.3"' in gradle
assert "hashRefreshToken" in auth_core
provider_auth = (backend / "src/provider-auth.js").read_text()
provider_http = (backend / "src/provider-http.js").read_text()
otp_auth = (backend / "src/auth-otp.js").read_text()
assert all(route in server for route in ["/v1/auth/otp/request", "/v1/auth/otp/verify", "providerMatch"])
assert "verifyGoogleIdToken" in provider_auth and "verifyYandexAccessToken" in provider_http and "verifyVkAccessToken" in provider_http
assert "login.yandex.ru/info" in provider_http and "id.vk.ru/oauth2/user_info" in provider_http
assert "AES-256-GCM" in otp_auth or "aes-256-gcm" in otp_auth
assert "CONTACT_HASH_SECRET" in otp_auth and "CONTACT_ENCRYPTION_KEY" in otp_auth
assert "pg_advisory_xact_lock(hashtextextended($1, 0))" in otp_auth
assert "OTP:${contactType}:" in otp_auth
app_ui = (ROOT / "app/src/main/java/ru/momento/app/ui/MomentoApp.kt").read_text()
assert "delay(waitMillis)" in app_ui and "delay(15_000L)" in app_ui
assert "deliverOtp" in server and "OTP_HMAC_SECRET" in server
assert "003_auth_session_hardening.sql" in (ROOT / "backend/README.md").read_text()
assert "REFRESH_REPLAY" in server and "/v1/auth/refresh" in server and "/v1/auth/logout" in server
assert (ROOT / "docs/AUTHENTICATION_DESIGN.md").exists()

# Mirror the table DDL from LocalMomentDatabase to verify SQLite constraints.
conn = sqlite3.connect(":memory:")
conn.executescript("""CREATE TABLE moments (
    id TEXT NOT NULL PRIMARY KEY,
    local_path TEXT NOT NULL UNIQUE,
    captured_at_ms INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL CHECK(duration_ms BETWEEN 2000 AND 5000),
    byte_size INTEGER NOT NULL CHECK(byte_size > 0),
    upload_state TEXT NOT NULL DEFAULT 'LOCAL_ONLY',
    created_at_ms INTEGER NOT NULL
);
CREATE INDEX index_moments_captured_at ON moments(captured_at_ms DESC);
CREATE INDEX index_moments_upload_state ON moments(upload_state);""")
conn.execute("INSERT INTO moments VALUES (?,?,?,?,?,?,?)", ("ok", "/tmp/ok.mp4", 1, 5000, 1, "LOCAL_ONLY", 1))
try:
    conn.execute("INSERT INTO moments VALUES (?,?,?,?,?,?,?)", ("bad", "/tmp/bad.mp4", 2, 5001, 1, "LOCAL_ONLY", 2))
except sqlite3.IntegrityError:
    pass
else:
    raise AssertionError("SQLite accepted a moment longer than five seconds")

conn_spaces = sqlite3.connect(":memory:")
conn_spaces.executescript("""CREATE TABLE spaces (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 60),
    created_at_ms INTEGER NOT NULL,
    sync_state TEXT NOT NULL DEFAULT 'LOCAL_ONLY'
        CHECK(sync_state IN ('LOCAL_ONLY', 'SYNC_PENDING', 'SYNCED', 'FAILED'))
);""")
conn_spaces.execute("INSERT INTO spaces VALUES (?, ?, ?, ?)", ("room-1", "Семья", 1, "LOCAL_ONLY"))
try:
    conn_spaces.execute("INSERT INTO spaces VALUES (?, ?, ?, ?)", ("room-2", "", 2, "LOCAL_ONLY"))
except sqlite3.IntegrityError:
    pass
else:
    raise AssertionError("SQLite accepted an empty room name")

print("MOMENTO checks passed: Android structure, 5-second policy, local DBs, backend auth routes, provider adapters syntax and backend unit tests.")
print("Note: this does not replace Android Gradle compilation or physical-device tests.")

# Avatar must remain an opt-in local preview with no implicit upload.
avatar = (ROOT / "app/src/main/java/ru/momento/app/ui/VideoAvatarSection.kt").read_text()
assert "2000L..5000L" in avatar and "stopPlayback()" in avatar
assert "VideoAvatarSection(savedMoments)" in app_ui

# Regression: no second network writer from the archive UI.
assert "uploadClient.uploadMoment(" not in ui
assert "MomentUploadWorker.enqueue(context, accountId)" in ui
assert "upload_state = 'QUEUED' WHERE upload_state = 'UPLOADING'" in database

# Regression guards for duration and retryable worker failures.
worker = (ROOT / "app/src/main/java/ru/momento/app/data/MomentUploadWorker.kt").read_text()
assert "Result.retry()" in worker
assert "duration in 1L..5_000L" not in camera

worker = (ROOT / "app/src/main/java/ru/momento/app/data/MomentUploadWorker.kt").read_text()
assert "MomentoSessionRefresh.refreshForOwner(vault, expectedOwner)" in worker
assert "vault.read()?.refreshToken != current.refreshToken" in (ROOT / "app/src/main/java/ru/momento/app/network/MomentoSessionRefresh.kt").read_text()
assert "Result.failure()" in worker

refresh_source = (ROOT / "app/src/main/java/ru/momento/app/network/MomentoSessionRefresh.kt").read_text()
assert "synchronized(lock)" in refresh_source
assert "client.authenticatedUserId(refreshed.accessToken)" in refresh_source
assert "MomentoSessionRefresh.refreshForOwner(sessionVault, owner)" in ui
assert "authClient.refresh(refreshToken)" not in ui

# 0.40.0: a long upload queue must refresh credentials for each item.
assert "val activeSession = try" in worker
assert "client.uploadMoment(activeSession.accessToken" in worker
assert "e.statusCode != 401" in worker
assert "vault.read()?.accessToken != activeSession.accessToken" in worker

# CI regression: backend checks and Android APK build are separate workflows.
quality_workflow = (ROOT / ".github/workflows/quality.yml").read_text()
android_workflow = (ROOT / ".github/workflows/android-build.yml").read_text()
assert "npm test" in quality_workflow
assert "scripts/test_upload_queue_migration.py" in quality_workflow
assert "assembleDebug" in android_workflow
assert "upload-artifact" in android_workflow
assert "scripts/restore_gradle_wrapper.sh" in android_workflow
assert ":app:testDebugUnitTest" in android_workflow
assert ":app:lintDebug" in android_workflow
assert "contents: read" in android_workflow
assert "MOMENTO-offline-debug.apk" in android_workflow

# The rooms-first UI must use actual account-scoped local data, not invented public listings.
rooms_ui = (ROOT / "app/src/main/java/ru/momento/app/ui/RoomsExperience.kt").read_text()
assert "LiveHomeScreen" in ui and "localSpaces" in ui
assert "CreateRoomScreen" in ui and "spacesDatabase.createRoom" in ui
assert "Мои комнаты" in rooms_ui and "Публичный каталог появится" in rooms_ui
assert "onSave(name.trim(), description.trim(), visibility, topic)" in rooms_ui

# The archive must reflect asynchronous WorkManager state transitions.
assert 'LaunchedEffect(database, spacesDatabase, selectedTab)' in ui
assert 'database.getMoments()' in ui
assert 'latest != savedMoments.toList()' in ui
