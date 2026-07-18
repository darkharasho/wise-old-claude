# Wise Old Claude v2 — Automated End-to-End Test (Sub-project D)

**Status:** Draft for review
**Date:** 2026-07-18
**Builds on:** v1 + v2-A + v2-B (all merged to `main`).

## Summary

Add a deterministic, automated end-to-end test that exercises the real sidecar
transport, protocol, and tool round-trip across a real WebSocket — the integration
surface that per-component unit tests and manual checks left uncovered. It fakes
only the "brain" (the Agent SDK `query()` / LLM decision), so it needs no OAuth
token, no network, no real Claude, and no RuneLite. It also adds a cross-language
contract fixture that guards the TS↔Java protocol from silent drift.

**Test-only: no production code changes.**

## Goals

- One vitest test file that stands up the real `SidecarServer` on an ephemeral
  port and drives it with a real `ws` client acting as the plugin.
- Cover the **chat → tool → answer → stream** round-trip: handshake, chat, a real
  `tool_request` answered by the client, streamed `assistant_delta`/`assistant_done`,
  all correlated by id.
- Cover the **proactive `event` → tool → stream** round-trip.
- A **cross-language contract fixture**: `parseMessage` accepts the exact literal
  frames the Java `ProtocolCodec` emits.
- Fully deterministic and CI-friendly: `--maxWorkers=2`, no token/network/RuneLite.

## Non-goals

- A live e2e that calls real Claude (needs a token + network, non-deterministic).
  The manual checklist in the v1 plan covers that path.
- Exercising the real Agent SDK `query()` agent loop (it drives MCP tool
  invocation itself and requires the real SDK/credentials). `runChat`/`runProactive`
  remain covered by their existing unit tests with a fake `queryFn`; D covers the
  wire/transport/tool-bridge integration those unit tests cannot.
- Any change to shipping code. D adds tests only.

## Why this seam

The real `query()` is the component that both (a) needs credentials and (b) drives
MCP tool calls. Replacing it with a simple fake `queryFn` (as the unit tests do)
therefore *bypasses* the tool round-trip entirely — a fake generator never invokes
the MCP tools. To exercise the actual `ToolBridge` → `tool_request` → client →
`tool_response` → resolve path deterministically, the server side must issue the
tool call itself. So D scripts the server's `onChat`/`onEvent` to drive the *same*
`SessionCtx` the real handlers drive (`ctx.bridge.request(...)`, `ctx.sendDelta`,
`ctx.sendDone`). Everything else in the path — the `SidecarServer`, the WebSocket
transport, the protocol (`parseMessage`/`serialize`), the `ToolBridge` correlation,
and the real `ws` client — is genuine.

## Architecture

```
vitest test (e2e.test.ts)
  │  real ws client (acts as the plugin)
  ▼
ws://127.0.0.1:<ephemeral>
  ▼
REAL SidecarServer  { onChat: scripted, onEvent: scripted }
  │   scripted handler uses the REAL SessionCtx:
  │     ctx.bridge.request("get_player_state", {})  ──▶ tool_request ──▶ test client
  │     ctx.bridge resolves        ◀── tool_response ◀── test client (fake game state)
  │     ctx.sendDelta(id, text) / ctx.sendDone(id)  ──▶ assistant_delta/_done ──▶ client
  ▼
(assertions on what the client received)

Contract fixture: parseMessage(<literal Java-emitted frame>) → expected typed object
```

## Components

### `sidecar/src/e2e.test.ts` (new — the only file)

- A small local helper to open a `ws` client to `127.0.0.1:<port>`, plus a way to
  await the next frame (or collect frames) — mirroring the pattern already used in
  `server.test.ts` (a message queue so a rapid second frame isn't dropped).
- **Test 1 — chat round-trip with a tool call:**
  1. Start `new SidecarServer({ port: 0, token: "e2e", onChat, onEvent })` where
     `onChat(id, text, ctx)` calls `ctx.bridge.request("get_player_state", {})`,
     then on resolution calls `ctx.sendDelta(id, "You are combat level " + data.combatLevel)`
     and `ctx.sendDone(id)`.
  2. Connect the client, send `{type:"hello", token:"e2e"}`, expect `hello_ok`.
  3. Send `{type:"chat", id:"c1", text:"how am I doing?"}`.
  4. Expect a `tool_request` with `tool:"get_player_state"`; reply
     `{type:"tool_response", requestId:<same>, data:{combatLevel:99}}`.
  5. Expect `assistant_delta` (id `c1`, text containing `99`) then `assistant_done`
     (id `c1`). Assert the ids match and the tool data flowed through.
- **Test 2 — proactive event round-trip:** same shape driven by
  `{type:"event", id:"e1", kind:"level_up", detail:{skill:"Attack", level:70}}`,
  with `onEvent` requesting a tool then streaming; assert the streamed comment
  arrives under id `e1`.
- **Test 3 — tool error propagation:** the client answers the `tool_request` with
  `{type:"tool_response", requestId:<same>, error:"not logged in"}`; assert the
  scripted handler observes the rejection (streams an error/adapted message) —
  verifying the `error`-branch of the tool round-trip end to end.
- **Test 4 — cross-language contract fixture:** call `parseMessage` on the exact
  literal frame strings the Java `ProtocolCodec` emits (copied from the Java tests'
  pinned assertions) and assert the parsed objects:
  - `{"type":"hello","token":"t"}`
  - `{"type":"chat","id":"1","text":"hi"}`
  - `{"type":"tool_response","requestId":"r1","data":{"hp":99}}`
  - `{"type":"tool_response","requestId":"r1","error":"not logged in"}`
  - `{"type":"event","id":"e1","kind":"level_up","detail":{"skill":"Attack","level":70}}`

  Each must parse to the expected typed object, proving the sidecar accepts what
  the plugin emits.
- `afterEach` stops the server; each test uses `port: 0`.

## Data flow

Covered by the architecture diagram: the client and server exchange real frames
over a real socket; the scripted handler drives the real `ToolBridge` and `ctx`
streaming; assertions are made on the frames the client actually receives.

## Error handling

N/A — this is a test. Test 3 explicitly verifies the tool-error branch; the server's
existing auth gate and malformed-frame handling are already covered in
`server.test.ts` and are out of scope here.

## Testing strategy

The deliverable *is* the test. Run: `cd sidecar && npx vitest run --maxWorkers=2`.
All e2e cases must pass deterministically with pristine output. Because it uses
real sockets on ephemeral ports, each test must start and stop its own server
(`afterEach`) to avoid port/state bleed.

## Milestones (suggested build order)

1. Test file scaffold + the `ws`-client helper + Test 1 (chat → tool → stream).
2. Test 2 (event round-trip) + Test 3 (tool-error branch).
3. Test 4 (cross-language contract fixture).

## Open questions / assumptions to confirm

1. The scripted `onChat`/`onEvent` drive the real `SessionCtx` (bridge + streaming)
   rather than the real `runChat`/`runProactive` — because a fake `queryFn` cannot
   trigger the MCP tool round-trip. Acceptable (this is the only way to exercise the
   tool wire path deterministically), or do you want the test to also route through
   `runChat` for the non-tool streaming portion?
2. The contract fixtures are hand-copied from the Java tests' pinned literal strings.
   Fine, or would you rather generate them from the Java side (more machinery, not
   worth it for a personal project)?
