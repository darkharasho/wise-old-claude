# Wise Old Claude v2 — Proactive Commentary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Wise Old Claude comment unprompted on milestone level-ups, deaths, and valuable drops, with a global cooldown, contextual tools, and sidecar I/O moved off the WebSocket read thread.

**Architecture:** Layers onto the merged v1. The plugin watches RuneLite events on the game thread (`EventWatcher`), gates them through a `ProactiveThrottle`, and hands allowed events to a worker thread (`ProactiveDispatcher`) that sends a new `event` message to the sidecar. The sidecar runs a stateless proactive `query()` (same MCP tools) and streams the reply back as `assistant_delta`/`assistant_done`, which the existing panel renders. The dispatcher's worker also takes over `onToolRequest`, off the read thread.

**Tech Stack:** Same as v1 — TypeScript (Node ≥ 20, ESM), `@anthropic-ai/claude-agent-sdk`, `ws`, `vitest`; Java 11, Gradle, RuneLite `net.runelite:client` (1.12.33), Gson 2.8.5, Lombok, JUnit 5 + Mockito.

## Global Constraints

- Branch: `design/v2-proactive` (already created off `main`). Do NOT switch branches; verify `git branch --show-current` == `design/v2-proactive` before each commit.
- vitest runs with `--maxWorkers=2` (CLAUDE.md memory cap).
- The `event` message field names are FROZEN: `{ "type": "event", "id": string, "kind": "level_up"|"death"|"drop", "detail": object }`. TS and Java must match byte-for-byte.
- Gson on the plugin is **2.8.5** — build JSON with `JsonObject`/`JsonArray`; parse with `gson.fromJson(raw, JsonObject.class)` (no `JsonParser.parseString`).
- The Agent SDK `tool()` takes a zod **raw shape** (`{}`), not `z.object({})` — but this plan does not add tools; it reuses v1's `buildTools`/`ALLOWED_TOOLS` unchanged.
- Config defaults: `proactiveEnabled=true`, `proactiveCooldownSeconds=60`, `dropValueThreshold=100000`.
- **RuneLite API verification:** these class/method names are expected but MUST be confirmed against the resolved `net.runelite:client:1.12.33` jar during the task that uses them (as v1 did for `getItemDefinition`). If a name differs, adjust the code AND the test stub, and note it in the report:
  - `net.runelite.client.eventbus.Subscribe`, `net.runelite.client.eventbus.EventBus` (`register`/`unregister`)
  - `net.runelite.api.events.StatChanged` (`getSkill()`, `getLevel()` = real level)
  - `net.runelite.api.events.ActorDeath` (`getActor()`)
  - `net.runelite.api.events.GameStateChanged` (`getGameState()`)
  - `net.runelite.client.events.LootReceived` (`getItems()` → `Collection<ItemStack>`; `ItemStack.getId()/getQuantity()`)
  - `net.runelite.client.game.ItemManager` (`getItemPrice(int)`, `getItemComposition(int).getName()`)
- Git commit signing (1Password) can intermittently fail with "failed to fill whole buffer"; retry 2-3×, and if still failing report DONE_WITH_CONCERNS (do not disable signing).

## Protocol reference (the one new message)

Plugin → sidecar (new): `{ "type": "event", "id": string, "kind": string, "detail": object }`
Sidecar → plugin (reused): `assistant_delta` / `assistant_done` / `tool_request` / `error`, keyed by the event's `id`.

---

### Task 1: Sidecar protocol — `event` inbound message

**Files:**
- Modify: `sidecar/src/protocol.ts`
- Test: `sidecar/src/protocol.test.ts`

**Interfaces:**
- Produces: `Event` type in `PluginToSidecar`; `parseMessage` accepts `type: "event"`.

- [ ] **Step 1: Add a failing test to `sidecar/src/protocol.test.ts`**

Append inside the `describe("protocol", ...)` block:

```ts
  it("parses an event message", () => {
    const raw = JSON.stringify({ type: "event", id: "e1", kind: "level_up", detail: { skill: "Attack", level: 70 } });
    expect(parseMessage(raw)).toEqual({ type: "event", id: "e1", kind: "level_up", detail: { skill: "Attack", level: 70 } });
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sidecar && npx vitest run --maxWorkers=2 src/protocol.test.ts`
Expected: FAIL — `parseMessage` throws "unknown inbound message type: event".

- [ ] **Step 3: Edit `sidecar/src/protocol.ts`**

Add the `Event` type and include it in the union and the inbound set. After the `ToolResponse` type add:

```ts
export type Event = {
  type: "event";
  id: string;
  kind: string;
  detail: Record<string, unknown>;
};
```

