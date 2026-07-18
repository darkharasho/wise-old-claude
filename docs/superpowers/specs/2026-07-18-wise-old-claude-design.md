# Wise Old Claude — Design

**Status:** Draft for review
**Date:** 2026-07-18

## Summary

Wise Old Claude is an OSRS/RuneLite plugin with a side chat panel powered by
Claude. The player asks questions in the panel ("what should I do with these
herbs?", "am I ready for this boss?"), and Claude answers using **live game
state** it pulls on demand via tool calls — inventory, stats, location, nearby
entities — rather than the player having to describe their situation.

The name riffs on the Wise Old Man NPC and doubles as attribution to the model.

## Goals (v1)

- A side panel in RuneLite where the player chats with Claude.
- Claude can inspect the player's **current game state** through tools.
- Reuse the established Claude Agent SDK + `CLAUDE_CODE_OAUTH_TOKEN` auth pattern
  from the sibling apps (sai / tai / otto / axivale).
- Reactive only: Claude answers when asked; it does not pipe up unprompted.

## Non-goals (v1)

- **Proactive commentary** on game events (level-ups, deaths, drops). Natural v2;
  the architecture leaves room for it but v1 does not implement the event layer.
- **Hub distribution.** This is a personal/enthusiast project. No RuneLite Plugin
  Hub submission, no multi-user auth, no packaging for strangers.
- **Auto-spawning the sidecar** (see Lifecycle — v1 is manual start).
- Fine-grained per-stat tools. Tools are coarse (see Tools).

## Architecture

Two processes on the local machine, talking over a localhost connection.

```
┌─────────────────────────────┐         ┌──────────────────────────────────┐
│  RuneLite plugin (Java)      │         │  Sidecar (Node/TypeScript)       │
│                              │  chat   │                                  │
│  ┌────────────────────────┐  │ ──────▶ │  ┌────────────────────────────┐  │
│  │ Swing side panel       │  │ deltas  │  │ Claude Agent SDK  query()  │  │
│  └────────────────────────┘  │ ◀────── │  │  - drives the agent loop   │  │
│  ┌────────────────────────┐  │         │  │  - CLAUDE_CODE_OAUTH_TOKEN │  │
│  │ WebSocket client       │  │ tool    │  └────────────┬───────────────┘  │
│  └────────────────────────┘  │ request │               │ tool call        │
│  ┌────────────────────────┐  │ ◀────── │  ┌────────────▼───────────────┐  │
│  │ GameStateProvider      │  │ tool    │  │ SDK MCP server ("gielinor") │  │
│  │  (clientThread.invoke) │  │ resp.   │  │  get_player_state           │  │
│  └────────────────────────┘  │ ──────▶ │  │  get_inventory              │  │
│                              │         │  │  get_nearby_entities        │  │
└─────────────────────────────┘         │  └─────────────────────────────┘  │
                                        └──────────────────────────────────┘
```

The key inversion, same as the sibling apps: **the tools live in the sidecar,
not the plugin.** The Agent SDK runs the loop and hosts the tools as an
in-process SDK MCP server (`createSdkMcpServer`). But the *data* those tools
return lives in the Java plugin. So each tool handler in the sidecar proxies a
request over the WebSocket to the plugin, which reads the game state and answers.

The plugin is a fairly dumb data-provider plus a chat UI. All the Claude smarts
(prompt, model, agent loop) live in the sidecar, so we can iterate on them
without recompiling Java.

### Why this shape

- **Reuses the proven auth stack.** The Agent SDK + `CLAUDE_CODE_OAUTH_TOKEN`
  pattern is exactly what sai / tai / otto / axivale use
  (`axivale/src/main/providers/claude.ts:176-187`). OAuth works because we are
  running Claude Code — the first-party surface it's sanctioned for — and the
  token is long-lived (`claude setup-token`), so no refresh loop.
- **No Java Agent SDK exists.** The Agent SDK is Node/TS + the `claude` binary;
  there is no way to run `query()` or an SDK MCP server from inside a Java
  plugin. Reusing the auth stack therefore *requires* a Node process. The sidecar
  is justified by the auth stack, not merely a language bridge.
- **Token never touches RuneLite.** The OAuth token is read from the sidecar's
  environment. The plugin never sees it and it never lands in RuneLite's config
  files.

## Components

### 1. Sidecar (Node/TypeScript) — `sidecar/`

Responsibilities:

- Run a WebSocket **server** on `127.0.0.1` at a fixed port (default `8137`,
  configurable via env).
- For each `chat` message, call the Agent SDK `query()` with the Wise Old Claude
  system prompt, streaming assistant text back to the plugin as it arrives.
- Host an SDK MCP server named `gielinor` exposing the three tools. Each tool
  handler sends a `tool_request` to the plugin and awaits the matching
  `tool_response`, then returns that data to the agent loop.
- Pass `CLAUDE_CODE_OAUTH_TOKEN` (from its own env) into the SDK, mirroring the
  sibling apps.

Internal units (each independently testable):

- `server.ts` — WebSocket lifecycle, connection handshake, message routing.
- `agent.ts` — wraps `query()`; owns the system prompt and model selection;
  translates SDK stream messages into `assistant_delta` / `assistant_done`.
- `tools.ts` — defines the three MCP tools; each handler calls `toolBridge`.
- `toolBridge.ts` — correlates `tool_request`/`tool_response` by `requestId`,
  returns a Promise the tool handler awaits, with a timeout.
- `protocol.ts` — shared message type definitions and (de)serialization.

Model is configurable (env / config). Default: a current fast model for snappy
interactive chat (`claude-sonnet-4-6`), overridable to a higher-quality model.

### 2. RuneLite plugin (Java) — `plugin/`

Responsibilities:

- Render the side panel and chat transcript (Swing).
- Maintain a WebSocket **client** connection to the sidecar; reconnect with
  backoff; show a clear connected/disconnected state.
- Answer `tool_request` messages by snapshotting the requested game state **on
  the client game thread** and replying with `tool_response`.

Internal units:

- `WiseOldClaudePlugin` — RuneLite `@PluginDescriptor` entry point; wires
  everything up; owns lifecycle.
- `WiseOldClaudeConfig` — RuneLite config (sidecar host/port, model override
  passthrough is a sidecar concern; plugin config is connection + UI only).
- `WiseOldClaudePanel` — Swing `PluginPanel`: transcript view, input box, status
  indicator. Appends streamed deltas to the in-flight assistant bubble.
- `SidecarClient` — WebSocket client; serializes `chat`, deserializes
  `assistant_delta` / `assistant_done` / `tool_request` / `error`; reconnect
  loop.
- `GameStateProvider` — the only class that touches the RuneLite `Client`.
  Exposes `playerState()`, `inventory()`, `nearbyEntities()`, each of which runs
  its read inside `clientThread.invoke(...)` and returns a plain data snapshot.

### 3. Protocol — JSON over WebSocket

A small, versioned message envelope. All messages are JSON objects with a `type`.

Plugin → sidecar:
- `chat` — `{ type: "chat", id, text }` — a user message.
- `tool_response` — `{ type: "tool_response", requestId, data }` or
  `{ type: "tool_response", requestId, error }`.

Sidecar → plugin:
- `assistant_delta` — `{ type: "assistant_delta", id, text }` — streamed token(s).
- `assistant_done` — `{ type: "assistant_done", id }` — turn complete.
- `tool_request` — `{ type: "tool_request", requestId, tool, args }`.
- `error` — `{ type: "error", id?, message }`.

Handshake: on connect the plugin sends a `hello` with a shared token (from
plugin config, matching a value in the sidecar env). The sidecar rejects the
connection if it doesn't match. This keeps other local processes from talking to
the sidecar. Localhost-only bind is the primary guard; the token is defense in
depth.

## Tools (v1 — coarse-grained)

Fewer, broader tools mean fewer round-trips and an easier menu for the model to
pick from.

- **`get_player_state`** — combat level, skill levels + current/base + XP, current
  HP / prayer / run energy, location (region/coords, area name if resolvable),
  quest points, current world, membership.
- **`get_inventory`** — inventory contents (item id, name, quantity), worn
  equipment by slot, and — **only if the bank interface is open** — bank
  contents. If the bank is closed, the tool says so rather than returning stale
  data.
- **`get_nearby_entities`** — nearby NPCs, other players, ground items, and
  interactable game objects within a bounded radius, each with name and rough
  distance/direction.

Each tool returns a compact JSON snapshot. Tool descriptions tell Claude *when*
to reach for each (e.g. "call `get_inventory` when the question is about items,
gear, or what to do with something the player is carrying").

## Data flow (one reactive turn)

1. Player types a question → panel sends `chat` to the sidecar.
2. Sidecar calls `query()` with the system prompt + the user text.
3. Claude decides it needs, say, the inventory → the `get_inventory` MCP tool
   fires in the sidecar → `toolBridge` sends `tool_request` to the plugin and
   awaits.
4. Plugin's `SidecarClient` receives it → `GameStateProvider.inventory()` runs a
   read inside `clientThread.invoke(...)` on the game thread → replies with
   `tool_response`.
5. `toolBridge` resolves; the tool returns the snapshot to the agent loop.
6. Claude produces its answer; the sidecar streams `assistant_delta` messages,
   then `assistant_done`.
7. Panel appends the streamed text to the assistant bubble.

## Threading

RuneLite fires events and exposes client state on the **client game thread**, and
that thread must never block on network or the SDK. The plugin's WebSocket I/O
runs on its own thread(s). The single crossing point is `GameStateProvider`:

- A `tool_request` arrives on a WebSocket thread.
- `GameStateProvider` submits the read via `clientThread.invoke(...)`, capturing
  the snapshot into a `CompletableFuture`.
- The WebSocket thread awaits the future with a timeout (see Error handling), then
  sends the `tool_response`.

Nothing on the game thread ever calls the sidecar or blocks on the network.

## Lifecycle (v1: manual start)

The sidecar is started manually (a terminal / a small run script) before playing.
The plugin connects on load and retries with backoff; the panel shows
"disconnected" until the sidecar is up. Running it yourself means live logs and
the ability to restart it (to change prompts/tools) without touching the game.

**Auto-spawn** (the plugin launches the sidecar as a child process and kills it on
shutdown) is a documented v2 once the core is stable. *Assumption to confirm in
review: manual start is acceptable for v1.*

## Error handling

- **Sidecar down / connection lost** — panel shows a disconnected state; the input
  is disabled (or messages queue) until reconnect. `SidecarClient` retries with
  capped backoff.
- **Tool request timeout** (game thread busy, or player logged out) — after a
  bounded wait the plugin returns a `tool_response` with an `error` payload
  ("player not logged in", "bank not open", "timed out"). The tool surfaces that
  to Claude as an error result so it can adapt, rather than hanging the turn.
- **Not logged in** — `GameStateProvider` returns a structured "not logged in"
  result; tools pass it through.
- **Agent SDK / API error** — the sidecar sends an `error` message; the panel
  renders it as an error bubble instead of an answer.
- **Malformed / unknown message** — logged and ignored on both ends; never
  crashes the connection.

## Security & privacy

- **Game state leaves the machine** to Anthropic when Claude reads a tool result.
  This is accepted for the use case; worth a note in the README.
- **OAuth token** lives only in the sidecar's environment
  (`CLAUDE_CODE_OAUTH_TOKEN`). The plugin never sees it; it never lands in
  RuneLite config files.
- **WebSocket** binds to `127.0.0.1` only and requires the handshake token, so
  other local processes can't drive it.

## Testing strategy

- **Sidecar** (vitest — respect the repo's `--maxWorkers=2` limit):
  - `protocol.ts` — round-trip (de)serialization.
  - `toolBridge.ts` — request/response correlation, timeout behavior, against a
    fake WebSocket peer.
  - `tools.ts` — each tool proxies correctly and shapes its result; fake bridge.
  - `agent.ts` — stream-message translation to `assistant_delta`/`_done` with a
    stubbed `query()`.
- **Plugin** (JUnit):
  - `GameStateProvider` — snapshotting against a mocked RuneLite `Client`;
    not-logged-in and bank-closed paths.
  - `SidecarClient` — message parsing/dispatch and reconnect logic against a fake
    server.
  - Panel logic is thin; verify transcript append/stream handling. Full in-client
    behavior is verified by manual testing in RuneLite.

## Repository layout

Monorepo:

```
/plugin     # Java, Gradle — the RuneLite plugin
/sidecar    # Node/TypeScript — the Agent SDK sidecar
/docs       # this spec and future design docs
```

## Open questions / assumptions to confirm

1. **Manual sidecar start** for v1 (auto-spawn deferred to v2) — acceptable?
2. **Default model** `claude-sonnet-4-6` for latency, overridable — good default,
   or prefer a higher-quality default?
3. **Fixed port `8137`** and a handshake token — fine, or do you want the port
   auto-negotiated?

## Milestones (suggested build order)

1. Sidecar skeleton: WebSocket server + `protocol` + echo, no Claude yet.
2. Plugin skeleton: panel + `SidecarClient`, connect and round-trip `chat`/echo.
3. Wire the Agent SDK into the sidecar with `CLAUDE_CODE_OAUTH_TOKEN`; plain chat
   (no tools) end to end.
4. `GameStateProvider` + one tool (`get_player_state`) end to end.
5. Remaining two tools.
6. Error/disconnect/timeout handling and polish.
