# MOMENTO UI changes (2026-11-23)

These are source-level changes, not verified Android builds.

- `ui/MomentoDesign.kt`: added common design tokens, accessible Material3 action button, reusable room tile, state panel and CameraX-driven recording status.
- `ui/GuestHomeScreen.kt`: replaced public room cards and unavailable/empty states with shared components; primary guest camera CTA standardized.
- `ui/RoomsExperience.kt`: standardized locally owned room cards and empty room state.
- `ui/CameraScreen.kt`: recording status and progress, prominent circular record control, explicit live-camera-only copy. Recording logic unchanged.

Visual reference: `UI-MOCKUPS-NOT-SCREENSHOTS.png` is an **illustrative generated mockup**, not a Compose Preview or app screenshot. It contains aspirational UI elements not yet present in source (e.g. cover photos, fully built reminder settings, profile statistics, reactions).

Reference approaches: official Android Compose Samples and Now in Android (Apache-2.0); no source code copied from repositories, no new UI dependencies.

Backend test: `node --test`: 100 tests, 98 pass, 2 skip, 0 fail. Android compile, lint, emulator and APK: not run (no Android SDK or local Gradle distribution).
