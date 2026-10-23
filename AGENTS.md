# AGENTS.md

Guidance for AI coding agents (Claude Code, Codex, Cursor, etc.) working in this repo.

## What this project is

**stride-mcp**: an MCP server that lets AI apps read the owner's Garmin Connect data (activities, sleep, steps, weight).

- Garmin has **no personal API**. We log in the way the Garmin phone app does (unofficial), get OAuth tokens, and keep them refreshed.
- It must work with **any MCP client** (Claude, ChatGPT, Codex, etc.). Don't rely on features only one client has.

Read `docs/README.md` first. Then read the doc for whatever phase you're working on.

## Current status

- **Phase 1 (command-line login):** steps 2–7 built and tested (October 2026). Step 8, the live check by the owner, is still to do. Plan: `docs/phases/phase-1-login.md`.
- `spike/` holds throwaway experiments, run as single-file Java (`java spike/GarminLoginSpike.java probe`). It's not part of the build. Don't build on it.
- Later phases are outlined in `docs/project-plan.md`. Don't start them unless asked.

## Stack

- Java 25, Spring Boot 4.1 (Spring Framework 7, Jackson 3)
- Maven via the wrapper (`./mvnw`). Don't assume a global `mvn`.
- Planned: Spring AI 2.x MCP server starter (phase 3)
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
├── garmin/         Garmin data client                    (phase 2)
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
- The token file (`~/.stride-mcp/garmin_tokens.json`) must stay `600` inside a `700` folder, and be written atomically (temp file, then move).
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