Change the `PluginToSidecar` union to include it:

```ts
export type PluginToSidecar = Hello | Chat | ToolResponse | Event;
```

Change the `INBOUND` set to include `"event"`:

```ts
const INBOUND = new Set(["hello", "chat", "tool_response", "event"]);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd sidecar && npx vitest run --maxWorkers=2 src/protocol.test.ts`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add sidecar/src/protocol.ts sidecar/src/protocol.test.ts
git commit -m "feat(sidecar): add event inbound protocol message"
```

---

### Task 2: Java event plumbing — codec builder + `SidecarClient.sendEvent`

**Files:**
- Modify: `plugin/src/main/java/com/wiseoldclaude/protocol/Messages.java`
- Modify: `plugin/src/main/java/com/wiseoldclaude/protocol/ProtocolCodec.java`
- Modify: `plugin/src/main/java/com/wiseoldclaude/SidecarClient.java`
- Test: `plugin/src/test/java/com/wiseoldclaude/protocol/ProtocolCodecTest.java`
- Test: `plugin/src/test/java/com/wiseoldclaude/SidecarClientTest.java`

**Interfaces:**
- Consumes: existing `ProtocolCodec` (Gson), `SidecarClient` seam constructor.
- Produces: `ProtocolCodec.event(String id, String kind, JsonObject detail): String`; `SidecarClient.sendEvent(String id, String kind, JsonObject detail): void`.

- [ ] **Step 1: Add failing tests**

In `ProtocolCodecTest.java`, add:

```java
    @Test
    void buildsEvent()
    {
        JsonObject detail = new JsonObject();
        detail.addProperty("skill", "Attack");
        detail.addProperty("level", 70);
        assertEquals("{\"type\":\"event\",\"id\":\"e1\",\"kind\":\"level_up\",\"detail\":{\"skill\":\"Attack\",\"level\":70}}",
            codec.event("e1", "level_up", detail));
    }
