# MOMENTO — MVP scope

## Product principle
MOMENTO is a focused Russian-market evolution of the SetVlog/Setlog idea. The core loop is: capture a tiny video moment (maximum 5 seconds) → share it with a room → see the room's moments → revisit the shared timeline.

## In MVP
- Android app first; backend API designed so iOS can use the same contract later.
- Record a video moment of 1–5 seconds; hard stop at 5 seconds.
- Private personal archive by default.
- Rooms: create, invite by link/code, join, member list, shared chronological moments.
- Private and public room visibility; public discovery only if moderation and age/access rules are ready.
- Comments/reactions kept lightweight.
- Offline local save and automatic upload retry when connectivity returns.
- Basic privacy, block, report, and moderation tools.

## Explicitly out of MVP
- Direct messages between strangers.
- Infinite algorithmic feed.
- Complex recommendation engine.
- Elaborate video editor, filters, music library, creator tools.
- Stories/reels clones, follower counts, public popularity rankings.
- Commerce, livestreams, large group chat, gamification.

## Product invariants
- Maximum moment duration: 5,000 ms, enforced in client and server.
- A new moment is private until the owner explicitly grants a room audience.
- A single media asset can be granted to several rooms; do not duplicate the source file per room.
- Public room membership never makes a user's private archive public.
- A failed upload must not delete the only local copy.

## Initial implementation milestones
1. Android app shell and core navigation (this first scaffold).
2. CameraX capture, hard 5-second stop, local file persistence and preview.
3. Room database and offline queue (WorkManager).
4. Backend authentication, room membership/invites, upload sessions, moment timeline.
5. Moderation/privacy and integration tests.
6. Device QA and closed beta.
