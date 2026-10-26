# Phase 3: MCP server (stdio)

**Goal:** an AI app on my Mac (Claude Code, Claude Desktop, Codex CLI) starts stride-mcp and calls four tools to read my activities, one activity, a daily summary and sleep.

**Done when:** I ask Claude "how was my training this week?" and get an answer built from my real Garmin data.

_Status: plan approved 25 October 2026, with all the proposed decisions. Step 2 (dependencies and `serve`) done 25 October 2026. Step 3 (the shared 90-day limit) done 25 October 2026. Step 4 (the four tools and their replies) done 26 October 2026: see [Settled in step 4](#settled-in-step-4-26-october-2026). Next: step 5, errors._

## What changes

Same jar. Phase 2's commands stay as they are. One new command, `serve`, starts the MCP server.

```mermaid
flowchart LR
    subgraph mac["My Mac"]
        AI["AI app"] -- "starts it, then talks<br/>over stdin/stdout" --> S["java -jar stride-mcp.jar serve"]
        subgraph app["stride-mcp"]
            T["mcp/<br/>4 tools"] --> C["garmin/<br/>GarminClient + cache"] --> A["garmin/auth/<br/>GarminApi, tokens"]
        end
        S --- T
        A <--> F[("token file, cache,<br/>log file")]
    end
    A -- HTTPS --> G["Garmin"]
```

The tools only call `GarminClient`. They never see `GarminApi` or tokens (the layer rule in AGENTS.md).

## The library (checked 24 October 2026)

| Thing | Version | Notes |
|---|---|---|
| Spring AI | **2.0.2** (current stable) | Its MCP server starter is built against Spring Boot **4.1.2**. We're on 4.1.1, so we move up one patch release. |
| Spring AI 2.1.0 | RC1 only | Built against Spring Boot 4.2.0-RC1. Not for us yet. |
| Starter | `spring-ai-starter-mcp-server` | The stdio one. "No additional web dependencies" (Spring AI docs). |
| MCP Java SDK | 2.1.0, brought in by Spring AI | Uses Jackson 3 (`mcp-json-jackson3`), like the rest of the app. Also brings Reactor, and Spring AI's model module. |

Checked against the poms on Maven Central and the Spring AI 2.0.2 reference docs.

**New dependencies, and why:** the Spring AI BOM (2.0.2) and `spring-ai-starter-mcp-server`. The project plan already chose Spring AI for the MCP server. Writing the MCP protocol ourselves would be more code to keep up with a spec that changes often.

**Dependencies to drop:** `spring-boot-starter-webmvc` and `spring-boot-starter-actuator`, with their test starters. Nothing uses them (`web-application-type=none` since phase 1). Fewer libraries means a faster start, and the AI app starts the jar each session. Phase 5 (hosted) adds a web starter back.

## Two modes, one jar

```
java -jar stride-mcp.jar serve
```

| | CLI commands (`login`, `activities`…) | `serve` |
|---|---|---|
| MCP server | Off | On, over stdio |
| stdin | My paste at `login` | MCP messages from the AI app |
| stdout | The command's output | **MCP messages only** |
| Logs | Warnings only, to the console as now | To a file, never the console |
| Ends | After the command, with its exit code | When the AI app closes stdin (exit `0`). Exit `1` if it couldn't start, or couldn't read what the AI app sent, with the reason on stderr. |

`main` decides the mode before Spring starts: `serve` turns on a Spring profile, also called `serve`. Without it, the MCP server is off. This matters: the server reads stdin, and so does `login`.

`main` today exits as soon as Spring has started. In `serve` mode it waits until the transport closes, then exits `0`.

**Keeping stdout clean.** In `serve` mode:

- No banner (already off), no startup info (already off), console logging off.
- Logs go to `~/.stride-mcp/stride-mcp.log`, a `600` file. A missing folder is created as `700`; an existing one is left alone, in case it's one the owner named with `--stride.log-file`. A link or a folder at the log path is refused. No log rolling (it would create new files open to others): instead, at startup a log over 10 MB becomes `stride-mcp.log.old` and a new one starts. Warnings and errors only, plus one line per tool call (tool name, how long it took, whether it worked). Never tokens, never Garmin's replies.
- Belt and braces: the server keeps the real stdout for itself, then points `System.out` at stderr. So a stray `println` anywhere can't break the connection. AI apps keep a server's stderr as its log.

`serve` takes no options except the Spring settings (`--stride.token-file=…`, `--stride.cache-dir=…`). `whoami`, `capture` and the others don't change.

### Settled in step 2 (25 October 2026)

Answered by reading Spring AI 2.0.2's and the MCP Java SDK 2.1.0's source, then checked with tests.

| Question | Answer | What we did |
|---|---|---|
| `spring.ai.mcp.server.enabled=false` turns the server off for CLI commands | Yes, but not all of it. Spring AI's annotation scanner has its own switch. Left on, it logs warnings to the console. | CLI mode sets both `spring.ai.mcp.server.enabled=false` and `spring.ai.mcp.server.annotation-scanner.enabled=false`. A test checks that no MCP bean exists outside `serve`. |
| The stdio transport needs `spring.ai.mcp.server.stdio=true` | No. This starter always builds a stdio transport. The setting only turns off the stateless and web variants. | Set anyway, in the `serve` profile, so the intent is clear |
| Our own transport bean replaces the default one | Yes (`@ConditionalOnMissingBean`) | `mcp/StdioServer` uses the stdout that `main` kept. It also notices when the connection ends: see below. |
| `spring.ai.mcp.server.instructions` sets the server's instructions | Yes. The AI gets them in the `initialize` reply. | Set in the `serve` profile |
| A `RuntimeException` from a tool becomes an error result | Yes, but the text is the exception's message **twice**, plus its root cause's message. A checked exception becomes a protocol error instead. | Step 5: the tools catch everything themselves and build the error result, so the AI only sees our wording and a bug's details stay in the log (the plan's fallback) |

