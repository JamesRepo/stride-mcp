# Phase 4b: Daily log

**Done when:** I ask "fill in my health log for yesterday", and the AI gets every column Garmin can supply for that day from one tool call.

_Status: plan approved 20 November 2026, with all the [decisions](#decisions-to-make). The owner confirmed which night goes with a date (✅) and approved the rest as proposed. Step 2 done 21 November 2026: the owner's capture of 20 November showed that both range replies are good enough, so a 28-day call takes about five requests, not 85 (see [What the capture showed](#what-the-capture-showed-20-november-2026)). The owner chose to add Garmin's calories eaten, as `caloriesEaten` (21 November 2026). Step 3 done 21 November 2026: the client (`GarminClient.dailyLog`). Next: step 4, the tool and the CLI._

## What the capture showed (20 November 2026)

The owner's `capture` on the evening of 20 November 2026 (about 22:38; the watch had last synced at 08:43) asked for 24 October to 20 November (28 days). The fixtures keep 5 of those days: 24 October (the oldest), 25 October (the clock change), 12 November (checked against the log), 19 November (yesterday) and 20 November (today, part of a day).

**Steps** (`summary-range.json`, `/usersummary-service/v2/dailysummaries`):

| Finding | What it means |
|---|---|
| The reply is `{ "data": [...] }`: one entry per day, with `calendarDate`, **oldest first**. All 28 days were there. | One request for the whole range |
| The field names are new. Steps are `movement.steps.value` (the one-day reply's `totalSteps`), the goal `movement.steps.goal`, the distance `movement.steps.distanceInMeters`. Calories, heart rate, stress, body battery and intensity minutes are in their own objects (`calories.burnedTotal`, `heartRate.restingValue`…). | A parser of its own. `GarminReplies.dailySummary` can't be reused. The log only needs steps. |
| 20 November is in both replies. Steps, goal, distance, calories burned, resting heart rate, stress, body battery, intensity minutes and floors are the same. Lowest and highest heart rate aren't (38 and 120 in the one-day reply, 41 and 118 in the range). | Steps can come from the range. Heart rate isn't in the log. |
| 12 November has 14,239 steps, as the log does | |
| Today is there too, as far as the watch had synced: 95 steps, with `wellnessChronology` ending at 08:42 | `final: false` covers it |
| 25 October, the day the clocks went back, is 25 hours long (`totalDurationInMillis` 90,000,000). Days are local days. | As in the other tools |
| `calories.consumedValue` is there on 4 of the 5 kept days (2,442 on 20 November, the same as the one-day reply's `consumedKilocalories`). On 12 November it's missing, and `consumedRemaining` is the goal plus the calories burned while active, so nothing was logged. The sheet has no calories for 12 November either. Protein, carbs and fat aren't there. | Used, as `caloriesEaten` (the owner's choice, see [Decisions](#decisions-to-make)). A day without it leaves it out, which covers 12 November. |
| Each day has a `uuid` and `userProfilePk`, and each body battery event a `deviceId` | Stripped from the fixture. Not used. |

**Calories eaten against the log** (checked 21 November 2026). The sheet's Calories column comes from MyFitnessPal. Here it is next to Garmin's `consumedValue` for all 28 captured days:

| Days | Sheet | Garmin |
|---|---|---|
| 24 to 30 October, 3 and 10 November (9 days) | A figure | The same |
| 2, 4 and 13 November | A figure | 1 kcal less (3,049 against 3,050 on 2 November) |
| 6, 7 and 12 November | Blank | None |
| 31 October, 1, 5, 8, 9 and 11 November, 14 to 20 November (13 days) | Blank | A figure |

So Garmin's figure is MyFitnessPal's daily total, rounded a little differently. Where Garmin has none, the sheet has none either. Garmin also has figures for 13 days the sheet hasn't logged yet. Two of them are low (387 on 5 November, 1,227 on 14 November), maybe days only partly logged in MyFitnessPal. That's not checked.

**Sleep** (`sleep-range.json`, `/sleep-service/stats/sleep/daily/{from}/{to}`):

| Finding | What it means |
|---|---|
| The reply has `overallStats` (averages for the range) and `individualStats`: one entry per night, **oldest first**, with `calendarDate` and `values`. All 28 nights were there. | One request for the whole range. `overallStats` isn't used. |
| `calendarDate` is the morning the night ended: 20 November's night started at 22:34 on the 19th | The same night rule as `get_sleep` |
| The field names are new: `sleepScore` (the one-day `sleepScores.overall.value`), `sleepScoreQuality` (`qualifierKey`), `totalSleepTimeInSeconds` (`sleepTimeSeconds`), `deepTime`, `lightTime`, `remTime`, `awakeTime` (`…SleepSeconds`), `gmtSleepStartTimeInMillis` and `gmtSleepEndTimeInMillis`. Times are seconds, start and end epoch milliseconds, as before. | A parser of its own |
| 20 November's night is in both replies. The score, label, time asleep, each stage, start and end are the same. Breathing and SpO2 aren't rounded in the range (13.07 and 96.89, against 13.0 and 97.0). | Score and hours can come from the range |
| 12 November: score 50 and 23,580 seconds (6.6 hours), as the log has | |
| On 4 nights `totalSleepTimeInSeconds` is a few seconds short of deep + light + REM (30,835 against 30,840) | Hours come from `totalSleepTimeInSeconds`. To 1 decimal place it's the same. |
| It also has HRV, resting heart rate, body battery change, skin temperature and sleep need | Not used (not in the log) |
| No names, ids or devices | Kept as it came, apart from the trim |

**Activity types** (`activity-types.json`, `/activity-service/activity/activityTypes`):

| Finding | What it means |
|---|---|
| A list of 156 types, each `{ typeId, typeKey, parentTypeId, isHidden, restricted, trimmable }` | Kept whole: it's Garmin's own list, with nothing personal in it |
| `parentTypeId` 17 is **`all`**, the root, not `running` as this plan first said. `running`'s parent is `all`, `trail_running`'s is `running`. Each type is at most two steps below `all`. | `group` is the type's ancestor just below `all`, or the type itself if its parent is `all` |
| `running` has 9 types below it: `trail_running`, `street_running`, `track_running`, `treadmill_running`, `virtual_run`, `obstacle_run`, `indoor_running`, `ultra_run`, `canicross` | All are `group: running` |
| The owner's other types: `indoor_cycling` → `cycling`; `strength_training` and `stair_climbing` → `fitness_equipment`; `soccer` → `team_sports` | `Gym?` and `Football?` in [4c](phase-4c-catch-up.md) go by `type`, not `group`, so this is fine |
| `soccer` is the football key. `american_football` is a different type. | Confirms 4c's `Football?` rule |
| An activity already carries its own `parentTypeId` | The list is only needed to name the parent |

**Not seen yet:** a day with no steps or a night with no sleep inside a range, and a type missing from the list. A day missing from `data` or `individualStats`, or with its values null, is read as "Garmin has nothing". A type not in the list gets no `group`. More than 28 days hasn't been tried: the limit comes from python-garminconnect, and our own limit stops us asking.

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
| Calories | My food tracker (MyFitnessPal). Garmin also has calories eaten on most days (seen 20 November 2026). It's MyFitnessPal's total: it matched the sheet on all 12 days that have both, to within 1 kcal, and neither has a figure for 12 November (checked 21 November 2026, see [above](#what-the-capture-showed-20-november-2026)). | `get_daily_log` gives it as `caloriesEaten`. Garmin's other `calories` are calories **burned**. They must never end up in this column. |
| Protein, Carbs, Fat | My food tracker (MyFitnessPal) | **Never Garmin.** Garmin doesn't have them. |

So stride-mcp can already supply everything Garmin knows. What's missing is doing it well:

- **It takes many calls.** One day needs `get_sleep`, `get_daily_summary` and `get_activities`. Catching up two weeks takes about 30 tool calls, and about 45 requests to Garmin.
- **Weight is one day at a time** (fixed in 4a).
- **Activity types aren't grouped.** Is `treadmill_running` a run? Garmin's list of activity types says it is (20 November 2026).

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

(The values are made up, apart from the sleep, steps and minutes, which match the log. There's no `caloriesEaten`, because Garmin had none for 12 November.)

- **`sleep`** is the night that ended on the morning of `date`, as in `get_sleep`.
- **`activities`** are the ones that started that day, in the owner's time zone. Garmin's search goes by the date on the clock where the activity happened, which can differ when travelling (a run at 20:30 in New York is 01:30 the next day in London). So the search covers a day either side of the range, and only the activities that started inside it in the owner's time zone are kept (after review, 21 November 2026). `minutes` is the whole duration, not the moving time. (On 21 October the log has 70.1 minutes for a 1:10:03 session.)
- **`group`** is Garmin's top-level type: the type's ancestor just below `all`, or the type itself if it's already top-level. So `running`, `trail_running` and `treadmill_running` are all `running`, `indoor_cycling` is `cycling`, and `strength_training` is `fitness_equipment`. A type that isn't in Garmin's list gets no `group`. An activity names its parent only as a number (`parentTypeId`: 17 is `all`, 2 is `cycling`, 29 is `fitness_equipment`), so naming it needs Garmin's list of activity types, from `/activity-service/activity/activityTypes` (seen 20 November 2026). It hardly ever changes, so it's cached for 30 days. This means 4b doesn't depend on phase 2's open question about `--type running`, but it doesn't answer it either: the list says what's below `running`, not what Garmin's search filter does.
- **`caloriesEaten`** is the food logged for that day, in kcal, from `calories.consumedValue` in the steps reply (no extra request). It's not calories burned, and the tool description says so. A day with none, or with 0, leaves it out: nobody eats 0 kcal, so 0 means nothing was logged. Protein, carbs and fat aren't in Garmin.
- **`weight`** is the day's weigh-in, picked as in 4a.
- **`final`** is `false` for today: the watch and scale may still sync. Yesterday is `true`, although the cache still treats it as changing (15 minutes): I log yesterday each morning, after the overnight sync, so it's normally complete (the owner's choice after review, 21 November 2026).
- Parts Garmin doesn't have are left out. A day with nothing at all is `{ "date": …, "final": true }`, so the AI can tell "no data" from "not asked".

The reply speaks Garmin, not my spreadsheet. There's no `Run?` or `Gym?` here. Why: stride-mcp works with any AI app and any log, and the sheet's rules belong with the sheet. They're in [4c](phase-4c-catch-up.md).

A CLI command, `stride-mcp log [from] [to]`, prints the same, for checking.

## Garmin

Up to 28 days takes three range requests, whatever the number of days, plus the activity search (one request per 20 activities, so about two for 28 days) and the activity types once a month:

| Data | Path | Seen 20 November 2026 |
|---|---|---|
| Steps | `/usersummary-service/v2/dailysummaries`, `startDate`, `endDate` | ✅ Used. Every day, oldest first, steps in `movement.steps.value`. |
| Sleep | `/sleep-service/stats/sleep/daily/{from}/{to}` | ✅ Used. Every night, oldest first, with the score and time asleep. |
| Weigh-ins | `/weight-service/weight/range/{from}/{to}` | ✅ From [4a](phase-4a-weight.md), as it is |
| Activities | The search we already use, one day wider at each end | Already in the cache. Still about two requests for 28 days. |
| Activity types | `/activity-service/activity/activityTypes` | ✅ For `group`. Once every 30 days, not once per call. Asked for again if an activity's type isn't in it, once per type per run. |

**Range or one day at a time?** Both range replies are good enough, so there's no fallback to the one-day calls:

| Reply | Decision | Why | One day at a time would cost, for 28 days |
|---|---|---|---|
| Steps | **The range** | Every day is there, and steps match the one-day reply and the log | 28 requests, not 1 |
| Sleep | **The range** | Every night is there, with the score, time asleep and the same night rule. They match the one-day reply and the log. | 28 requests, not 1 |
| Activity types | **The list, cached for 30 days** | It names every parent. Writing the parents into our config instead would go stale when Garmin adds a type. | Not possible: no other call names a parent |

All three falling back would have taken up to 85 requests for 28 days. This way it's about five.

The 28-day limit is Garmin's on the sleep call (python-garminconnect, checked 20 November 2026). Keeping our limit the same means one request per kind per call.

**Time limits.** Codex gives each tool call a time limit (`tool_timeout_sec`, 60 seconds by default, to check). About five requests fit easily, so the most days per call stays 28.

## Decisions to make

| Question | Proposed | Why |
|---|---|---|
| A new tool, or make the four tools take ranges? | **A new tool** | One call for a log row is the point. The other four stay as they are. |
| Garmin's words, or the spreadsheet's columns? | **Garmin's words, plus `group`** | Works for any log. The sheet's rules live in the sheet (4c). |
| Most days per call | **28** | Garmin's limit. A longer catch-up makes more calls, 28 days each. |
| Default | **Yesterday** | I log every day, and the day just finished is the one to log. That's one call with no inputs. |
| Which night goes with a date? | ✅ **The night that ended that morning.** The owner confirmed the sheet works this way (20 November 2026): 19 November's score is the night of 18 to 19 November. | Garmin's own rule, and `get_sleep`'s, so nothing needs converting |
| Today | **Allowed, with `final: false`** | So "how am I doing today?" works, and the AI knows not to log it yet |
| Range replies, or one day at a time? | ✅ **The range replies** (settled by the capture, 21 November 2026) | Both have every day and match the one-day replies. About five requests for 28 days, not 85. See [Garmin](#garmin). |
| Which type is a `group`? | ✅ **The top-level type below `all`** (from the capture, 21 November 2026) | Garmin's own tree. `running` is its own group, and `trail_running` and `treadmill_running` are in it. |
| Garmin's calories eaten (`calories.consumedValue`)? | ✅ **Add it, as `caloriesEaten`** (the owner's choice, 21 November 2026; proposed: leave it out) | It's in the steps reply already, so it costs nothing. The name keeps it apart from calories burned. On 21 November 2026 it matched the sheet's Calories column (from MyFitnessPal) on all 12 days that have both, to within 1 kcal. So [4c](phase-4c-catch-up.md) fills that column from it, blank cells only (the owner's choice, 21 November 2026). |

## Steps

1. **Plan:** this doc.
2. **Capture** (owner): `capture` also saves the range calls for the last 28 days and the activity types, as `summary-range.json`, `sleep-range.json` and `activity-types.json` (the weigh-ins are 4a's `weight-range.json`, 30 days). ✅ Built and tested 20 November 2026, saved raw and not parsed. ✅ Done 21 November 2026: the owner ran it on 20 November, and the replies are stripped into fixtures and settled (see [What the capture showed](#what-the-capture-showed-20-november-2026)). Both range replies are used.
3. **Client:** range methods in `GarminClient` for steps and sleep, each with its own parser, plus the weigh-ins from 4a as they are. Activities grouped by local day, with their `group` from the activity types (cached for 30 days). Tests: `summary-range.json`, `sleep-range.json` and `activity-types.json`, the clock-change day, today's part day, a day missing from a range reply or with its values null, a type not in the list, calories eaten missing or 0 (left out), a day with three activities, an activity just after midnight, more than 28 days refused with nothing sent, 429 once with no retry. ✅ Done 21 November 2026, in `GarminClient`:
   - `dailyTotals(from, to)` reads steps and calories eaten from the range of daily summaries, and `sleep(from, to)` each night from the sleep stats. Each has its own parser in `GarminReplies`. A day with nothing, or outside the range, is left out. Calories eaten of 0 or less count as none. A day Garmin marked `privacyProtected` is refused, as the one-day summary is, not shown as a day with nothing (after review, 21 November 2026; not seen in a range reply yet).
   - `activityTypes()` gives each type's group (`ActivityTypes`). It's only asked for when there are activities. It comes from the cache for 30 days, even with `refresh`, like the profile. If an activity's type isn't in the cached list, the log asks for the list again, as Garmin may have added it (the owner's choice after review, 21 November 2026). Only once per type per run of stride-mcp, so a type Garmin never lists doesn't cost a request on every call.
   - `dailyLog(from, to)` puts them together with the weigh-ins and the activity search: a `LogDay` for every day, oldest first, with `isFinal` false for today. An activity's day is the day it started in the owner's time zone. The search covers a day either side, so an activity recorded abroad isn't missed at either end. A day's activities are oldest first. Any request failing fails the whole call.
   - At most `GarminDataConfig.MAX_LOG_RANGE_DAYS` (28) days, checked before anything is sent, not even the profile lookup. `capture` uses the same constant.
   - Cached like the weigh-ins over a range: 15 minutes if the range includes today or yesterday, otherwise a day, or an hour if it had nothing. That lifetime is now `CacheLifetimes.olderRange`, as it's not only for weight any more.
4. **Tool and CLI:** `get_daily_log`, its reply and description, and `stride-mcp log`. Tests as for the other tools, including over stdio.
5. **Docs:** the quick start, the tool table, AGENTS.md status.
6. **Live check** (owner): "what did Garmin record for the last week?" in Codex, which I'll use every day, then in Claude Code. Compare with the Garmin Connect app.

## Not in this plan

- Writing to the spreadsheet. That's the AI app's job, through its Google Sheets connection ([4c](phase-4c-catch-up.md)).
- Protein, carbs and fat. Garmin doesn't have them.
- HRV, Body Battery, stress and training status. They aren't in the log. Easy to add to the reply later.
