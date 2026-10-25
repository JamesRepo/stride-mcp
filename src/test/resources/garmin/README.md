# Garmin reply fixtures

Real replies from Garmin Connect, captured with `stride-mcp capture` on 23 October 2026. Each file is the reply body only (no status, no headers).

| File | Request |
|---|---|
| `social-profile.json` | `socialProfile` |
| `personal-information.json` | My time zone |
| `activities.json` | Activities for 17–23 October 2026 (8, newest first) |
| `activity.json`, `activity-splits.json` | The newest of those (a stair stepper session) and its one lap |
| `summary-today.json`, `summary-past.json` | 23 October and 23 September 2026 |
| `sleep-last-night.json` | The night of 22–23 October |
| `sleep-none.json` | 1 January 2000: a 200 whose `dailySleepDTO` fields are all null |
| `weight-none.json` | 1 January 2000: a 200 with an empty `dateWeightList` and an all-null `totalAverage`. Captured 25 October 2026, unchanged. |

How they were changed from the raw replies:

- **Stripped:** names, ids, email, birth date, body stats, locations, coordinates, polylines, links, device ids and UUIDs. The fields are kept with `0` or `"redacted"` as the value.
- `displayName` is `"jo runner"` (made up, with a space so tests check it's encoded in paths).
- **Trimmed:** `userRoles` removed from the profile and each activity. Every list in the sleep reply cut to its first 3 entries.

Made-up variants (missing fields, odd values) live in the tests themselves, not here.

No real day **with** weigh-ins has been captured yet (25 October 2026: the owner hadn't weighed in). Until one is, the tests use a made-up one: `Fixtures.TWO_WEIGH_INS`. Replace it with a stripped `weight-today.json` from `capture`.
