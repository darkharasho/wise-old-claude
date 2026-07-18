# Wise Old Claude v2 — Proactive Commentary (Sub-project A)

**Status:** Draft for review
**Date:** 2026-07-18
**Builds on:** v1 (`2026-07-18-wise-old-claude-design.md`), now merged to `main`.

## Summary

Give Wise Old Claude the ability to **pipe up unprompted** when something
noteworthy happens in-game. The plugin watches RuneLite events, and on a
milestone level-up, a death, or a valuable drop it asks Claude for a brief,
in-character remark — which Claude can make *contextual* by pulling live game
state through the same tools it uses for chat. This layers onto v1: same
sidecar/`query()` loop, same MCP tools, same panel rendering.

This sub-project also folds in the deferred v1 robustness item (**C**): move the
plugin's sidecar I/O off the WebSocket read thread so neither the game thread
nor the read thread ever blocks — which matters more now that events add
unprompted traffic.

## Goals (this sub-project)

- Detect three triggers on the game thread and comment on them: **milestone
  level-ups** (level crosses a multiple of 10, or reaches 99), **player death**,
  and **valuable drops** (total loot value ≥ a configurable threshold).
- One **global cooldown** prevents bursts; suppressed events are dropped.
- Proactive turns have the **same tools** as chat, so comments are contextual.
- Proactive comments render in the existing panel but run as **isolated,
  stateless turns** — they do not join the user's chat conversation.
- A master **on/off** config toggle, plus configurable cooldown and drop
  threshold.
- Move plugin sidecar I/O (tool-request handling + event dispatch) **off the
  WebSocket read thread** (item C).

## Non-goals (this sub-project)

- Additional event types (quest completion, low-HP danger, region entry,
  valuable pickups). The event set is exactly three; widening is a later pass.
- Per-event-type toggles or per-type cooldowns (one global toggle + one global
  cooldown only).
- Queuing/coalescing suppressed events (drop-on-suppression).
- A shared proactive↔chat conversation ("tell me more" follow-ups). Each
  proactive comment is a one-shot.
- Auto-spawning the sidecar (sub-project B) and the automated e2e test
  (sub-project D) — separate specs.

## Architecture

Layers onto v1 without changing its core loop. New elements in **bold**.

```
RuneLite events (game thread)
        │  @Subscribe
        ▼
┌──────────────────────┐
│  EventWatcher (NEW)   │  detects milestone level-up / death / valuable drop,
│                       │  snapshots a compact payload ON the game thread
└──────────┬───────────┘
           │ EventPayload
           ▼
┌──────────────────────┐
│ ProactiveThrottle(NEW)│  global cooldown gate; drop-on-suppression
└──────────┬───────────┘
           │ allowed
           ▼
┌──────────────────────┐        event {kind,detail}      ┌───────────────────────┐
│ ProactiveDispatcher   │ ─────────────────────────────▶ │ Sidecar onEvent (NEW)  │
│  (NEW worker thread)   │                                │  builds proactive turn,│
│  also handles v1        │ ◀─── assistant_delta/_done ─── │  runs query() WITH the │
│  tool_request off the   │      tool_request/response     │  gielinor MCP tools    │
│  read thread (item C)   │ ◀──────────────────────────▶  └───────────────────────┘
└──────────┬─────────────┘
           │ delta/done (event id)
           ▼
   WiseOldClaudePanel (unchanged): renders as a new "Claude:" bubble
```

## Components

### Plugin (`plugin/`)

- **`EventWatcher`** (new) — holds RuneLite `@Subscribe` handlers, registered on
  the `EventBus` in `startUp` and unregistered in `shutDown`. Each handler is thin
  and delegates to testable logic:
  - **Milestone level-up:** on `StatChanged`, compare the skill's new real level
    against a stored `Map<Skill,Integer>` of last levels. Fire only when the level
    *increased* to a milestone (`level == 99 || level % 10 == 0`). The first
    `StatChanged` per skill records a baseline without firing; the map is cleared
    on logout (`GameStateChanged` → not `LOGGED_IN`) so re-login re-baselines
    (prevents a burst of "level up!" on login).
  - **Death:** on `ActorDeath`, fire when `event.getActor() == client.getLocalPlayer()`.
  - **Valuable drop:** on `LootReceived`, sum each item's value via
    `ItemManager` (`getItemPrice(id) * quantity`); if the total ≥
    `config.dropValueThreshold()`, fire with the item list + total.
  - Every handler first checks `config.proactiveEnabled()` and that the player is
    logged in; it builds an `EventPayload` on the game thread (fast, no blocking),
    then passes it to the throttle.
