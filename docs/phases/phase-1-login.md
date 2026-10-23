# Phase 1: Log in from the command line

**Goal:** log in to Garmin once, save the tokens, and keep them working without logging in again.

**Done when:**

- `login` works, including MFA
- `whoami` still works days later, with no new login
- stride-mcp never sees my password

## The commands

All three run from the same jar that will later be the MCP server.

| Command | What it does |
|---|---|
| `stride-mcp login` | Opens Garmin's sign-in page in my browser. I log in there and paste back the address I end up on. Saves tokens. |
| `stride-mcp whoami` | Uses the saved tokens to fetch my Garmin profile. Refreshes them if needed. |
| `stride-mcp logout` | Deletes the saved token file. |

## Running it

```
./mvnw package
java -jar target/stride-mcp.jar login     # or whoami, logout
```

Tip: `alias stride-mcp='java -jar ~/Development/source/stride-mcp/target/stride-mcp.jar'` makes the commands below work as written.

| Option | What it does |
|---|---|
| `--stride.token-file=/some/path.json` | Use a different token file (default `~/.stride-mcp/garmin_tokens.json`) |

Exit codes: `0` worked, `1` failed (the message says why), `2` wrong usage.

## How the login works

I log in with **my own browser**, on Garmin's real sign-in page. stride-mcp only receives a single-use **ticket**, then swaps it for tokens.

```mermaid
sequenceDiagram
    actor Me
    participant B as My browser
    participant S as stride-mcp
    participant SSO as sso.garmin.com
    participant DI as diauth.garmin.com
    participant API as connectapi.garmin.com
    S->>B: 1. open Garmin sign-in page
    Me->>B: log in (and MFA)
    SSO-->>B: redirect to the phone-app address, with ?ticket=ST-…
    Note over B: page can't load (that's expected)
    Me->>S: 2. paste the address
    S->>DI: 3. swap ticket for tokens
    DI-->>S: access token + refresh token
    S->>API: 4. check: fetch my profile
    API-->>S: OK, so save tokens
```

Later, when the access token is about to expire:

```mermaid
sequenceDiagram
    participant S as stride-mcp
    participant DI as diauth.garmin.com
    S->>DI: refresh token
    DI-->>S: new access token + new refresh token
    Note over S: save straight away
```

### What the browser sees

1. stride-mcp opens `https://sso.garmin.com/sso/login?service=https://mobile.integration.garmin.com/gcm/ios`. This is Garmin's classic sign-in form, and Garmin's MFA works on it as normal.
2. After login, Garmin redirects to `mobile.integration.garmin.com/gcm/ios?ticket=ST-…`. That address normally opens the Garmin phone app, so a browser can't load it. Safari shows "This Connection Is Not Secure". **Don't click Continue.** Just copy the address.
3. If the browser is already signed in to Garmin, step 1 skips the form and redirects straight away.

## Why the browser, not a password prompt

We first tried sending email and password straight to Garmin's phone-app login, the way python-garminconnect does. It failed:

