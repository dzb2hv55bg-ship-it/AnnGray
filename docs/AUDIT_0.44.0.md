# MOMENTO 0.44.0 — build pipeline hardening

- The Android GitHub Actions job now runs `scripts/build_preflight.py` before compilation, so local and CI prerequisite checks share the same implementation.
- Workflow permissions are restricted to read-only repository content.
- APK publication remains conditional on successful Android compilation.
- Reviewed third-party repository list in `docs/THIRD_PARTY_REFERENCES.md`; existing references are documented, but **their current HEAD and licenses have not been independently reverified this turn**. No third-party code copied in this release.
- Version metadata synchronized to 0.44.0.

**Not yet validated:** GitHub Actions run, Android compilation, emulator/device testing, backend PostgreSQL integration. This release does not claim a working APK.