- **`ProactiveThrottle`** (new) — `ProactiveThrottle(long cooldownMs, LongSupplier clock)`
  with `boolean tryFire()`: returns true and stamps the fire time if `now - lastFire
  >= cooldownMs`, else false. Initialized so the first call fires. Clock injected for
  tests. Drop-on-suppression (a false return simply drops the event).
- **`ProactiveDispatcher`** (new) — owns a single-thread `ExecutorService`. Accepts an
  allowed `EventPayload`, and on the worker thread calls `SidecarClient.sendEvent(...)`.
  **Item C lives here:** `WiseOldClaudePlugin.onToolRequest` is changed to submit its
  work to this same executor instead of running on the WebSocket read thread, so a
  slow game-thread read never stalls inbound frame processing. Shut down in `shutDown`.
- **`EventPayload`** (new) — a small immutable value: `kind` (`"level_up"|"death"|"drop"`)
  and a `JsonObject detail` (level_up: `{skill, level}`; death: `{}`; drop:
  `{items:[{name,quantity,value}], totalValue}`).
- **`SidecarClient`** — add `sendEvent(String id, String kind, JsonObject detail)`
  (routes through the codec to the sink, same pattern as `sendChat`).
- **`WiseOldClaudeConfig`** — add `proactiveEnabled()` (default `true`),
  `proactiveCooldownSeconds()` (default `60`), `dropValueThreshold()` (default
  `100000`).
- **`WiseOldClaudePlugin`** — inject `EventBus` + `ItemManager`; construct the
  throttle (from config) and dispatcher; build and register `EventWatcher` in
  `startUp`; unregister the watcher and shut down the dispatcher in `shutDown`;
  route `onToolRequest` through the dispatcher.

### Sidecar (`sidecar/`)

- **`protocol.ts`** — add `Event = { type: "event"; id: string; kind: string;
  detail: Record<string, unknown> }` to `PluginToSidecar`; add `"event"` to the
  inbound set.
- **`server.ts`** — `SidecarServerOpts` gains `onEvent(id, kind, detail, ctx)`;
  the connection handler routes an inbound `event` message to it (auth-gated like
  `chat`).
