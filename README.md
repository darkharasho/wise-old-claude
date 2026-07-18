# Wise Old Claude

A RuneLite plugin with a reactive Claude side-chat panel that answers questions about your character using live OSRS game state via tool calls.

## Architecture

Two components work together over a localhost WebSocket:

- **`sidecar/`** — Node/TypeScript process running the Claude Agent SDK. Exposes three MCP tools (`get_inventory`, `get_skills`, `get_player_info`) that the Claude model can call during a conversation.
- **`plugin/`** — Java RuneLite plugin. Renders the chat panel, connects to the sidecar, reads game state from the RuneLite `Client` on the game thread, and responds to the sidecar's tool requests.

```
RuneLite plugin  ←—WS (localhost:8137)—→  sidecar  ←—HTTPS—→  Anthropic API
  (game state)                             (Claude Agent SDK)
```

## Prerequisites

- **Node ≥ 20** (sidecar)
- **JDK 11+** (plugin builds Java 11 bytecode via Gradle)
- **`CLAUDE_CODE_OAUTH_TOKEN`** — obtain with `claude setup-token`
- RuneLite developer setup (see [RuneLite wiki](https://github.com/runelite/runelite/wiki/Building-with-IntelliJ-IDEA))

## Running (manual, v1)

### 1. Start the sidecar

```sh
cd sidecar
npm install
WOC_TOKEN=<shared-secret> CLAUDE_CODE_OAUTH_TOKEN=$(claude setup-token) npm run dev
```

Environment variables:

| Variable | Default | Description |
|---|---|---|
| `WOC_TOKEN` | — (required) | Handshake token shared with the plugin |
| `WOC_PORT` | `8137` | WebSocket listen port |
| `WOC_MODEL` | `claude-sonnet-4-6` | Claude model ID |

### 2. Start the plugin

Build and run the plugin in your RuneLite dev environment. In the plugin config panel set:

- **Sidecar host:** `127.0.0.1`
- **Sidecar port:** `8137` (or whatever `WOC_PORT` you chose)
- **Token:** the same value you set for `WOC_TOKEN`

### 3. Verify

The panel header should show **Connected**. Log in to OSRS and ask a question such as "What's in my inventory?" — Claude will call the appropriate tool and reply with real item names.

### Auto-spawn the sidecar (optional)

By default you start the sidecar yourself (see above). To have the plugin launch
it for you, first build the sidecar once (`cd sidecar && npm install && npm run
build`), then in the plugin config:

- Enable **Manage sidecar**.
- Set **Sidecar directory** to the path of the `sidecar/` folder (it must contain
  `dist/main.js`).
- Set **Node path** if `node` is not on RuneLite's PATH.

The plugin spawns `node dist/main.js`, passing the handshake token and port, and
pipes the sidecar's output into RuneLite's log. It **does not** pass your Claude
credential — the spawned sidecar inherits `CLAUDE_CODE_OAUTH_TOKEN` from your
environment. If RuneLite is launched such that it doesn't inherit that variable,
point **Sidecar env file** at a `KEY=VALUE` file containing
`CLAUDE_CODE_OAUTH_TOKEN=...` (entries there override the inherited environment).

If a sidecar is already running on the configured port, the plugin attaches to it
instead of spawning a second one. The plugin kills a sidecar it spawned when the
plugin is disabled.

## Manual end-to-end test

1. Start the sidecar with a valid `CLAUDE_CODE_OAUTH_TOKEN` and a known `WOC_TOKEN`.
2. Run the plugin pointing at the same port and token.
3. Ask **"What's in my inventory?"** — confirm the sidecar logs a `tool_request` and Claude answers with real items.
4. Kill the sidecar. Confirm the panel flips to **Disconnected**.
5. Restart the sidecar. Confirm the plugin reconnects automatically (exponential backoff, cap ~8 s) and the panel returns to **Connected**.

There is no automated end-to-end test — the e2e path requires a live OAuth token and a running RuneLite instance, making it a manual gate only.

## Limitations / notes

- **v1 is reactive only.** Claude responds to messages you send; there is no proactive commentary on game events.
- **Tool calls are serial in v1.** `onToolRequest` runs on the WebSocket read thread and blocks up to ~5 s on the game-thread read.
- **Privacy.** Game state read by the tools (inventory, skills, player info) is included in the prompt context sent to Anthropic's API when Claude invokes a tool.
- **Token isolation.** The sidecar's `CLAUDE_CODE_OAUTH_TOKEN` stays in the sidecar's process environment. The plugin config only stores the localhost handshake token (`WOC_TOKEN`); it never sees the OAuth token.
- **Loot Tracker dependency.** Proactive drop comments require RuneLite's built-in **Loot Tracker** plugin to be enabled (it posts the loot event); level-up and death comments have no such dependency.