Two more things found:

- **The SDK doesn't say when stdin closes.** `StdioServer` wraps the session's transport and waits for its close. That also covers the SDK giving up on a line it can't parse, so `serve` ends instead of hanging. It watches stdin too, to tell the two apart: the first exits `0`, the second `1`.
- **Replies still being worked on when stdin closes are dropped** (by the SDK). That matches the MCP spec: for stdio, closing stdin is how the client ends the session, so it no longer expects replies. AI apps keep stdin open while they wait. Tests and scripts must too: they wait for each reply before closing stdin.

Also in the `serve` profile: only the tools capability is offered (no prompts, resources or completions), and `listChanged` is off because the tools never change while the server runs.

## The tools

Four tools, as in the project plan. All read-only. The names are the ones the AI sees.

| Tool | Inputs | Returns |
|---|---|---|
| `get_activities` | `from`, `to` (dates, optional), `type` (optional), `refresh` (optional) | Activities that started in that range, newest first |
| `get_activity` | `id` (required), `refresh` | One activity: totals, heart rate, cadence, power, training effect and laps |
| `get_daily_summary` | `date` (optional), `refresh` | One day's steps, calories, heart rate, stress, Body Battery, intensity minutes, floors, and the day's weight and body composition (added 26 October 2026) |
| `get_sleep` | `date` (optional), `refresh` | The night that ended on that morning: times, stages, score, breathing, SpO2 |

| Input | Means |
|---|---|
| A date | `YYYY-MM-DD`, in the owner's Garmin profile time zone |
| `from`, `to` | Both included. Default: the last 7 days, today included. At most 90 days. |
| `date` | Default today. For `get_sleep`, today means last night. |
| `type` | Garmin's type key: `running`, `cycling`, `strength_training`… Leave out for all types. |
| `id` | A number from `get_activities` |
| `refresh` | `true` to ask Garmin even if a recent copy is cached. Same as `--no-cache`. |

Each tool is marked read-only and not destructive, with MCP's tool hints (`readOnlyHint`, `destructiveHint`).

### What the AI reads

The AI reads the descriptions to choose a tool. So they say what comes back, what the inputs mean (with formats and units) and when to use the tool. Drafts:

> **get_activities**: Lists the owner's Garmin Connect activities (runs, rides, strength sessions, walks and others) that started between two dates, newest first. For each: id, name, type, local start time, distance in km, time, moving time, average pace per km, elevation gain in metres, average and max heart rate in bpm, and calories. Use it for questions about training over a period, such as "how was my training this week?" or "how far did I run in September?". Then use get_activity with an id for laps, cadence, power and training effect. `from` and `to` are dates like 2026-10-22, both included, covering at most 90 days (for example 2026-07-26 to 2026-10-23). Leave both out for the last 7 days. The reply also gives today's date in the owner's time zone.

