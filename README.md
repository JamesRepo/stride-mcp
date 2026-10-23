# stride-mcp

Let AI apps read my Garmin data, using [MCP](https://modelcontextprotocol.io). Works with any MCP client, such as Claude or ChatGPT.

> **Status:** early. Phase 1 (command-line login) is built and being tested for real. It can log in, but can't fetch data yet. See [the plan](docs/project-plan.md).

## How it works

```mermaid
flowchart LR
    AI["AI app"] -- MCP --> S["stride-mcp"]
    S -- HTTPS --> G["Garmin Connect"]
```

stride-mcp logs in to Garmin once, keeps the login fresh, and offers tools like "get my recent activities" to the AI app.

## Build

Needs Java 25.

```
./mvnw verify
```

## Try it

[Quick start](docs/quick-start.md): build, log in to Garmin, check it works.

## Docs

Start with [docs/README.md](docs/README.md).

## Note

This uses Garmin's unofficial phone-app login, because Garmin has no personal API. It's for accessing your own data, and it may break when Garmin changes things. See [Getting Garmin data](docs/garmin-access.md).
