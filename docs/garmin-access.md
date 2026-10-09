# Getting Garmin data

_Researched October 2026. This changes often, so check again before relying on it._

## The ways in

| Option | Can I use it? | Live data? | Reliability |
|---|---|---|---|
| **Official Garmin API** (Connect Developer Program) | No. It's for companies and research institutions, not individuals. | Yes | High |
| **Phone-app login** (unofficial) | Yes | Yes | Medium. Garmin can break it without warning. |
| **Data export** (Garmin account → "Export your data") | Yes | No. A one-off download that can take days to arrive. | High |

**Our approach:** use the phone-app login for live data. Keep the data export in mind as a backup.

## How the phone-app login works

This is the "authenticate on the command line" idea. We get the same tokens the Garmin Connect phone app uses:

1. `stride-mcp login` opens Garmin's sign-in page in my browser. I log in there, MFA included.
2. Garmin redirects to the phone-app address with a single-use **ticket** in it. I paste that address back into the terminal.
3. stride-mcp swaps the ticket for an **access token** and a **refresh token** (from `diauth.garmin.com`).
4. It saves the tokens to a private file on my machine.
5. From then on, it uses the tokens to call Garmin's data endpoints, and refreshes them when they run out.

stride-mcp never sees my password. Why we use the browser rather than a password prompt (Garmin blocks non-browser logins): see [Phase 1](phases/phase-1-login.md).

## What happened in March 2026

- Garmin changed its login flow.
- **garth**, the Python library almost every Garmin tool used, stopped working for new logins and is now abandoned.
- **python-garminconnect**, the most popular library, switched to a new login method (no longer using garth) and is still maintained. It's the best place to see what currently works.

**Lesson:** the login is the fragile part. Our design should keep it in one place, so a fix means changing one module.

## Is this allowed?

Garmin's terms don't approve this kind of access, and Garmin doesn't actively support it either. It's widely used for personal data. To keep the risk low:

- Only access **my own** account.
- Keep request rates low, and cache results instead of fetching the same thing repeatedly.
- Don't run lots of logins in a row. Repeated logins can get an account temporarily locked.

## What data we can get

Roughly what the Garmin Connect app shows:

- **Activities:** list, details, splits/laps, heart rate, pace, power
- **Daily health:** steps, resting heart rate, stress, Body Battery
- **Sleep:** stages, score, HRV
- **Training:** training status, load, VO2 max, race predictions
- **Body:** weight and body composition, if recorded

Writing data (for example creating workouts) is also possible, but it's out of scope for now.

## Existing projects to learn from

Several open-source Garmin MCP servers already exist, mostly in Python on top of python-garminconnect (for example `Taxuspt/garmin_mcp`). They're useful references for which tools are worth offering. We're building our own in Java/Spring for control and reliability.

## Sources

- [python-garminconnect](https://github.com/cyberjunky/python-garminconnect): current login flow and token handling
- [garth (deprecated)](https://github.com/matin/garth)
- [Why Garmin has no personal API](https://ghurt.org/garmin-api-for-personal-use). Written by a commercial vendor, so read with that in mind.
