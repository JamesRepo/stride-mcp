# AGENTS.md

Guidance for AI coding agents (Claude Code, Codex, Cursor, etc.) working in this repo.

## What this project is

**stride-mcp**: an MCP server that lets AI apps read the owner's Garmin Connect data (activities, sleep, steps, weight).

- Garmin has **no personal API**. We log in the way the Garmin phone app does (unofficial), get OAuth tokens, and keep them refreshed.
- It must work with **any MCP client** (Claude, ChatGPT, Codex, etc.). Don't rely on features only one client has.

Read `docs/README.md` first. Then read the doc for whatever phase you're working on.

## Current status

- **Phase 1 (command-line login):** built and tested. The owner ran `login` and `whoami` live on 23 October 2026 and both worked. The first automatic token refresh worked live on 24 October 2026. Still to confirm: it still works days later. Plan: `docs/phases/phase-1-login.md`.
- **Phase 2 (fetch real data):** done 24 October 2026. The CLI commands `activities`, `activity`, `summary` and `sleep` (with `--no-cache`) and the reply cache are built and tested, and passed the owner's live check. Fixtures from real replies (23 October 2026) are in `src/test/resources/garmin/`. Still open: whether `--type running` also returns trail and treadmill runs. Plan and live-check results: `docs/phases/phase-2-data.md`.
- **Summary extras (25 October 2026):** `summary` also shows the night's sleep and the day's weight and body composition. Built and tested. A real capture confirmed the reply's shape for a day with no weigh-ins. Still to confirm, from a day with a weigh-in: one weigh-in's field names, and that masses are in grams.
- **Phase 3 (MCP server):** plan approved 25 October 2026. Step 2 done 25 October 2026: Spring AI 2.0.2 on Spring Boot 4.1.2, and `serve` answers over stdio with no tools yet. stdout carries only MCP messages, and logs go to `~/.stride-mcp/stride-mcp.log`. Step 3 done 25 October 2026: the 90-day limit is `GarminDataConfig.MAX_RANGE_DAYS`, shared by the CLI and the tools. Step 4 done 26 October 2026: the four tools (`mcp/GarminTools`) and their JSON replies (`mcp/ToolReplies`). Bad inputs are refused before anything reaches Garmin. `get_daily_summary` also gives the day's weight and body composition (the owner's choice). Garmin's errors still show Spring AI's default text. Next: step 5, errors. Plan: `docs/phases/phase-3-mcp.md`.
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
