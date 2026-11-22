# AGENTS.md

Guidance for AI coding agents (Claude Code, Codex, Cursor, etc.) working in this repo.

## What this project is

**stride-mcp**: an MCP server that lets AI apps read the owner's Garmin Connect data (activities, sleep, steps, weight).

- Garmin has **no personal API**. We log in the way the Garmin phone app does (unofficial), get OAuth tokens, and keep them refreshed.
- It must work with **any MCP client** (Claude, ChatGPT, Codex, etc.). Don't rely on features only one client has.

Read `docs/README.md` first. Then read the doc for whatever phase you're working on.

## Current status

- **Phase 1 (command-line login):** built and tested. The owner ran `login` and `whoami` live on 23 October 2026 and both worked. The first automatic token refresh worked live on 24 October 2026. On 22 November 2026, `whoami` refreshed by itself again and the session moved on to 22 December, but the owner may have logged in again since 23 October. So still to confirm: it still works days later with no new login. Plan: `docs/phases/phase-1-login.md`.
- **Phase 2 (fetch real data):** done 24 October 2026. The CLI commands `activities`, `activity`, `summary` and `sleep` (with `--no-cache`) and the reply cache are built and tested, and passed the owner's live check. Fixtures from real replies (23 October 2026) are in `src/test/resources/garmin/`. Still open: whether `--type running` also returns trail and treadmill runs. Garmin's list of activity types (20 November 2026) puts them under `running`, but doesn't say what the search filter does. Plan and live-check results: `docs/phases/phase-2-data.md`.
- **Summary extras (25 October 2026):** `summary` also shows the night's sleep and the day's weight and body composition. Built and tested. A real capture confirmed the reply's shape for a day with no weigh-ins. Confirmed 20 November 2026: one weigh-in's field names, and that masses are in grams.
- **Phase 3 (MCP server):** plan approved 25 October 2026. Step 2 done 25 October 2026: Spring AI 2.0.2 on Spring Boot 4.1.2, and `serve` answers over stdio with no tools yet. stdout carries only MCP messages, and logs go to `~/.stride-mcp/stride-mcp.log`. Step 3 done 25 October 2026: the 90-day limit is `GarminDataConfig.MAX_RANGE_DAYS`, shared by the CLI and the tools. Step 4 done 26 October 2026: the four tools (`mcp/GarminTools`) and their JSON replies (`mcp/ToolReplies`). Bad inputs are refused before anything reaches Garmin. `get_daily_summary` also gives the day's weight and body composition (the owner's choice). Step 5 done 29 October 2026: every error is an error result in our own words, said once, built in `GarminTools.call`. A bug's details only go to the log. A missing or wrongly typed `id` keeps the MCP SDK's wording, and an unusable cache is only logged, once per run (the owner's choices). Garmin's error replies never reach the AI or the log: `GarminException.garminReply()` keeps them apart, for the CLI. Step 6 done 30 October 2026: a `tools/call` over stdio gets a real reply from FakeGarmin, and a `println` during a call goes to stderr. Tool calls run at the same time (MCP SDK 2.1.0, as Spring AI 2.0.2 sets it up without a web server). Calls at once share one token check and one profile lookup, and its error if it fails, through `concurrent/SingleFlight`. So a 429 reaches Garmin once, not once per waiting call. The stdio tests reach FakeGarmin through a test-only `@Primary` config (`FakeGarminServe`), with no production setting for Garmin's addresses. Step 7 done 31 October 2026: `docs/running.md` says how to add `serve` to Claude Code, Claude Desktop and Codex CLI, with the full path to Java 25 (the owner's choice: the apps may not find the right `java`), and what to do when it goes wrong. The quick start has "Use it from an AI app" and the four tools. Next: step 8, the owner's live check. Plan: `docs/phases/phase-3-mcp.md`.
- **Phase 4 (fill in the health log):** three plans approved 20 November 2026, with all their decisions: weight history (`docs/phases/phase-4a-weight.md`), a daily log tool (`phase-4b-daily-log.md`) and catching up the log (`phase-4c-catch-up.md`). Built in that order. The log is the owner's Google Sheet. The AI app writes it, and stride-mcp never does. A day's weight is its first weigh-in, everywhere (the owner's choice), and its body composition the first weigh-in with a scale reading (the owner's choice, 20 November 2026; a typed-in weight has only a BMI, which doesn't count), both in `garmin/DayWeight`. Step 2 of 4a and 4b, code done 20 November 2026: `capture` also saves four replies, raw and unparsed, all seen in the owner's capture of 20 November 2026: `weight-range.json` (30 days), `summary-range.json` and `sleep-range.json` (28 days) and `activity-types.json`. One of these failing is saved and named, the rest still run, and capture exits 1. A 403 or 429 still stops everything. The owner's capture on 20 November 2026 confirmed a weigh-in's field names and that masses are in grams, so nothing needed fixing. Step 3 of 4a done 20 November 2026: `GarminClient.weighIns(from, to)`, at most `GarminDataConfig.MAX_WEIGHT_RANGE_DAYS` (367, so a year to the same date fits even across 29 February), with days outside the range left out and older ranges cached for a day, with fixtures `weight-today.json` and `weight-range.json`. Steps 4 and 5 of 4a done 20 November 2026: the `get_weight` tool (one entry per day with a weigh-in, plus a summary built by `garmin/WeightHistory`) and `stride-mcp weight [from] [to]`, 30 days by default (`GarminDataConfig.DEFAULT_WEIGHT_RANGE_DAYS`), and `summary` and `get_daily_summary` now use the first weigh-in. Step 6 done 21 November 2026: the owner's live check in Claude Code matched the CLI, so **4a is done**. One weigh-in matched the Garmin Connect website in every value, bone mass included. 4b step 2 done 21 November 2026: the summary range, sleep range and activity types are stripped into fixtures (`summary-range.json` and `sleep-range.json`, 5 of 28 days, and `activity-types.json`, whole) and settled in the plan's "What the capture showed". Both range replies have every day, oldest first, and match the one-day replies, but with new field names, so each needs its own parser. So `get_daily_log` uses them, with no one-day fallback: about five requests for 28 days. `parentTypeId` 17 is `all`, the root, so `group` is the top-level type below it (`trail_running` → `running`). The reply also gives `caloriesEaten`, from the steps reply's `calories.consumedValue`, left out when missing or 0 (the owner's choice, 21 November 2026). On 21 November 2026 it matched the sheet's Calories column (from MyFitnessPal) on all 12 days that have both, to within 1 kcal. So 4c fills that column from it, blank cells only (the owner's choice, 21 November 2026). 4b step 3 done 21 November 2026: `GarminClient.dailyLog(from, to)`, a `LogDay` for every day, oldest first, from `dailyTotals` (steps and `caloriesEaten`) and `sleep(from, to)` (each with its own parser), the weigh-ins as 4a reads them, and the activities, grouped by the day they started in the owner's time zone (searched a day wider at each end, for travel), each with its `group` from `activityTypes()`. The types are cached 30 days, even with `refresh`, and only asked for when there are activities. A type missing from the cached list asks for it again, once per type per run. A day Garmin marks `privacyProtected` is refused. Yesterday was `isFinal` (the owner's choice), until the live check below changed it. At most `GarminDataConfig.MAX_LOG_RANGE_DAYS` (28), refused before anything is sent. The range lifetime is now `CacheLifetimes.olderRange`. 4b step 4 done 22 November 2026: the `get_daily_log` tool (reply `mcp/LogReply`) and `stride-mcp log [from] [to]`. No dates means yesterday, only `to` that one day, only `from` up to yesterday (or just today if `from` is today), and no day after today (the owner's choices, 22 November 2026), all in `garmin/LogRange`, shared by both. The default is `GarminDataConfig.DEFAULT_LOG_RANGE_DAYS` (1). Hours and minutes to 1 decimal place come from `Units.decimalHours` and `decimalMinutes`. 4b step 5 done 22 November 2026: the quick start has them in its tables, and a "Daily log" example that `DataCommandsTest.logAsTheQuickStartShowsIt` pins. 4b step 6 done 22 November 2026: the owner's live check in Codex and Claude Code passed, so **4b is done**. It found that a day the watch hadn't synced was still `final` (Friday had 95 steps). The owner chose: a day before today is `final` only once the watch has synced since that day ended. Built 22 November 2026: a past day is final if its `wellnessChronology` (in the steps range reply, `DayTotals.watchDataUntil`) runs to midnight, or a later day in the reply has watch data, with no extra request (the owner's choice). One that isn't has `watchDataUntil` (`"08:42"` or `"none"`) in the reply, and a line in `stride-mcp log`. While a range has such a day, its steps, sleep and activities are cached only 10 to 15 minutes. After review (22 November 2026, the owner's choices): watch data needs a steps value too, as a chronology might come from MyFitnessPal alone (a capture to check it is the owner's, see the plan); if the last day's watch data stops early, the next day's steps are asked for, only then; fresh steps mean fresh sleep and activities; the log's activity searches have their own cache files; and `dailyLog` decides each day's `LogDay.State`. Next: 4c.
- `spike/` holds throwaway experiments, run as single-file Java (`java spike/GarminLoginSpike.java probe`). It's not part of the build. Don't build on it.
- Later phases are outlined in `docs/project-plan.md`. Don't start them unless asked.

## Stack

- Java 25, Spring Boot 4.1 (Spring Framework 7, Jackson 3)
- Maven via the wrapper (`./mvnw`). Don't assume a global `mvn`.
- Spring AI 2.0.2 MCP server starter (`spring-ai-starter-mcp-server`), stdio
- HTTP to Garmin: the JDK's `java.net.http.HttpClient`. Don't add an HTTP library without a reason.

## Commands

| Task | Command |
|---|---|
| Build and run all tests | `./mvnw verify` |
| Run tests | `./mvnw test` |
| Run one test class | `./mvnw test -Dtest=ClassName` |
| Build the jar | `./mvnw package` |

Run `./mvnw verify` before saying a change is done.

## Layout

```
src/main/java/com/hammez/stride/
├── cli/            login / whoami / logout commands      (phase 1)
├── garmin/auth/    Garmin login, token store, refresh    (phase 1)
├── garmin/         Garmin data client and cache          (phase 2)
├── files/          private folders: 700/600, atomic writes (shared by all layers)
├── concurrent/     SingleFlight: calls at once share one attempt (shared by all layers)
└── mcp/            MCP tools                             (phase 3)
docs/               plain-language project docs
spike/              throwaway experiments, not built
```

Keep the layers separate: **MCP tools → Garmin client → auth.** Tools never call Garmin directly, and nothing outside `garmin/auth` touches tokens.

## Hard rules

### Never trigger a real Garmin login
- **Don't run `login`, the spike's `login`/`browser` modes, or anything that uses a real ticket or token.** Only the human runs those. If the human pastes a ticket or token into the chat, don't use it. Ask them to paste it into their terminal instead.
- Login is **browser-based** (see `docs/phases/phase-1-login.md`). Don't reintroduce a password login: Garmin returns HTTP 429 to non-browser clients based on their connection fingerprint.
- The credential-free `probe` mode still hits Garmin's login endpoint and counts toward rate limits. Ask before running it.
- Tests must use a **fake HTTP server**, never real Garmin endpoints.

### Never leak secrets
- Never print, log, or include in exception messages: service tickets (`ST-…`), access tokens or refresh tokens. Error bodies from Garmin are fine to show if they contain none of these.
- stride-mcp never handles the Garmin password. Don't add any code that asks for it or stores it.
- The token file (`~/.stride-mcp/garmin_tokens.json`) and the cache (`~/.stride-mcp/cache/`) must stay `600` inside a `700` folder, and be written atomically (temp file, then move). Use `files.PrivateFolder` for this; don't write another copy.
- Never commit tokens, `.env` files, or `application-local.*` (already gitignored).

### Be gentle with Garmin
- No retry loops on login or token calls. On 403 or 429: stop and show a clear message.
- Refresh tokens instead of logging in again. Cache data instead of re-fetching it.

### Keep Garmin's changing details in one place
- URLs, client IDs, user agents and headers live in `GarminAuthConfig` (or the equivalent config class for data endpoints). Never hard-code them elsewhere.
- The token swap and refresh follow [python-garminconnect](https://github.com/cyberjunky/python-garminconnect) (MIT). When Garmin breaks something, check that project's recent changes first. Re-implement, don't copy code.

### stdio safety (phase 3 onwards)
- In stdio mode, **stdout is the MCP channel**. Nothing else may write to it: no `System.out`, no Spring banner, no console logging. Logs go to a file.

## Code style

- Match the surrounding code. Plain, readable Java. Use records for data, and constructor injection.
- Clear, specific exceptions (for example "not logged in", "session expired", "rate limited") whose messages tell the user what to do next.
- Write MCP tool descriptions for an AI reader: say what the tool returns, what the inputs mean (with units and date formats), and when to use it.
- Add tests with each change. Cover the failure paths (bad paste, rejected ticket, unknown client IDs, 429, expired refresh token), not just success.
- No new dependencies without saying why.

## Docs

- Docs in `docs/` are for a reader new to MCP. Use plain language, short sentences, tables and Mermaid diagrams. Not too many words.
- When a decision or finding changes the plan (for example a Garmin behaviour we discover), update the relevant doc in the same change.
- Date anything about Garmin's or AI apps' behaviour ("October 2026"). It changes often.

## Git

- Commit only when asked. Write short, plain commit messages.
- `spike/` is uncommitted scratch work unless the owner says otherwise.
