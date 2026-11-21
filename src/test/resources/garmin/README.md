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
| `weight-today.json` | 20 November 2026: one weigh-in from an Index scale. Captured 20 November 2026. |
| `weight-range.json` | 22 October to 20 November 2026 (`/weight-service/weight/range/{from}/{to}`). Captured 20 November 2026. Trimmed from 14 days to 4: the newest two, the oldest, and 14 November, which has two weights typed in (`USER_SETTING`) 4 seconds apart. |
| `summary-range.json` | 24 October to 20 November 2026 (`/usersummary-service/v2/dailysummaries`). Captured on the evening of 20 November 2026; the watch had last synced at 08:43. Trimmed from 28 days to 5: 24 October (the oldest), 25 October (the clock change, 25 hours), 12 November, 19 November and 20 November (today, synced to 08:42). Each day's `bodyBattery.activityEvents` cut to its first 3. |
| `sleep-range.json` | The same 28 nights (`/sleep-service/stats/sleep/daily/{from}/{to}`), trimmed to the same 5. `overallStats` is still the average of all 28, so it won't match the 5 left. |
| `activity-types.json` | Garmin's list of 156 activity types (`/activity-service/activity/activityTypes`), captured 20 November 2026. Kept whole. |

How they were changed from the raw replies:

- **Stripped:** names, ids, email, birth date, body stats, locations, coordinates, polylines, links, device ids and UUIDs. The fields are kept with `0` or `"redacted"` as the value.
- `displayName` is `"jo runner"` (made up, with a space so tests check it's encoded in paths).
- **Trimmed:** `userRoles` removed from the profile and each activity. Every list in the sleep reply cut to its first 3 entries.

Made-up variants (missing fields, odd values) live in the tests themselves, not here.

- **Weigh-ins (20 November 2026):** every weight, BMI, body fat, body water, muscle and bone mass, and `weightDelta` replaced with made-up values, and `samplePk` renumbered. Dates, times, `sourceType` and nulls are real. In the range, each day's `latestWeight`, `minWeight` and `maxWeight` are rebuilt from the made-up values.

- **Ranges (20 November 2026):** in `summary-range.json`, each day's `uuid` is `"redacted"`, `userProfilePk` and every body battery event's `deviceId` `0`. `sleep-range.json` and `activity-types.json` had no names or ids to strip. **Every health value in the two range fixtures is real**, not made up as the weigh-ins are. Kept that way on purpose (the owner's choice, 21 November 2026):
  - `summary-range.json`: steps, distance and floors; calories eaten and burned (and the calorie goal); heart rate; stress; body battery, with its feedback and activity events; intensity minutes; hydration; breathing rate; SpO2.
  - `sleep-range.json`: sleep score, times and stages; heart rate and resting heart rate; HRV (with its 7-day average and status); SpO2; breathing rate; skin temperature; body battery change; sleep need; and `overallStats`, the averages of all of these over 28 days.

`Fixtures.TWO_WEIGH_INS` is a made-up day with a scale reading and a weight typed in. It's still used where a test needs two different weigh-ins on one day.