```

In `SidecarClientTest.java`, add (uses the existing `sent` sink + `client(...)` helper + `NoopListener`):

```java
    @Test
    void sendEventEmitsEventFrame()
    {
        SidecarClient c = client(new NoopListener());
        JsonObject detail = new JsonObject();
        detail.addProperty("level", 99);
        c.sendEvent("e2", "level_up", detail);
        assertEquals("{\"type\":\"event\",\"id\":\"e2\",\"kind\":\"level_up\",\"detail\":{\"level\":99}}", sent.get(0));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd plugin && ./gradlew test --tests '*ProtocolCodecTest' --tests '*SidecarClientTest'`
Expected: FAIL — `event`/`sendEvent` do not exist.

- [ ] **Step 3: Add the `EVENT` constant to `Messages.java`**

Add alongside the other constants:

```java
    public static final String EVENT = "event";
```

- [ ] **Step 4: Add the `event` builder to `ProtocolCodec.java`**

Add this method (mirrors `chat`):

```java
    public String event(String id, String kind, JsonObject detail)
    {
        JsonObject o = new JsonObject();
        o.addProperty("type", Messages.EVENT);
        o.addProperty("id", id);
        o.addProperty("kind", kind);
        o.add("detail", detail);
        return gson.toJson(o);
    }
```

- [ ] **Step 5: Add `sendEvent` to `SidecarClient.java`**

Next to `sendChat`:

```java
    public void sendEvent(String id, String kind, JsonObject detail) { sink.accept(codec.event(id, kind, detail)); }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd plugin && ./gradlew test --tests '*ProtocolCodecTest' --tests '*SidecarClientTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add plugin/src/main/java/com/wiseoldclaude/protocol/ plugin/src/main/java/com/wiseoldclaude/SidecarClient.java plugin/src/test/java/com/wiseoldclaude/
git commit -m "feat(plugin): event protocol builder + SidecarClient.sendEvent"
```

---

### Task 3: ProactiveThrottle (global cooldown)

**Files:**
- Create: `plugin/src/main/java/com/wiseoldclaude/ProactiveThrottle.java`
- Test: `plugin/src/test/java/com/wiseoldclaude/ProactiveThrottleTest.java`

**Interfaces:**
- Produces: `ProactiveThrottle(long cooldownMs, java.util.function.LongSupplier clock)` with `boolean tryFire()`.

- [ ] **Step 1: Write the failing test `plugin/src/test/java/com/wiseoldclaude/ProactiveThrottleTest.java`**

```java
package com.wiseoldclaude;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProactiveThrottleTest
{
    @Test
    void firesThenSuppressesWithinCooldownThenFiresAfter()
    {
        long[] now = {1000};
        ProactiveThrottle t = new ProactiveThrottle(60_000, () -> now[0]);
        assertTrue(t.tryFire(), "first call fires");
        now[0] = 1000 + 30_000;
        assertFalse(t.tryFire(), "within cooldown suppressed");
        now[0] = 1000 + 60_000;
        assertTrue(t.tryFire(), "cooldown elapsed fires");
        now[0] = 1000 + 61_000;
        assertFalse(t.tryFire(), "suppressed again right after");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd plugin && ./gradlew test --tests '*ProactiveThrottleTest'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Create `plugin/src/main/java/com/wiseoldclaude/ProactiveThrottle.java`**

```java
package com.wiseoldclaude;

import java.util.function.LongSupplier;

/** Global cooldown gate. Owned by the game thread; volatile for visibility. */
public class ProactiveThrottle
{
    private final long cooldownMs;
    private final LongSupplier clock;
    private volatile long lastFire;

    public ProactiveThrottle(long cooldownMs, LongSupplier clock)
    {
        this.cooldownMs = cooldownMs;
        this.clock = clock;
        this.lastFire = clock.getAsLong() - cooldownMs; // allow the first fire
    }

    public boolean tryFire()
    {
        long now = clock.getAsLong();
        if (now - lastFire >= cooldownMs)
        {
            lastFire = now;
            return true;
        }
        return false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd plugin && ./gradlew test --tests '*ProactiveThrottleTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add plugin/src/main/java/com/wiseoldclaude/ProactiveThrottle.java plugin/src/test/java/com/wiseoldclaude/ProactiveThrottleTest.java
git commit -m "feat(plugin): ProactiveThrottle global cooldown gate"
```

---

### Task 4: EventWatcher (trigger detection) + EventPayload

**Files:**
- Create: `plugin/src/main/java/com/wiseoldclaude/game/EventPayload.java`
- Create: `plugin/src/main/java/com/wiseoldclaude/game/EventWatcher.java`
- Test: `plugin/src/test/java/com/wiseoldclaude/game/EventWatcherTest.java`

**Interfaces:**
- Consumes: `ProactiveThrottle`, `WiseOldClaudeConfig` (only `proactiveEnabled()` + `dropValueThreshold()` are read here — those config keys are added in Task 7; this task compiles against the existing config plus the two new methods, so **Task 7's config keys must exist for this to compile** — see note below), RuneLite `Client`, `ItemManager`.
- Produces: `EventPayload(String kind, JsonObject detail)` with `kind()`/`detail()`; `EventWatcher(Client, ItemManager, WiseOldClaudeConfig, ProactiveThrottle, Consumer<EventPayload> onFire)` with `@Subscribe` handlers `onStatChanged`, `onActorDeath`, `onLootReceived`, `onGameStateChanged`.

> **Ordering note for the implementer:** `EventWatcher` reads `config.proactiveEnabled()` and `config.dropValueThreshold()`, which are added in Task 7. To keep this task independently compilable and testable, **add those two methods to `WiseOldClaudeConfig` as part of THIS task** (with the defaults from Global Constraints); Task 7 adds the remaining `proactiveCooldownSeconds()` key and the wiring. If the two methods already exist, leave them. This is the one deliberate cross-task ordering exception.

- [ ] **Step 1: Create `EventPayload.java`**

```java
package com.wiseoldclaude.game;

import com.google.gson.JsonObject;

/** Immutable proactive-event payload: a kind and its detail object. */
public final class EventPayload
{
    private final String kind;
    private final JsonObject detail;

    public EventPayload(String kind, JsonObject detail)
    {
        this.kind = kind;
        this.detail = detail;
    }

    public String kind() { return kind; }
    public JsonObject detail() { return detail; }
}
```

- [ ] **Step 2: Ensure the two config methods exist (add to `WiseOldClaudeConfig.java` if missing)**

Add these items (keep existing ones):

```java
    @ConfigItem(keyName = "proactiveEnabled", name = "Proactive comments", position = 4,
        description = "Let Claude comment unprompted on notable events")
    default boolean proactiveEnabled() { return true; }

    @ConfigItem(keyName = "dropValueThreshold", name = "Drop value threshold", position = 6,
        description = "Minimum total GE value of a drop to comment on")
    default int dropValueThreshold() { return 100000; }
```

- [ ] **Step 3: Write the failing test `plugin/src/test/java/com/wiseoldclaude/game/EventWatcherTest.java`**

```java
package com.wiseoldclaude.game;

import com.wiseoldclaude.ProactiveThrottle;
import com.wiseoldclaude.WiseOldClaudeConfig;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.game.ItemManager;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventWatcherTest
{
    private final ProactiveThrottle alwaysFire = new ProactiveThrottle(0, () -> 0L);

    private EventWatcher watcher(Client client, ItemManager items, List<EventPayload> out)
    {
        WiseOldClaudeConfig config = mock(WiseOldClaudeConfig.class);
        when(config.proactiveEnabled()).thenReturn(true);
        when(config.dropValueThreshold()).thenReturn(100000);
        return new EventWatcher(client, items, config, alwaysFire, out::add);
    }

    private StatChanged stat(Skill s, int level)
    {
        StatChanged e = mock(StatChanged.class);
        when(e.getSkill()).thenReturn(s);
        when(e.getLevel()).thenReturn(level);
        return e;
    }

    @Test
    void firesOnMilestoneLevelButNotBetween()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        List<EventPayload> out = new ArrayList<>();
        EventWatcher w = watcher(client, mock(ItemManager.class), out);

        w.onStatChanged(stat(Skill.ATTACK, 69)); // baseline, no fire
        w.onStatChanged(stat(Skill.ATTACK, 70)); // milestone, fires
        w.onStatChanged(stat(Skill.ATTACK, 71)); // not milestone
        assertEquals(1, out.size());
        assertEquals("level_up", out.get(0).kind());
        assertEquals(70, out.get(0).detail().get("level").getAsInt());
    }

    @Test
    void fires99()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        List<EventPayload> out = new ArrayList<>();
        EventWatcher w = watcher(client, mock(ItemManager.class), out);
        w.onStatChanged(stat(Skill.SLAYER, 98));
        w.onStatChanged(stat(Skill.SLAYER, 99));
        assertEquals(1, out.size());
        assertEquals(99, out.get(0).detail().get("level").getAsInt());
    }

    @Test
    void firesOnLocalPlayerDeathOnly()
    {
        Client client = mock(Client.class);
        net.runelite.api.Player me = mock(net.runelite.api.Player.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(me);
        List<EventPayload> out = new ArrayList<>();
        EventWatcher w = watcher(client, mock(ItemManager.class), out);

        ActorDeath other = mock(ActorDeath.class);
        when(other.getActor()).thenReturn(mock(net.runelite.api.NPC.class));
        w.onActorDeath(other);
        assertEquals(0, out.size());

        ActorDeath mine = mock(ActorDeath.class);
        when(mine.getActor()).thenReturn(me);
        w.onActorDeath(mine);
        assertEquals(1, out.size());
        assertEquals("death", out.get(0).kind());
    }

    @Test
    void firesOnDropAtOrAboveThreshold()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        ItemManager items = mock(ItemManager.class);
        when(items.getItemPrice(4151)).thenReturn(2_000_000); // abyssal whip
        net.runelite.api.ItemComposition whip = mock(net.runelite.api.ItemComposition.class);
        when(whip.getName()).thenReturn("Abyssal whip");
        when(items.getItemComposition(4151)).thenReturn(whip);

        List<EventPayload> out = new ArrayList<>();
        EventWatcher w = watcher(client, items, out);

        net.runelite.client.game.ItemStack stack = mock(net.runelite.client.game.ItemStack.class);
        when(stack.getId()).thenReturn(4151);
        when(stack.getQuantity()).thenReturn(1);
        net.runelite.client.events.LootReceived loot = mock(net.runelite.client.events.LootReceived.class);
        when(loot.getItems()).thenReturn(java.util.List.of(stack));
        w.onLootReceived(loot);

        assertEquals(1, out.size());
        assertEquals("drop", out.get(0).kind());
        assertEquals(2_000_000, out.get(0).detail().get("totalValue").getAsLong());
    }

    @Test
    void logoutResetsLevelBaseline()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        List<EventPayload> out = new ArrayList<>();
        EventWatcher w = watcher(client, mock(ItemManager.class), out);

        w.onStatChanged(stat(Skill.ATTACK, 70)); // baseline (first sighting), no fire
        GameStateChanged logout = mock(GameStateChanged.class);
        when(logout.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        w.onGameStateChanged(logout); // clears baseline
        w.onStatChanged(stat(Skill.ATTACK, 70)); // re-baseline after relog, still no fire
        assertEquals(0, out.size());
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `cd plugin && ./gradlew test --tests '*EventWatcherTest'`
Expected: FAIL — `EventWatcher` does not exist.

- [ ] **Step 5: Create `plugin/src/main/java/com/wiseoldclaude/game/EventWatcher.java`**

```java
package com.wiseoldclaude.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wiseoldclaude.ProactiveThrottle;
import com.wiseoldclaude.WiseOldClaudeConfig;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.LootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

/**
 * Detects proactive triggers on the game thread and, when the throttle allows,
 * hands a compact payload to onFire. All @Subscribe handlers run on the client
 * game thread; they only read state and build a payload (no blocking).
 */
public class EventWatcher
{
    private final Client client;
    private final ItemManager itemManager;
    private final WiseOldClaudeConfig config;
    private final ProactiveThrottle throttle;
    private final Consumer<EventPayload> onFire;
    private final Map<Skill, Integer> lastLevels = new EnumMap<>(Skill.class);

    public EventWatcher(Client client, ItemManager itemManager, WiseOldClaudeConfig config,
                        ProactiveThrottle throttle, Consumer<EventPayload> onFire)
    {
        this.client = client;
        this.itemManager = itemManager;
        this.config = config;
        this.throttle = throttle;
        this.onFire = onFire;
    }

    private boolean active()
    {
        return config.proactiveEnabled()
            && client.getGameState() == GameState.LOGGED_IN
            && client.getLocalPlayer() != null;
    }

    private static boolean isMilestone(int level)
    {
        return level == 99 || level % 10 == 0;
    }

    private void fire(EventPayload p)
    {
        if (throttle.tryFire()) onFire.accept(p);
    }

    @Subscribe
    public void onStatChanged(StatChanged e)
    {
        Skill skill = e.getSkill();
        int level = e.getLevel();
        Integer prev = lastLevels.put(skill, level);
        if (!active()) return;
        if (prev != null && level > prev && isMilestone(level))
        {
            JsonObject d = new JsonObject();
            d.addProperty("skill", skill.getName());
            d.addProperty("level", level);
            fire(new EventPayload("level_up", d));
        }
    }

    @Subscribe
    public void onActorDeath(ActorDeath e)
    {
        if (!active()) return;
        if (e.getActor() == client.getLocalPlayer())
        {
            fire(new EventPayload("death", new JsonObject()));
        }
    }

    @Subscribe
    public void onLootReceived(LootReceived e)
    {
        if (!active()) return;
        long total = 0;
        JsonArray items = new JsonArray();
        for (ItemStack s : e.getItems())
        {
            long value = (long) itemManager.getItemPrice(s.getId()) * s.getQuantity();
            total += value;
            JsonObject j = new JsonObject();
            j.addProperty("name", itemManager.getItemComposition(s.getId()).getName());
            j.addProperty("quantity", s.getQuantity());
            j.addProperty("value", value);
            items.add(j);
        }
        if (total >= config.dropValueThreshold())
        {
            JsonObject d = new JsonObject();
            d.add("items", items);
            d.addProperty("totalValue", total);
            fire(new EventPayload("drop", d));
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged e)
    {
        if (e.getGameState() == GameState.LOGIN_SCREEN)
        {
            lastLevels.clear();
        }
    }
}
```

Note the milestone test: `onStatChanged(70)` after a `69` baseline fires because `prev(69) != null && 70 > 69 && isMilestone(70)`. The very first sighting of a skill records the baseline with `prev == null`, so it never fires on login. `Skill.getName()` returns the display name (e.g. "Attack"); if that method is absent in 1.12.33, use `skill.name()` and adjust the test's expected skill string accordingly.

- [ ] **Step 6: Run test to verify it passes**

Run: `cd plugin && ./gradlew test --tests '*EventWatcherTest'`
Expected: PASS (5 tests). If a RuneLite name differs (e.g. `LootReceived.getItems`, `ItemStack` constructor, `Skill.getName`), adjust code + test per the Global Constraints note and re-run.

- [ ] **Step 7: Commit**

```bash
git add plugin/src/main/java/com/wiseoldclaude/game/EventPayload.java plugin/src/main/java/com/wiseoldclaude/game/EventWatcher.java plugin/src/main/java/com/wiseoldclaude/WiseOldClaudeConfig.java plugin/src/test/java/com/wiseoldclaude/game/EventWatcherTest.java
git commit -m "feat(plugin): EventWatcher detects milestone levels, deaths, valuable drops"
```

---

### Task 5: ProactiveDispatcher (worker) + move onToolRequest off the read thread

**Files:**
- Create: `plugin/src/main/java/com/wiseoldclaude/ProactiveDispatcher.java`
- Test: `plugin/src/test/java/com/wiseoldclaude/ProactiveDispatcherTest.java`

**Interfaces:**
- Consumes: `SidecarClient.sendEvent`, `EventPayload`.
- Produces: `ProactiveDispatcher(java.util.concurrent.Executor executor, SidecarClient client, java.util.function.Supplier<String> idGen)` (+ a convenience constructor defaulting `idGen` to random UUIDs); `void dispatch(EventPayload p)`; `void submit(Runnable r)`.

- [ ] **Step 1: Write the failing test `plugin/src/test/java/com/wiseoldclaude/ProactiveDispatcherTest.java`**

```java
package com.wiseoldclaude;

import com.wiseoldclaude.game.EventPayload;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProactiveDispatcherTest
{
    private final java.util.concurrent.Executor inline = Runnable::run;

    @Test
    void dispatchSendsEventWithGeneratedId()
    {
        SidecarClient client = mock(SidecarClient.class);
        ProactiveDispatcher d = new ProactiveDispatcher(inline, client, () -> "fixed-id");
        JsonObject detail = new JsonObject();
        detail.addProperty("level", 70);
        d.dispatch(new EventPayload("level_up", detail));
        verify(client).sendEvent("fixed-id", "level_up", detail);
    }

    @Test
    void submitRunsOnExecutor()
    {
        SidecarClient client = mock(SidecarClient.class);
        ProactiveDispatcher d = new ProactiveDispatcher(inline, client, () -> "x");
        boolean[] ran = {false};
        d.submit(() -> ran[0] = true);
        assertTrue(ran[0]);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd plugin && ./gradlew test --tests '*ProactiveDispatcherTest'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Create `plugin/src/main/java/com/wiseoldclaude/ProactiveDispatcher.java`**

```java
package com.wiseoldclaude;

import com.wiseoldclaude.game.EventPayload;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Runs sidecar I/O on a worker so neither the game thread nor the WebSocket read
 * thread blocks. Sends proactive events, and also carries tool-request handling
 * off the read thread via submit().
 */
public class ProactiveDispatcher
{
    private final Executor executor;
    private final SidecarClient client;
    private final Supplier<String> idGen;

    public ProactiveDispatcher(Executor executor, SidecarClient client)
    {
        this(executor, client, () -> UUID.randomUUID().toString());
    }

    public ProactiveDispatcher(Executor executor, SidecarClient client, Supplier<String> idGen)
    {
        this.executor = executor;
        this.client = client;
        this.idGen = idGen;
    }

    public void dispatch(EventPayload p)
    {
        final String id = idGen.get();
        executor.execute(() -> client.sendEvent(id, p.kind(), p.detail()));
    }

    public void submit(Runnable r)
    {
        executor.execute(r);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd plugin && ./gradlew test --tests '*ProactiveDispatcherTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add plugin/src/main/java/com/wiseoldclaude/ProactiveDispatcher.java plugin/src/test/java/com/wiseoldclaude/ProactiveDispatcherTest.java
git commit -m "feat(plugin): ProactiveDispatcher worker for events + off-thread tool handling"
```

---

### Task 6: Sidecar — proactive turn (`runProactive`, `onEvent`, main wiring)

**Files:**
- Modify: `sidecar/src/agent.ts`
- Modify: `sidecar/src/server.ts`
- Modify: `sidecar/src/main.ts`
- Test: `sidecar/src/agent.test.ts`

**Interfaces:**
- Consumes: existing `Deps`, `ALLOWED_TOOLS`, `SessionCtx`, `runTool`/`buildTools`.
- Produces: `PROACTIVE_SYSTEM_PROMPT`, `proactivePrompt(kind, detail): string`, `runProactive(deps, id, kind, detail, ctx): Promise<void>`; `SidecarServerOpts.onEvent?`.

- [ ] **Step 1: Add failing tests to `sidecar/src/agent.test.ts`**

Append:

```ts
import { proactivePrompt, runProactive } from "./agent.js";

describe("proactive", () => {
  async function* fakeQuery() {
    yield { type: "assistant", message: { content: [{ type: "text", text: "GZ!" }] } };
    yield { type: "result", subtype: "success" };
  }
  function recordingCtx() {
    const events: string[] = [];
    return { events, ctx: {
      sendDelta: (_id: string, t: string) => events.push("delta:" + t),
      sendDone: () => events.push("done"),
      sendError: (_id: string | null, m: string) => events.push("error:" + m),
      bridge: {} as any,
    } };
  }

  it("builds a level_up prompt mentioning the skill and level", () => {
    const p = proactivePrompt("level_up", { skill: "Attack", level: 70 });
    expect(p).toContain("70");
    expect(p).toContain("Attack");
  });

  it("streams a proactive comment then done", async () => {
    const { events, ctx } = recordingCtx();
    await runProactive({ queryFn: fakeQuery as any, mcpServer: {} as any, model: "m" },
      "e1", "death", {}, ctx);
    expect(events).toEqual(["delta:GZ!", "done"]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sidecar && npx vitest run --maxWorkers=2 src/agent.test.ts`
Expected: FAIL — `proactivePrompt`/`runProactive` not exported.

- [ ] **Step 3: Refactor `sidecar/src/agent.ts` to share the stream loop and add the proactive path**

Replace the body of `runChat` with a delegation to a shared helper, and add the proactive exports. The file becomes:

```ts
import type { SessionCtx } from "./server.js";

export const SYSTEM_PROMPT = [
  "You are Wise Old Claude, an Old School RuneScape advisor shown in a side panel.",
  "You have tools that read the player's LIVE game state: get_player_state,",
  "get_inventory, get_nearby_entities. Call them when a question depends on the",
  "player's current situation rather than guessing or asking.",
  "Keep answers short and skimmable — this is a narrow panel, not an essay.",
].join(" ");

export const PROACTIVE_SYSTEM_PROMPT = [
  "You are Wise Old Claude, an Old School RuneScape companion. Something just",
  "happened to the player. React with ONE short, in-character remark (a sentence",
  "or two). You may call your tools to make it specific to their situation, but",
  "keep it brief — this is an unprompted aside, not a lecture.",
].join(" ");

const MCP_PREFIX = "mcp__gielinor__";
export const ALLOWED_TOOLS = [
  "get_player_state",
  "get_inventory",
  "get_nearby_entities",
].map((t) => MCP_PREFIX + t);

type Deps = {
  queryFn: (args: { prompt: string; options: Record<string, unknown> }) => AsyncIterable<unknown>;
  mcpServer: unknown;
  model: string;
};

async function streamAgent(
  deps: Deps, prompt: string, systemPrompt: string, id: string, ctx: SessionCtx,
): Promise<void> {
  try {
    const stream = deps.queryFn({
      prompt,
      options: {
        mcpServers: { gielinor: deps.mcpServer },
        systemPrompt,
        model: deps.model,
        includePartialMessages: false,
        tools: [],
        allowedTools: ALLOWED_TOOLS,
      },
    });
    for await (const msg of stream as AsyncIterable<any>) {
      if (msg?.type === "assistant") {
        for (const block of msg.message?.content ?? []) {
          if (block?.type === "text" && block.text) ctx.sendDelta(id, block.text);
        }
      }
    }
    ctx.sendDone(id);
  } catch (e) {
    ctx.sendError(id, e instanceof Error ? e.message : String(e));
  }
}

export function runChat(deps: Deps, id: string, text: string, ctx: SessionCtx): Promise<void> {
  return streamAgent(deps, text, SYSTEM_PROMPT, id, ctx);
}

export function proactivePrompt(kind: string, detail: Record<string, unknown>): string {
  switch (kind) {
    case "level_up":
      return `The player just reached level ${detail.level} ${detail.skill}. React briefly, in character.`;
    case "death":
      return `The player just died. React briefly, in character — wry or encouraging.`;
    case "drop":
      return `The player just received a valuable drop worth ${detail.totalValue} gp: ${JSON.stringify(detail.items)}. React briefly, in character; you may check their inventory.`;
    default:
      return `Something happened in-game (${kind}). React briefly, in character.`;
  }
}

export function runProactive(
  deps: Deps, id: string, kind: string, detail: Record<string, unknown>, ctx: SessionCtx,
): Promise<void> {
  return streamAgent(deps, proactivePrompt(kind, detail), PROACTIVE_SYSTEM_PROMPT, id, ctx);
}
```

- [ ] **Step 4: Add `onEvent` to `sidecar/src/server.ts`**

In `SidecarServerOpts`, add an optional `onEvent`:

```ts
export type SidecarServerOpts = {
  port: number;
  token: string;
  onChat: (id: string, text: string, ctx: SessionCtx) => void;
  onEvent?: (id: string, kind: string, detail: Record<string, unknown>, ctx: SessionCtx) => void;
};
```

In `onConnection`'s message handler, after the `chat` / `tool_response` branches, add:

```ts
      else if (msg.type === "event") this.opts.onEvent?.(msg.id, msg.kind, msg.detail, ctx);
```

- [ ] **Step 5: Wire `onEvent` in `sidecar/src/main.ts`**

Add `runProactive` to the import from `./agent.js`, and add an `onEvent` handler alongside `onChat` in the `new SidecarServer({...})` options:

```ts
  onEvent: (id, kind, detail, ctx) => {
    const mcpServer = createSdkMcpServer({
      name: "gielinor",
      version: "0.1.0",
      tools: buildTools(ctx.bridge),
    });
    void runProactive({ queryFn: query as any, mcpServer, model }, id, kind, detail, ctx);
  },
```

- [ ] **Step 6: Run tests + typecheck**

Run: `cd sidecar && npx vitest run --maxWorkers=2 && npx tsc --noEmit`
Expected: all tests pass (including the original `runChat` tests, unchanged behavior), tsc clean.

- [ ] **Step 7: Commit**

```bash
git add sidecar/src/agent.ts sidecar/src/server.ts sidecar/src/main.ts sidecar/src/agent.test.ts
git commit -m "feat(sidecar): proactive turn (runProactive) + onEvent routing"
```

---

### Task 7: Plugin wiring — inject EventBus/ItemManager, build watcher, config, off-thread tool handling

**Files:**
- Modify: `plugin/src/main/java/com/wiseoldclaude/WiseOldClaudeConfig.java`
- Modify: `plugin/src/main/java/com/wiseoldclaude/WiseOldClaudePlugin.java`

**Interfaces:**
- Consumes: `EventWatcher`, `ProactiveThrottle`, `ProactiveDispatcher`, `WiseOldClaudeConfig`, RuneLite `EventBus`, `ItemManager`.

- [ ] **Step 1: Add the remaining config key to `WiseOldClaudeConfig.java`**

`proactiveEnabled()` and `dropValueThreshold()` were added in Task 4. Add the cooldown key:

```java
    @ConfigItem(keyName = "proactiveCooldownSeconds", name = "Proactive cooldown (s)", position = 5,
        description = "Minimum seconds between proactive comments")
    default int proactiveCooldownSeconds() { return 60; }
```

- [ ] **Step 2: Modify `WiseOldClaudePlugin.java` — add imports**

```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import com.wiseoldclaude.game.EventWatcher;
```

- [ ] **Step 3: Add fields**

```java
    @Inject private EventBus eventBus;
    @Inject private ItemManager itemManager;
    private ExecutorService worker;
    private ProactiveDispatcher dispatcher;
    private EventWatcher eventWatcher;
```

- [ ] **Step 4: In `startUp()`, build the worker, dispatcher, throttle, and watcher (after `toolRouter` and the reconnect are set up, before/after `reconnect.start()` is fine)**

```java
        worker = Executors.newSingleThreadExecutor();
        dispatcher = new ProactiveDispatcher(worker, client);
        ProactiveThrottle throttle = new ProactiveThrottle(
            config.proactiveCooldownSeconds() * 1000L, System::currentTimeMillis);
        eventWatcher = new EventWatcher(runeliteClient, itemManager, config, throttle,
            payload -> dispatcher.dispatch(payload));
        eventBus.register(eventWatcher);
```

- [ ] **Step 5: In `shutDown()`, unregister and stop the worker**

```java
        if (eventWatcher != null) eventBus.unregister(eventWatcher);
        if (worker != null) worker.shutdownNow();
```

- [ ] **Step 6: Move `onToolRequest` off the read thread**

Replace the `onToolRequest` body so the blocking game-thread read runs on the worker:

```java
    @Override public void onToolRequest(String requestId, String tool, JsonObject args)
    {
        dispatcher.submit(() -> {
            try
            {
                JsonObject data = toolRouter.handle(tool, args);
                client.sendToolResponse(requestId, data);
            }
            catch (RuntimeException e)
            {
                client.sendToolError(requestId, e.getMessage());
            }
        });
    }
```

- [ ] **Step 7: Verify compile + full suites**

Run: `cd plugin && ./gradlew build` and `cd sidecar && npx vitest run --maxWorkers=2 && npx tsc --noEmit`
Expected: plugin BUILD SUCCESSFUL (all tests), sidecar green.

- [ ] **Step 8: Commit**

```bash
git add plugin/src/main/java/com/wiseoldclaude/WiseOldClaudeConfig.java plugin/src/main/java/com/wiseoldclaude/WiseOldClaudePlugin.java
git commit -m "feat(plugin): wire EventWatcher + dispatcher; config keys; off-thread tool handling"
```

---

## Final manual verification (in-client)

1. Start the sidecar (as in v1). 2. Run RuneLite with the plugin; confirm the panel connects. 3. Log in. 4. Trigger a milestone (or lower the drop threshold and pick up a moderately valuable drop) and confirm Claude comments unprompted, contextually. 5. Fire two triggers within 60s and confirm only the first comments (cooldown). 6. Toggle "Proactive comments" off in config and confirm no unprompted comments. 7. Confirm normal chat still works and tool calls still resolve (off-thread handling).
