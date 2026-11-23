# MOMENTO 0.21.0 — Life calendar API

- Authenticated GET /v1/me/calendar?period=week|month|all&offsetMinutes=180.
- Period is required; offsetMinutes is an explicit fixed UTC offset, not an IANA timezone.
- Private owner-only moments, excluding deleted and non-visible moderation.
- Week = current local calendar day and six preceding days; month = current calendar month; all = unrestricted start.
- This is backend API only: Android UI, uploads, production DB integration and real timezone/DST support remain pending.
