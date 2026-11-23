# MOMENTO — approved memory features (2026-11-21)

- Video-only product: user-recorded moments **2–5 seconds**, no photo mode. Existing database allows 1–5s: tighten validation in capture/upload APIs before enabling them.
- Live avatar: a user-owned, READY video asset 2–5s, looped silently, with a still frame as fallback when playback is unavailable; avatar is not a photo-upload feature. Existing `users.avatar_media_id` supports the reference. Ownership checks and media upload pipeline remain TODO.
- Life calendar: user-selectable **all / month / week**, never default public. Date grouping must use user's timezone and authorized moments only; current helper uses UTC and is not the final timezone-aware query.
- Time capsule: reveal after at least **one calendar year**; no public release, no disclosure before release. The SQL CHECK currently checks future only; API must validate >=1 year.
- Room film: export a compilation of 2–5s source moments; output film may exceed five seconds but no user can record a long clip. Only explicitly consented moments may be included. Verify room membership and media grants at export and playback, and revoke output when permissions change.
- Room video diary: chronological playback of authorized room moments; not a public feed.
- Removed from roadmap: **On this day / Past moments** retrospective feature.
- Personal stats remain in scope, computed from authorized private moments.

## References (reviewed 2026-11-21)
- Official AndroidX Media3 (player, Transformer): https://github.com/androidx/media
- AndroidX official Compose samples: https://github.com/android/compose-samples
- Android Material3 DatePicker docs: https://developer.android.com/develop/ui/compose/components/datepickers
- Media3 player docs: https://developer.android.com/media/media3/exoplayer/hello-world

## Delivery status
This iteration adds **schema foundations and pure validation tests**. No video upload, rendering, room film endpoint, avatar playback, capsule scheduler or Android UI is implemented yet. APK and PostgreSQL migrations not integration-tested.
