# Phase 4b: Daily log

**Done when:** I ask "fill in my health log for yesterday", and the AI gets every column Garmin can supply for that day from one tool call.

_Status: plan approved 20 November 2026, with all the [decisions](#decisions-to-make). The owner confirmed which night goes with a date (✅) and approved the rest as proposed. Step 2's code is done (20 November 2026), in the same `capture` run as [4a](phase-4a-weight.md)'s. Next: the owner runs it._

## What the health log needs from Garmin

My health log is a Google Sheet ("Health Log — Source of Truth"). New rows go in its `daily_log` tab, and the `History` tab shows them newest first. It has 39 columns. Read 20 November 2026:

| Column | Where it comes from | In stride-mcp today? |
|---|---|---|
| Sleep Score | The sleep score, 0 to 100 | ✅ `get_sleep` |
| Sleep Hrs | Time asleep, in hours | ✅ `get_sleep` (in seconds) |
| Activities, Sessions, Run?, Gym?, Football?, Exercise Mins | The day's activities: type, name and duration | ✅ `get_activities` |
| Steps | The day's steps | ✅ `get_daily_summary` |
| Weight (kg), Body Fat %, Muscle (kg) | The day's weigh-in | ✅ `get_daily_summary` (one day). Range in [4a](phase-4a-weight.md). |
| Sleep (1-3), Sleep, Fat Mass (kg) | Worked out from the columns above | The AI or the sheet, from the rules in [4c](phase-4c-catch-up.md) |
| Date, Year, Month #, Month, Day, Week Start | The date | The AI or the sheet |
| Mood, Alcohol, Drinks, Drugs?, Stretch, Sex, Notes | Me | Never Garmin |
| Calories, Protein, Carbs, Fat | My food tracker (MyFitnessPal) | **Never Garmin.** These are calories eaten. Garmin's `calories` are calories burned. They must not end up in this column. |

So stride-mcp can already supply everything Garmin knows. What's missing is doing it well:

- **It takes many calls.** One day needs `get_sleep`, `get_daily_summary` and `get_activities`. Catching up two weeks takes about 30 tool calls, and about 45 requests to Garmin.
- **Weight is one day at a time** (fixed in 4a).
- **Activity types aren't grouped.** Is `treadmill_running` a run? This has been open since phase 2.

## The tool: `get_daily_log`

| Input | Meaning |
|---|---|
| `from`, `to` | Dates like 2026-11-08, both included. At most 28 days. Leave both out for yesterday. Only `from`: from there to yesterday. |
| `refresh` | As in the other tools |

One entry per day, oldest first, with what Garmin has for it:

```json
{
  "date": "2026-11-12",
  "final": true,
  "sleep": { "score": 50, "hours": 6.6 },
  "steps": 14239,
  "activities": [
    { "id": 24890000000, "name": "London Running", "type": "running", "group": "running", "minutes": 49.9 }
  ],
  "weight": { "kg": 89.5, "bodyFatPercent": 19.2, "muscleMassKg": 37.2, "time": "07:41", "weighIns": 1 }
}
```

(The values are made up, apart from the sleep, steps and minutes, which match the log.)

- **`sleep`** is the night that ended on the morning of `date`, as in `get_sleep`.
- **`activities`** are the ones that started that day, in the owner's time zone. `minutes` is the whole duration, not the moving time. (On 21 October the log has 70.1 minutes for a 1:10:03 session.)
- **`group`** is Garmin's parent type, so `trail_running` and `treadmill_running` are both `running`. This also settles phase 2's open question. Garmin sends the parent as a number (`parentTypeId`: 17 for `running`, 2 for `indoor_cycling`, 29 for `strength_training` in our fixtures), so naming it needs Garmin's list of activity types. python-garminconnect gets it from `/activity-service/activity/activityTypes` (to check). It hardly ever changes, so it's cached for 30 days.
- **`weight`** is the day's weigh-in, picked as in 4a.
- **`final`** is `false` for today: the watch and scale may still sync.
- Parts Garmin doesn't have are left out. A day with nothing at all is `{ "date": …, "final": true }`, so the AI can tell "no data" from "not asked".

The reply speaks Garmin, not my spreadsheet. There's no `Run?` or `Gym?` here. Why: stride-mcp works with any AI app and any log, and the sheet's rules belong with the sheet. They're in [4c](phase-4c-catch-up.md).

A CLI command, `stride-mcp log [from] [to]`, prints the same, for checking.

## Garmin

Up to 28 days takes at most four requests, whatever the number of days, plus the activity types once a month:

| Data | Path | From python-garminconnect (checked 20 November 2026) |
|---|---|---|
| Steps | `/usersummary-service/v2/dailysummaries`, `startDate`, `endDate` | `get_daily_summaries`: "full daily summaries for a date range in one call" |
| Sleep | `/sleep-service/stats/sleep/daily/{from}/{to}` | `get_sleep_daily`: "a 28-day limit per request" |
| Weigh-ins | `/weight-service/weight/range/{from}/{to}` | From [4a](phase-4a-weight.md) |
| Activities | The search we already use | Already in the cache |
| Activity types | `/activity-service/activity/activityTypes` | For `group`. Once every 30 days, not once per call. |

**None of these replies have been seen yet.** The sleep stats may not include time asleep, for example. Step 2 captures them. Wherever a range reply turns out to be no good, we fall back to the one-day calls we already have, which are cached. With all three falling back, a 28-day call would take up to 85 requests (3 a day, plus the activity search). Before choosing that, we'd stop and ask the owner.

The 28-day limit is Garmin's, on two of these calls. Keeping our limit the same means one request per kind per call.

**Time limits.** Codex gives each tool call a time limit (`tool_timeout_sec`, 60 seconds by default, to check). Four requests fit easily. The one-day fallback for 28 days may not, so if we fall back, we either lower the most days per call or say in the docs how to raise the limit.

## Decisions to make

| Question | Proposed | Why |
|---|---|---|
| A new tool, or make the four tools take ranges? | **A new tool** | One call for a log row is the point. The other four stay as they are. |
| Garmin's words, or the spreadsheet's columns? | **Garmin's words, plus `group`** | Works for any log. The sheet's rules live in the sheet (4c). |
| Most days per call | **28** | Garmin's limit. A longer catch-up makes more calls, 28 days each. |
| Default | **Yesterday** | I log every day, and the day just finished is the one to log. That's one call with no inputs. |
| Which night goes with a date? | ✅ **The night that ended that morning.** The owner confirmed the sheet works this way (20 November 2026): 19 November's score is the night of 18 to 19 November. | Garmin's own rule, and `get_sleep`'s, so nothing needs converting |
| Today | **Allowed, with `final: false`** | So "how am I doing today?" works, and the AI knows not to log it yet |

## Steps

1. **Plan:** this doc.
2. **Capture** (owner): `capture` also saves the range calls for the last 28 days and the activity types, as `summary-range.json`, `sleep-range.json` and `activity-types.json` (the weigh-ins are 4a's `weight-range.json`, 30 days). ✅ Built and tested 20 November 2026, saved raw and not parsed. Still to do: the owner runs it. Settle each reply's shape, or fall back to one day at a time.
3. **Client:** range methods in `GarminClient` for steps, sleep and weigh-ins (shared with 4a). Activities grouped by local day, with their parent type. Tests: the replies from the capture, empty days, a day with three activities, an activity just after midnight, more than 28 days refused with nothing sent, 429 once with no retry.
4. **Tool and CLI:** `get_daily_log`, its reply and description, and `stride-mcp log`. Tests as for the other tools, including over stdio.
5. **Docs:** the quick start, the tool table, AGENTS.md status.
6. **Live check** (owner): "what did Garmin record for the last week?" in Codex, which I'll use every day, then in Claude Code. Compare with the Garmin Connect app.

## Not in this plan

- Writing to the spreadsheet. That's the AI app's job, through its Google Sheets connection ([4c](phase-4c-catch-up.md)).
- Calories eaten and macros. Garmin doesn't have them.
- HRV, Body Battery, stress and training status. They aren't in the log. Easy to add to the reply later.
