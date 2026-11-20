# Phase 4a: Weight history

**Done when:** I ask "how has my weight changed since September?" and get an answer built from my Garmin scale's weigh-ins.

_Status: plan approved 20 November 2026, with all the [decisions](#decisions-to-make). The owner chose which weigh-in counts (✅) and approved the rest as proposed. Step 2 done 20 November 2026: the owner's capture confirmed the field names and grams (see [What the capture showed](#what-the-capture-showed-20-november-2026)). Step 3 done 20 November 2026: `GarminClient.weighIns(from, to)`. Next: step 4, the tool and the CLI command._

## What the capture showed (20 November 2026)

| Finding | What it means |
|---|---|
| A weigh-in's field names match the guesses, and masses are in grams | Nothing to fix in `summary` or `get_daily_summary` |
| `date` is the clock time written as if it were UTC: an hour ahead of `timestampGMT` in UK summer time (`previousDateWeight`, 20 October). In November they're equal. | A day is sorted by `timestampGMT` if every weigh-in with a time has it, otherwise by `date`, never a mix. One with no time goes last. |
| The range reply is `dailyWeightSummaries`: one entry per day **with** a weigh-in, newest day first | Days with none are already left out |
| Each day has `summaryDate` and `allWeightMetrics`, that day's weigh-ins, newest first, named as in the one-day reply | One parser for both. We sort them ourselves, so the first of the day is easy to pick. |
| Each day also has `latestWeight`, `minWeight`, `maxWeight` and `numOfWeightEntries` | Not used: we pick the first weigh-in ourselves |
| `sourceType` is `INDEX_SCALE` for the scale, `USER_SETTING` for a weight typed in on Garmin Connect, with no body composition | |
| 14 November had two weights typed in 4 seconds apart | The first one counts, as decided |
| The reply also has `previousDateWeight` (the last weigh-in before the range), `nextDateWeight` and `totalAverage` | Not used |
| 30 days in one request worked | |

Not seen yet: a range with no weigh-ins at all. It's read as empty if the list is missing, null or empty.

## Why

I weigh in on a Garmin scale. Today `get_daily_summary` gives one day's weight and body composition, so a trend takes one call per day. This plan adds one tool for a range of days. [Phase 4b](phase-4b-daily-log.md) uses the same Garmin call to fill in the health log.

## First: capture a real weigh-in

Two things have been unconfirmed since 25 October 2026 (see [Phase 2](phase-2-data.md#sleep-and-weight-in-the-summary-added-25-october-2026)). The owner hadn't weighed in on the day of the capture, so no real weigh-in has been seen:

| Still to confirm | Why it matters |
|---|---|
| The field names of one weigh-in | We guessed them from python-garminconnect |
| Masses in grams | If they're in kg, every weight is off by 1,000 |

So step 2 adds the range call to `capture`. The owner weighs in, then runs `capture`. The fixtures come from that run, and so does a fix to `summary` and `get_daily_summary` if the guesses were wrong.

## The tool: `get_weight`

| Input | Meaning |
|---|---|
| `from`, `to` | Dates like 2026-09-01, both included, in the owner's time zone. At most 367 days, so a year to the same date fits even across 29 February. Leave both out for the last 30 days. |
| `refresh` | As in the other tools: ask Garmin even if a copy is cached |

The reply has one entry per day with a weigh-in, oldest first:

| Field | Unit |
|---|---|
| `date`, `time` | The day, and the time of the weigh-in we picked (see [Decisions](#decisions-to-make)) |
| `weightKg` | kg, 1 decimal place |
| `bodyFatPercent`, `bodyWaterPercent` | % (18.5 means 18.5%) |
| `muscleMassKg`, `boneMassKg` | kg |
| `bmi`, `visceralFat`, `metabolicAge` | As Garmin gives them |
| `weighIns` | How many weigh-ins that day |

Fields the scale didn't send are left out, as everywhere. On top of that, a short summary for the range: the first and last weight, the change in kg, the lowest and highest, and how many days had a weigh-in. AI apps are bad at adding up long lists, so we do it for them.

A CLI command, `stride-mcp weight [from] [to]`, prints the same thing, so the owner can check the numbers without an AI app. The other data tools all have one too.

## Garmin

| Call | Path | Notes |
|---|---|---|
| Weigh-ins over a range | `/weight-service/weight/range/{from}/{to}`, `includeAll=true` | python-garminconnect's `get_weigh_ins`. No range limit in its code (checked 20 November 2026). Reply shape seen 20 November 2026: see above. |
| Daily body composition (fallback) | `/weight-service/weight/dateRange`, `startDate`, `endDate` | python-garminconnect's `get_body_composition`. Use it only if the capture shows the first one is no good. |

One request per call, whatever the range. Paths and parameter names go in `GarminDataConfig`.

Cache: if the range includes today or yesterday, 15 minutes (the scale may still sync). Otherwise **a day**, or an hour if it had no weigh-ins. Not the 30 days a single older day gets: a weight typed in later for a day in the range should show by the next day, as [phase 4c](phase-4c-catch-up.md) catches up the log from these (changed 20 November 2026, after review).

## Decisions to make

| Question | Proposed | Why |
|---|---|---|
| Which weigh-in counts for a day with several? | ✅ **The first one of the day** (owner's choice, 20 November 2026) | Morning weigh-ins are the most consistent. A weight typed in later, or one taken after a meal, won't move the trend. |
| Should `get_daily_summary` and `summary` change to match? | ✅ **Yes, the first one** (they use the latest today). Agreed with the choice above. | One rule everywhere, so a day's weight is the same in every tool and in the log |
| Most days per call | **367** (was 366, changed 20 November 2026) | It's one request to Garmin. A year is a common question, and a year to the same date is 367 days with both ends counted when it includes 29 February. |
| Default range | **Last 30 days** | Enough for a trend |
| Days with no weigh-in | **Left out** | Matches the sheet: "a blank means no reading, not no change" |

## Steps

1. **Plan:** this doc.
2. **Capture** (owner): `capture` also saves the range call for the last 30 days, as `weight-range.json`. ✅ Done 20 November 2026. The field names and grams were right, so `summary` and `get_daily_summary` needed no fix.
3. **Client:** `GarminClient.weighIns(from, to)`, with the path in `GarminDataConfig`, the cache, and fixtures. Tests: the reply from the capture, no weigh-ins, a day with two, a range over the limit refused with nothing sent, and 429 and 403 once with no retry. ✅ Done 20 November 2026. Every weigh-in in the range, oldest first, each with its day; the limit is `GarminDataConfig.MAX_WEIGHT_RANGE_DAYS`; cached as `weight-<from>-to-<to>.json`. After review (20 November 2026): days outside the range are left out, a weigh-in with no time sorts last in its day so it can't become the day's weight, a day is sorted by one time field only (see below), and the limit is 367.
4. **Tool and CLI:** `get_weight`, its reply and description, and `stride-mcp weight`. Tests as for the other tools, including over stdio. Also switch `DayWeight` (used by `summary` and `get_daily_summary`) from the latest weigh-in to the first, so a day's weight is the same everywhere.
5. **Docs:** the quick start, the tool table, AGENTS.md status.
6. **Live check** (owner): "how has my weight changed since September?" in Claude Code.

## Not in this plan

- Weight goals, or adding a weigh-in to Garmin
- Charts. The AI app can draw one from the reply.