> **get_activity**: One Garmin activity in detail: start time, type, distance, time and moving time, pace and fastest pace, elevation gain, heart rate, cadence, power, calories, aerobic and anaerobic training effect (0 to 5), training load, and each lap's distance, time, pace, heart rate and elevation. Fields Garmin didn't record are left out. `id` is a number from get_activities.

> **get_daily_summary**: The owner's totals for one day from Garmin: steps and step goal, distance, calories (total and active), resting, minimum and maximum heart rate, average stress (0 to 100), lowest and highest Body Battery (0 to 100), moderate and vigorous intensity minutes, and floors climbed. If the owner weighed in that day, also the latest weight in kg and the latest body composition: BMI, body fat and body water in %, muscle and bone mass in kg, visceral fat rating and metabolic age. Fields Garmin didn't record are left out. `date` is like 2026-10-22, in the owner's time zone. Leave it out for today. Today's numbers grow as the watch syncs.

> **get_sleep**: The owner's sleep for the night that ended on the morning of `date`: when it started and ended, time asleep, deep, light and REM sleep, time awake, the sleep score (0 to 100) with Garmin's label, average breathing rate and average SpO2. `date` is like 2026-10-23, which means the night of 22 to 23 October. Leave it out for last night.

`refresh` is described the same way in each tool: "Only set this when the owner says data is missing or they just synced their watch, or an activity looks unfinished (Garmin was still processing it). Replies are cached: today's and yesterday's data for 10 to 15 minutes, an activity's details for 7 days." (Reworded 26 October 2026: the first draft only gave the 10 to 15 minutes, but one activity's details are kept for 7 days, whatever its date.)

Server instructions (if step 2 confirms they work): "Garmin data for the owner of this computer. Dates are in the owner's Garmin profile time zone. Units are metric. If a tool says to run `stride-mcp login`, tell the owner to run it in a terminal: this server can't log in by itself."

### What comes back

JSON as text. Every MCP app reads text, so this works with any of them (the AGENTS.md rule). Structured output (an output schema) is left out for now: AI apps don't all support it yet (October 2026).

The JSON is shaped for an AI reader, not copied from the records:

| Rule | Why |
|---|---|
| Distances in km, elevation in metres | What people ask in. Saves the AI converting from metres. |
| Times both ways: `"duration": "36:01"` and `"durationSeconds": 2161` | The text to quote, the number to add up |
| Pace as text: `"pace": "7:08 /km"` | Turning metres per second into pace is easy to get wrong |
| Start as local time: `"start": "2026-10-22T07:06"` | The time on the clock where it happened, as in the CLI |
| Missing values left out, not `null` | Less noise. The description says fields may be missing. |
| Each reply says `today` and `timeZone` | An AI often doesn't know today's date, or whose "today" it is |
| No data is not an error | An empty list, or `"recorded": false` for sleep and daily summary |
| Compact JSON, fields in a fixed order (`today` first) | An AI reads it as well without the spaces, in fewer tokens |

For example (numbers from the live check of 24 October 2026; `GarminToolsTest` pins each reply):

```json
{
  "today": "2026-10-24",
  "timeZone": "Europe/London",
  "from": "2026-10-18",
  "to": "2026-10-24",
  "type": "running",
  "count": 3,
  "activities": [
    {
      "id": 24810777073,
      "name": "Islington Running",
      "type": "running",
      "start": "2026-10-22T07:06",
      "distanceKm": 5.05,
      "duration": "36:01",
      "durationSeconds": 2161,
      "movingDuration": "35:51",
      "movingDurationSeconds": 2151,
      "pace": "7:08 /km",
      "elevationGainMetres": 18,
      "averageHeartRateBpm": 143,
      "maxHeartRateBpm": 169,
      "kilocalories": 448
    }
  ]
}
```

These reply records live in `mcp/`. The Garmin records from phase 2 don't change.

### Garmin's `0` for missing values

As in the CLI: Garmin sends `0` for the distance, speed and moving time of activities without them (strength training, indoor cycling). The tools leave those out, so the AI doesn't read "0 km" as a value.

### Settled in step 4 (26 October 2026)

How Spring AI 2.0.2 registers tools, found by reading its source and the MCP Java SDK 2.1.0's, then checked with tests.

| Question | Answer | What we did |
|---|---|---|
| How are tools found? | With the annotation scanner on, any Spring bean with `@McpTool` methods. One tool per method. Inputs are the method's parameters, by name. | `GarminTools`, a bean in `McpBeans` (serve mode only) |
| How are the hints set? | `@McpTool(annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false))`. Without it, `destructiveHint` defaults to **true**. | Set on all four. `openWorldHint` stays true (Garmin is outside this computer). |
| Required or optional inputs? | Every parameter is required unless `@McpToolParam(required = false)` | Only `get_activity`'s `id` is required |
| Is the input schema checked? | Yes, **by the SDK**, before the tool runs. A missing `id` or a wrong type comes back as an error result in the SDK's words: `Tool (get_activity) input validation failed: Validation failed: JSON schema validation errors: [: required property 'id' not found]`. Unknown inputs pass (the schema allows them). | Our own checks cover the rest: dates, the range, `id` above 0, and unknown inputs (the tools take the raw request to see them) |
| What does a returned `String` become? | One text content, as it is | The tools return a `CallToolResult`, so a bad input can be an error result with our own message |
| Output schema? | Only with `generateOutputSchema = true` | Left off, as planned |

Also settled:

- **Weight in `get_daily_summary`** (owner's choice, 26 October 2026). The day's latest weight and latest body composition, picked as the CLI's `summary` does. The CLI and the tools share the picking (`garmin/DayWeight`). If the weigh-ins can't be read, the totals still come back, with `weightLeftOut` saying why. Sleep stays in `get_sleep`. The weigh-in field names and grams are still unconfirmed from a real weigh-in (see AGENTS.md).
- **Defaults when only one end is given:** only `to` means the 7 days ending then. Only `from` means from then to today.
- **Blank inputs** (`""`) are taken as left out. Some AI apps send them for "no value".
- **`type`** is trimmed and lower-cased (Garmin's keys are lower case).
- **One log line per call** at INFO: `get_sleep refused its input in 3 ms`, `get_activities worked in 412 ms`, or `… failed in … ms: RateLimitedException` for Garmin's errors. No inputs, no replies. A bug (any other exception) is logged with its stack trace: the log is the only place to find it.
- **Shared with the CLI, in `garmin/`:** the default range (`GarminDataConfig.DEFAULT_RANGE_DAYS`, 7), "a day with no data" (`DailySummary.isEmpty()`, which also sets its cache lifetime), and how durations, pace and Garmin's labels read (`Units`). The tool descriptions are built from `MAX_RANGE_DAYS` and `DEFAULT_RANGE_DAYS`. A test checks that the `refresh` text matches the cache lifetimes.
- **Not done:** fetching the daily summary and the weigh-ins at the same time. It would save one round trip, only when nothing is cached, and sends Garmin two requests at once. Spreading out requests is phase 4.

What step 5 must change: the tools still throw Garmin's errors and bugs, so Spring AI shows its default text (the message twice). Step 5 catches them in `GarminTools.call`, words the ones that mention CLI commands for the AI (for example the 404's "Check the id in `stride-mcp activities`"), and decides whether to keep the SDK's wording for a missing or wrongly typed `id` or replace it.

## Errors

The AI sees an error result with a message. It usually passes it on to me. Phase 2's messages already say what to do next, so the tools reuse them.

| Situation | The AI sees |
|---|---|
| Not logged in, session expired | "Not logged in. Run `stride-mcp login`." The server can't log in itself: the browser sign-in and paste need a terminal. After a login in the terminal, the next tool call works, with no restart. (Tokens are read from the file on each call.) |
| 403, 429 | Phase 1's "wait a while" messages. The description doesn't invite retries. stride-mcp never retries these. |
| Bad date, `from` after `to`, more than 90 days, unknown input | "`date` isn't a date. Dates look like 2026-10-22.", "The range can be at most 90 days. …", "Unknown input `days`. …" No data is asked for. (Done in step 4.) Only one check needs Garmin: with `from` but no `to`, `to` is today, and today needs the profile's time zone. That's the lookup every call makes, kept for 7 days. |
| Missing `id`, or a wrong type | For now the SDK's own words (see [Settled in step 4](#settled-in-step-4-26-october-2026)). Nothing is sent to Garmin. |
| No activity with that id | "No activity with id 123. Check the id with get_activities." |
| Garmin wouldn't share the summary, reply not as expected, more than 200 activities | Phase 2's messages |
| Anything else (a bug) | "stride-mcp hit an unexpected error. Details are in ~/.stride-mcp/stride-mcp.log." The details go to the log, never to the AI. |

A few phase 2 messages mention CLI commands, for example "Check the id in `stride-mcp activities`". The tools word those for the AI instead. Messages that say "Run `stride-mcp login`" stay as they are: that's what I'd do in either case.

## A process that keeps running

The CLI runs for a second. The server runs as long as the AI app does. What that changes:

| Thing | Choice |
|---|---|
| "Today" | Worked out on each call (already: `GarminClient.today()` reads the clock each time) |
| Tokens | Loaded, refreshed and saved under the token file's lock on each call (already). The CLI and the server can run at once. |
| Cache | Disk files, read on each call, as in phase 2. No memory cache yet: the files are small and local. Phase 4 adds one if it's needed. |
| `displayName` and time zone | Remembered in memory for 7 days, as in phase 2 |
| Switching Garmin account | `logout`, `login`, then restart the AI app, so the server forgets the old account's `displayName` |
| Two tool calls at once | Possible. `GarminClient`'s memory is an `AtomicReference`, the token store is locked, and cache writes are atomic. A test runs calls side by side to be sure. |

## Shared rules, one place

The CLI and the tools check the same things. Neither layer may depend on the other, so the shared parts move down into `garmin/`:

- The 90-day limit moves from `cli/CommandLine` to `GarminDataConfig.MAX_RANGE_DAYS`. It's a constant, not a record field: it's our rule, not a Garmin detail, and the CLI reads it from static code.
- Each layer still reads its own input: `--days 7` on the command line, `from` and `to` in the tools.
- `GarminClient.activities` also refuses `from` after `to`, or more than 90 days, before sending anything. It's a safety net under both layers, so its message is plain (`IllegalArgumentException`). Users see the CLI's or the tool's own message.

## Code layout

```
com.hammez.stride
├── StrideMcpApplication   picks CLI or serve mode, waits for the server in serve mode
├── cli/                   unchanged, except: no MCP server outside serve, 90-day limit from GarminDataConfig
├── mcp/
│   ├── GarminTools        the four tools: inputs checked, then GarminClient
│   ├── ToolReplies        records into the JSON shapes above (km, pace, local times)
│   ├── ActivitiesReply, ActivityReply, DayReply, SleepReply
│   ├── McpBeans           serve mode: the transport bean
│   ├── StdioServer        the SDK's stdio transport on the kept stdout, and a wait until it closes
│   ├── McpStreams         stdin and the kept stdout, handed over by main
│   └── PrivateLogFile     gets the log file ready (600, no links, kept small) before logging opens it
├── garmin/                unchanged, plus the 90-day limit in GarminDataConfig, and DayWeight (the weigh-ins the CLI and tools show), Units (durations, pace, labels), DEFAULT_RANGE_DAYS, DailySummary.isEmpty()
└── garmin/auth/           unchanged
src/main/resources/
├── application.properties         + MCP server off
├── application-serve.properties   MCP server on (stdio), the log file
└── logback-serve.xml              serve mode's logging: one file, appended to, no console
```

## Tests

Against `FakeGarmin` and the phase 2 fixtures, as before. No real Garmin calls.

| Area | Cases |
|---|---|
| Tools | Each tool's JSON from the fixtures (pinned, like `DataPrinterTest`). Defaults: last 7 days, today, last night. `type` and `refresh` passed on. Garmin's `0`s and missing values left out. `today` and `timeZone` in each reply. |
| Inputs | Bad date, `from` after `to`, 91 days, missing `id`. Each one an error, with nothing sent to Garmin. |
| Errors | Not logged in, session expired, 404, 429, `privacyProtected`, unexpected reply, an unexpected bug (message points to the log, with no details). No token in any reply or log line. |
| Server over stdio | Start the server with piped streams, send `initialize`, `tools/list` and `tools/call`. Check the four tools, their descriptions and hints, and a real reply. **Every line on stdout is a JSON-RPC message.** A `println` from a tool doesn't reach stdout. |
| Modes | CLI commands don't start the MCP server, and `login` still reads the paste. `serve` ends when stdin closes. |
| Side by side | Several tool calls at once give the same answers as one at a time |

## What I should see

At the live check:

| I do | I should see |
|---|---|
| Ask before logging in | The AI says to run `stride-mcp login` in a terminal |
| Log in, ask again | A real answer, with no restart |
| Ask twice within 10 minutes | The second answer comes from the cache (no new files in the cache folder) |
| Say "I just synced, check again" | The AI calls with `refresh: true` |

## Steps

1. ✅ **Plan:** this doc. Drafted 24 October 2026, approved 25 October 2026.
2. ✅ **Dependencies and `serve`:** Spring Boot 4.1.2, Spring AI 2.0.2, webmvc and actuator dropped. `serve` mode with the server off elsewhere, stdout kept clean, logs to a file, waits until stdin closes. Settled: see [Settled in step 2](#settled-in-step-2-25-october-2026). Done 25 October 2026. Tests: a server with no tools answers `initialize` and `tools/list`, and stdout carries only JSON-RPC.
3. ✅ **Shared rules:** the 90-day limit into `GarminDataConfig.MAX_RANGE_DAYS`, used by the CLI's check and usage text. `GarminClient.activities` refuses a bad range as a safety net. Done 25 October 2026. Tests: the CLI's wording is unchanged, and the client refuses 91 days or `from` after `to` with nothing sent to Garmin.
4. ✅ **The tools and their replies** in `mcp/`, with descriptions. Settled: see [Settled in step 4](#settled-in-step-4-26-october-2026). Done 26 October 2026. Tests: each tool's JSON from the fixtures, the defaults, `type` and `refresh` passed on, Garmin's `0`s and missing values left out, each bad input refused with nothing sent to Garmin, and the four tools with their descriptions and hints in `tools/list` over stdio.
5. **Errors** as tool errors, built by the tools themselves (not Spring AI's default text), with CLI wording replaced where needed. Tests for each row of [Errors](#errors).
6. **End to end over stdio:** the piped-stream test, and the side-by-side test.
7. **Docs:** [Running it](../running.md) gets the `serve` command. The quick start gets a short "Use it from an AI app" section. AGENTS.md status.
8. **Live check** (owner): add stride-mcp to Claude Code (`claude mcp add stride -- java -jar …/stride-mcp.jar serve`), ask "how was my training this week?", then the questions in [What I should see](#what-i-should-see). Then the same in Codex CLI, since it must work with any MCP app.

## Not in phase 3

- Local HTTP or hosted (phase 5)
- New kinds of data: steps detail, weight history, HRV, training status (phase 4). One day's weight is in `get_daily_summary` since step 4.
- Prompts or resources, such as a "weekly review" prompt (later, maybe)
- Structured output schemas (when AI apps support them more widely)
- A memory cache in front of the files (phase 4, if needed)
- Retries, or spreading out requests (phase 4)
- Writing anything to Garmin

## Decisions (approved 25 October 2026)

| Question | Decision | Other option |
|---|---|---|
| Command name | `serve` (`stride-mcp serve` reads well) | `mcp` |
| Spring AI version | 2.0.2, with Spring Boot 4.1.2 | Wait for 2.1.0 and Boot 4.2 |
| Unused web and actuator starters | Drop them now | Keep them until phase 5 |
| Activities input | `from` and `to` dates (the AI often has dates in mind) | `days`, as in the CLI |
| Reply format | JSON as text, shaped for the AI (km, pace, both kinds of time) | The CLI's printed text, or the raw records |
| `refresh` input | Yes, described so the AI uses it only when asked | No: the cache lifetimes are short enough |
| Log file | `~/.stride-mcp/stride-mcp.log`, inside the `700` folder | A `logs/` folder of its own |

## Credit

Spring AI's MCP server starter and the MCP Java SDK do the protocol work. Versions and settings are from Maven Central and the Spring AI 2.0.2 reference docs, checked 24 October 2026.