- **`agent.ts`** — add `PROACTIVE_SYSTEM_PROMPT` and `runProactive(deps, id, kind,
  detail, ctx)`: it formats the event into a short instruction (e.g. *"The player
  just hit 70 Attack. React briefly, in character."*), runs its own `query()` with
  the same `mcpServer` + `ALLOWED_TOOLS` as chat, and streams the reply as
  `assistant_delta`/`assistant_done` under the event's id. Independent of chat —
  its own `query()` call, no shared history.
- **`main.ts`** — implement `onEvent` to build the per-event MCP server from
  `ctx.bridge` (same as `onChat`) and call `runProactive`.

### Panel — unchanged

Proactive comments arrive as `assistant_delta`/`assistant_done` with a fresh id,
so the existing `onDelta`/`onDone` render them as a new "Claude:" bubble. (Optional
polish: prefix proactive bubbles differently, e.g. "Claude 👁" — deferred; not in
this spec.)

## Protocol addition (frozen field names, both sides)

Plugin → sidecar:
- `{ "type": "event", "id": string, "kind": "level_up"|"death"|"drop", "detail": object }`

Sidecar → plugin: unchanged — reuses `assistant_delta` / `assistant_done` / `tool_request` / `error`, all keyed by the event's `id`.

## Data flow (one proactive event)

1. RuneLite fires an event on the game thread → `EventWatcher` handler runs, checks
   `proactiveEnabled` + logged-in, detects a trigger, and builds an `EventPayload`
   on the spot (no blocking).
2. `ProactiveThrottle.tryFire()` — if suppressed, drop and stop.
3. Allowed → hand the payload to `ProactiveDispatcher` (off the game thread).
4. Worker thread → `SidecarClient.sendEvent` → `event` frame to the sidecar.
5. Sidecar `onEvent` → `runProactive` builds the prompt + tools → `query()` → may
   call `get_*` tools (round-tripping to the plugin exactly as in v1) → streams the
   comment.
6. Panel renders the streamed comment as a new bubble.

## Threading

- `EventWatcher` handlers run on the **game thread** (RuneLite `@Subscribe`). They
  only read client state + build a payload — fast, non-blocking — then enqueue to
  the dispatcher.
- `ProactiveDispatcher`'s single worker thread does all sidecar sends.
- **Item C:** `onToolRequest` (which blocks up to 5s on a game-thread read) moves
  onto the dispatcher's worker, off the WebSocket read thread, so inbound frame
  processing is never stalled.
- `ProactiveThrottle` is touched from the game thread only (all `@Subscribe`
  handlers run there); its `lastFire` field is therefore single-threaded, but it is
  marked `volatile` for safety and documented as game-thread-owned.

## Error handling

- **Sidecar down / disconnected:** `sendEvent` drops the frame if the socket is not
  open (existing warn log); the comment is simply lost. Reconnect is unchanged.
- **Throttle suppressed:** dropped.
- **Not logged in / proactive disabled:** `EventWatcher` no-ops (events only occur
  in-game; the config guard short-circuits).
- **Tool error during a proactive turn:** identical to chat — Claude receives the
  error tool-result and adapts.
- **Malformed event:** ignored on the sidecar (unknown fields tolerated).

## Testing strategy

- **Plugin (JUnit + Mockito):**
  - `ProactiveThrottle` — clock-injected: fires first call; suppresses within the
    cooldown; fires again after it elapses.
  - `EventWatcher` milestone logic — 69→70 fires, 70→71 does not, 98→99 fires,
    baseline-on-first-StatChanged does not fire, logout clears baseline.
  - `EventWatcher` death — fires only when the dying actor is the local player.
  - `EventWatcher` drop — sums values via a mocked `ItemManager`; fires at/above the
    threshold, not below.
  - `SidecarClient.sendEvent` emits the correct `event` frame (seam sink).
  - Dispatcher routes a payload to `sendEvent` (and `onToolRequest` goes through the
    worker) — verified without real sockets.
- **Sidecar (vitest):**
  - `protocol` parses an `event` message.
  - `runProactive` — with a fake `queryFn`, verifies prompt construction per kind
    and the delta→done stream translation (mirrors the v1 `agent.test` pattern).
  - `server` routes an inbound `event` to `onEvent` after auth.

## Config defaults

| Key | Default |
|---|---|
| `proactiveEnabled` | `true` |
| `proactiveCooldownSeconds` | `60` |
| `dropValueThreshold` | `100000` |

## RuneLite API notes (verify against 1.12.33 during implementation)

The plan must confirm these class/method names against the resolved RuneLite jar
(as v1 did for `getItemDefinition`), falling back to the correct name if any differ:
- `net.runelite.api.events.StatChanged` (`getSkill()`, `getLevel()` = real level)
- `net.runelite.api.events.ActorDeath` (`getActor()`)
- `net.runelite.client.events.LootReceived` (`getItems()` → `ItemStack`s)
- `net.runelite.client.game.ItemManager` (`getItemPrice(int)`)
- `net.runelite.client.eventbus.EventBus` (`register`/`unregister`)
- `net.runelite.api.events.GameStateChanged` (for logout baseline reset)

## Milestones (suggested build order)

1. Protocol `event` on both ends (sidecar `protocol.ts` + Java codec) + `sendEvent`.
2. `ProactiveThrottle` (pure, TDD).
3. `EventWatcher` detection logic (TDD each trigger against mocked events/ItemManager).
4. `ProactiveDispatcher` worker + move `onToolRequest` onto it (item C).
5. Sidecar `runProactive` + `onEvent` + `main.ts` wiring.
6. Plugin wiring: inject EventBus/ItemManager, build+register watcher, config keys.
7. Config toggle + defaults; end-to-end manual check.

## Open questions / assumptions to confirm

1. `proactiveEnabled` defaults **on** — acceptable, or default off until you've seen
   it behave?
2. Proactive bubbles render identically to chat replies (no distinct styling) in v1
   of this feature — fine, or do you want a small visual marker now?