| Finding (October 2026) | Detail |
|---|---|
| Java gets through Cloudflare | A fake login got a proper "wrong password" reply, not a bot challenge |
| …but Garmin then rate-limits | HTTP 429 after 2 attempts. Still 429 an hour later. |
| It's not about attempts | Garmin rate-limits based on the **client's connection fingerprint**, even from fresh networks ([python-garminconnect #449](https://github.com/cyberjunky/python-garminconnect/pull/449), [#453](https://github.com/cyberjunky/python-garminconnect/issues/453)). Python gets around it by imitating a browser's connection. Java can't. |

A real browser avoids all of that, and handles MFA and CAPTCHAs for free. As a bonus, stride-mcp never sees my password.

Two things that don't work in a browser:

- **The phone-app sign-in page** (`/mobile/sso/en_US/sign-in`) hangs on `/portal/sso/embed`, waiting for the phone app to take over.
- **Normal Garmin website sign-in**: the website uses up the ticket immediately, and tickets only work once.

## Token facts (measured October 2026)

| | Lifetime | Notes |
|---|---|---|
| **Access token** | ~21 hours | A JWT, so we can read its expiry ourselves. Refresh 15 minutes before it runs out. |
| **Refresh token** | 30 days | Each refresh returns a **new** refresh token. If a reply doesn't give its lifetime, we save "unknown" and let Garmin decide. |
| **Ticket** | At least several minutes, if unused | Single use. No need to rush the paste. |

**So:** if stride-mcp refreshes at least once every 30 days, I should never need to log in again. Phase 1's live check confirms this over time.

**Other details:**

- Garmin uses several app "client IDs" for the token swap, and they change over time. We try a short list in order (`…_DI_2025Q2` worked first time), and keep the list in one place.
- The refresh must use the client ID inside the access token, not just whichever one we tried first.
- The profile's `displayName` is an ID number, not my name. `whoami` shows `fullName`, then `userName`, then `displayName`, whichever exists first. These field names come from python-garminconnect. **To confirm at the live check.**

### How we read the token swap's errors (assumed, October 2026)

We haven't yet seen Garmin's real replies to a bad ticket or a bad client ID. Until the live check confirms them, we follow the OAuth standard:

| Garmin replies | We assume | Ticket swap | Refresh |
|---|---|---|---|
| `invalid_grant` | The ticket or refresh token is bad | Stop: "didn't accept that ticket" | Stop: "Session expired" |
| `invalid_client`, `unauthorized_client` | This client ID is no longer accepted | Try the next client ID | Stop: "app ID… check for an update" |
| 400 / 401 with no error code | Probably a bad ticket or refresh token | Stop: "rejected… run `login` again" | Stop: "Session expired" |
| 400 / 401 with any other code | Unknown | Stop: "rejected… run `login` again" | Same |
| 403 or 429 | Blocked or rate limited | Stop at once | Same |
| 5xx or anything else | Garmin problem | Stop, and show the (redacted) reply | Same |

Each client ID is tried **once**, and only an explicit client-ID error moves on to the next. A bad ticket is never sent more than once. There are no retries.

If the profile call returns 401 while the access token still looks valid (revoked early, or our clock is off), `whoami` refreshes **once** and tries again. A second 401 means "Session expired".

## Code layout

```
com.hammez.stride
├── cli/          login, whoami, logout commands
└── garmin/auth/
    ├── BrowserLogin         opens sign-in page, reads pasted address → ticket
    ├── DiTokenClient        ticket → tokens, and refresh
    ├── TokenStore           read/write the token file safely
    ├── TokenManager         "give me a valid access token", refreshing if needed
    └── GarminAuthConfig     URLs, client IDs, headers (the bits Garmin changes)
```

Also in `garmin/auth/`: `ProfileClient` (the profile check), `GarminApi` (signed-in requests, added in [phase 2](phase-2-data.md)), `GarminAuth` (what the commands call, so tickets and tokens never leave `garmin/auth`) and `Redactor` (strips tickets and tokens from Garmin error text).

**Tools:** Java's built-in HTTP client and Jackson 3 (already part of Spring Boot). No new libraries.

The jar won't start a web server in phase 1. The commands just run and exit. This is set by `spring.main.web-application-type=none` in `application.properties`. The web dependencies stay in the pom for phase 5, and the banner and console logs are off.

## Where tokens are saved

`~/.stride-mcp/garmin_tokens.json`

| Rule | Why |
|---|---|
| Folder readable only by me (`700`), file too (`600`) | The refresh token gives full account access |
| Write to a temp file, then swap it in | A crash mid-write can't corrupt the tokens |
| Never follow symlinks for the folder or file | Nothing can redirect the tokens somewhere else |
| Looser permissions are tightened when the file is read | In case something changed them |
| Load, refresh and save happen under a lock (`garmin_tokens.json.lock`) | Refresh tokens are single use. Two stride-mcp processes refreshing at once would lose the session. |
| Contains: access token, refresh token, client ID, expiry times | Nothing else. No password, no email. |

Moving this to the macOS Keychain is a possible future improvement. `TokenStore` is the only class that would change.

## Errors I should see

| Situation | Message (roughly) |
|---|---|
| Pasted text has no ticket | "No ticket found. Copy the full address from the browser's address bar." |
| Ticket rejected (expired or already used) | "Garmin didn't accept that ticket. Run `login` again." |
| All client IDs rejected | "Garmin rejected the token swap. Its app IDs may have changed. Check for an update." |
| Rate limited (429) | "Garmin is rate limiting. Wait before trying again." |
| Blocked (403) | "Garmin refused the request (HTTP 403). It may be blocking this connection. Wait…" |
| No token file | "Not logged in. Run `stride-mcp login`." |
| Refresh token rejected or expired | "Session expired. Run `stride-mcp login`." |
| Token file corrupt | "Token file … is unreadable. Run `stride-mcp logout`, then `stride-mcp login`." |
| Token file or folder is a symlink | "… is a link …". stride-mcp refuses to use it. |
| Token folder has no Unix permissions (FAT, exFAT, some network drives) | "Can't keep tokens private… Use a folder on your main disk." |
| Refresh turned down because of the app ID | "Garmin no longer accepts the app ID your saved session uses… Check for an update" |
| Ticket swap or refresh rejected for an unknown reason | "Garmin rejected … (HTTP 400). Run `stride-mcp login` again…" |
| Garmin's reply has no token or expiry | "Its login may have changed. Check for an update." |

Tickets and tokens are never printed in messages or logs.

## Steps

1. ~~**Cloudflare test**~~: done (see above). Led to the browser login.
2. ✅ **Project setup:** command handling (`login`, `whoami`, `logout`) and no web server for now.
3. ✅ **TokenStore:** safe file read and write, with tests for permissions and the temp-file swap.
4. ✅ **BrowserLogin:** open the sign-in page, read the pasted address, pull out the ticket.
5. ✅ **DiTokenClient:** ticket-for-tokens swap, and refresh.
6. ✅ **TokenManager + whoami:** load tokens, refresh if close to expiry, save, then fetch my profile.
7. ✅ **Tests:** each part tested against a fake Garmin server (no real logins), covering success, bad paste, rejected ticket, unknown client IDs, 429 and an expired refresh token.
8. 🟡 **Live check:** `login` and `whoami` worked for real on 23 October 2026. **Still to confirm:** `whoami` still works days later, after the access token (~21 hours) has run out and been refreshed.

## Not in phase 1

- Fetching activity, sleep or other data (phase 2)
- The MCP server (phase 3)
- Keychain storage
- Garmin China accounts (`garmin.cn`)
- Password-based login (blocked for Java clients, see above)

## Credit

The token swap and refresh are based on [python-garminconnect](https://github.com/cyberjunky/python-garminconnect) (MIT licence). We rewrite them in Java rather than copying code.
