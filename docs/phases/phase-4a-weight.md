# Phase 4a: Weight history

**Done when:** I ask "how has my weight changed since September?" and get an answer built from my Garmin scale's weigh-ins.

_Status: plan approved 20 November 2026, with all the [decisions](#decisions-to-make). The owner chose which weigh-in counts (✅) and approved the rest as proposed. Step 2's code is done (20 November 2026): `capture` saves `weight-range.json`. Next: the owner weighs in and runs `capture`._

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
| `from`, `to` | Dates like 2026-09-01, both included, in the owner's time zone. At most 366 days. Leave both out for the last 30 days. |
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
| Weigh-ins over a range | `/weight-service/weight/range/{from}/{to}`, `includeAll=true` | python-garminconnect's `get_weigh_ins`. No range limit in its code (checked 20 November 2026). Reply shape unconfirmed. |
| Daily body composition (fallback) | `/weight-service/weight/dateRange`, `startDate`, `endDate` | python-garminconnect's `get_body_composition`. Use it only if the capture shows the first one is no good. |

One request per call, whatever the range. Paths and parameter names go in `GarminDataConfig`.

Cache, as for the other day-by-day data: if the range includes today or yesterday, 15 minutes (the scale may still sync). Otherwise 30 days.

## Decisions to make

| Question | Proposed | Why |
|---|---|---|
| Which weigh-in counts for a day with several? | ✅ **The first one of the day** (owner's choice, 20 November 2026) | Morning weigh-ins are the most consistent. A weight typed in later, or one taken after a meal, won't move the trend. |
| Should `get_daily_summary` and `summary` change to match? | ✅ **Yes, the first one** (they use the latest today). Agreed with the choice above. | One rule everywhere, so a day's weight is the same in every tool and in the log |
| Most days per call | **366** | It's one request to Garmin. A year is a common question. |
| Default range | **Last 30 days** | Enough for a trend |
| Days with no weigh-in | **Left out** | Matches the sheet: "a blank means no reading, not no change" |

## Steps

1. **Plan:** this doc.
2. **Capture** (owner): `capture` also saves the range call for the last 30 days, as `weight-range.json`. ✅ Built and tested 20 November 2026, saved raw and not parsed. Still to do: the owner weighs in, then runs it. Settle the field names and grams. Fix `summary` and `get_daily_summary` if they were wrong.
3. **Client:** `GarminClient.weighIns(from, to)`, with the path in `GarminDataConfig`, the cache, and fixtures. Tests: the reply from the capture, no weigh-ins, a day with two, a range over 366 days refused with nothing sent, and 429 and 403 once with no retry.
4. **Tool and CLI:** `get_weight`, its reply and description, and `stride-mcp weight`. Tests as for the other tools, including over stdio.
5. **Docs:** the quick start, the tool table, AGENTS.md status.
6. **Live check** (owner): "how has my weight changed since September?" in Claude Code.

## Not in this plan

- Weight goals, or adding a weigh-in to Garmin
- Charts. The AI app can draw one from the reply.
