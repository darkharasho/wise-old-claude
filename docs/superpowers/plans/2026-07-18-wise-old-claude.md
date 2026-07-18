# Wise Old Claude Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a RuneLite plugin with a reactive Claude side chat panel that answers using live game state pulled on demand via tool calls.

**Architecture:** A Node/TypeScript **sidecar** runs the Claude Agent SDK (authenticated with `CLAUDE_CODE_OAUTH_TOKEN`) and hosts three coarse game-state tools as an in-process SDK MCP server. A Java **RuneLite plugin** is a game-state data-provider plus a Swing chat panel. They talk over a localhost WebSocket: chat text and streamed answers flow one way, tool-data requests/responses the other. Game state is always read on the client game thread.

**Tech Stack:** TypeScript (Node ≥ 20, ESM), `@anthropic-ai/claude-agent-sdk`, `ws`, `zod`, `vitest`; Java 11, Gradle, RuneLite `net.runelite:client`, `org.java-websocket:Java-WebSocket`, Gson, Lombok, JUnit 5 + Mockito.

## Global Constraints

- **Monorepo layout:** `sidecar/` (Node/TS), `plugin/` (Java/Gradle), `docs/`.
- **Node:** ≥ 20, ESM (`"type": "module"`), TypeScript strict.
- **vitest:** run with `--maxWorkers=2` (machine memory limit — from CLAUDE.md).
- **Java:** 11 (RuneLite's target). Gradle.
- **WebSocket:** server binds `127.0.0.1` only. Default port **8137** (env `WOC_PORT`).
- **Handshake token:** shared secret. Sidecar reads env `WOC_TOKEN`; plugin reads it from RuneLite config. Sidecar rejects any connection whose `hello` token mismatches.
- **Auth:** sidecar reads `CLAUDE_CODE_OAUTH_TOKEN` from its own environment and passes it into the Agent SDK subprocess env. The plugin never sees it.
- **Default model:** `claude-sonnet-4-6` (env `WOC_MODEL` overrides).
- **Protocol:** JSON objects, one per WebSocket text frame, discriminated by a `type` field. Message shapes are frozen in Task 1 (TS) / Task 5 (Java) and must match byte-for-byte on field names.
- **Reactive only:** no game-event subscriptions in v1.
- **Sidecar lifecycle:** started manually in v1 (no auto-spawn).

---

## Protocol reference (frozen — both sides must match)

Field names are identical across TS and Java.

Plugin → sidecar:
- `{ "type": "hello", "token": string }`
- `{ "type": "chat", "id": string, "text": string }`
- `{ "type": "tool_response", "requestId": string, "data": object }` or `{ "type": "tool_response", "requestId": string, "error": string }`

Sidecar → plugin:
- `{ "type": "hello_ok" }` or `{ "type": "hello_reject", "reason": string }`
- `{ "type": "assistant_delta", "id": string, "text": string }`
- `{ "type": "assistant_done", "id": string }`
- `{ "type": "tool_request", "requestId": string, "tool": string, "args": object }`
- `{ "type": "error", "id": string | null, "message": string }`

---

# Phase A — Sidecar foundation

### Task 1: Sidecar scaffold + protocol

**Files:**
- Create: `sidecar/package.json`
- Create: `sidecar/tsconfig.json`
- Create: `sidecar/vitest.config.ts`
- Create: `sidecar/src/protocol.ts`
- Test: `sidecar/src/protocol.test.ts`

**Interfaces:**
- Produces: TypeScript types `PluginToSidecar`, `SidecarToPlugin`; `parseMessage(raw: string): PluginToSidecar`; `serialize(msg: SidecarToPlugin): string`.

- [ ] **Step 1: Create `sidecar/package.json`**

```json
{
  "name": "wise-old-claude-sidecar",
  "version": "0.1.0",
  "type": "module",
  "private": true,
  "scripts": {
    "build": "tsc",
    "start": "node dist/main.js",
    "dev": "tsx src/main.ts",
    "test": "vitest run --maxWorkers=2"
  },
  "dependencies": {
    "@anthropic-ai/claude-agent-sdk": "^0.3.196",
    "ws": "^8.18.0",
    "zod": "^3.23.8"
  },
  "devDependencies": {
    "@types/node": "^20.14.0",
    "@types/ws": "^8.5.10",
    "tsx": "^4.16.0",
    "typescript": "^5.5.0",
    "vitest": "^2.0.0"
  }
}
```

- [ ] **Step 2: Create `sidecar/tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ES2022",
    "moduleResolution": "bundler",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "outDir": "dist",
    "rootDir": "src"
  },
  "include": ["src"]
}
```

- [ ] **Step 3: Create `sidecar/vitest.config.ts`**

```ts
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    pool: "forks",
    poolOptions: { forks: { maxForks: 2 } },
  },
});
```

- [ ] **Step 4: Write the failing test `sidecar/src/protocol.test.ts`**

```ts
import { describe, it, expect } from "vitest";
import { parseMessage, serialize } from "./protocol.js";

describe("protocol", () => {
  it("parses a chat message", () => {
    const msg = parseMessage(JSON.stringify({ type: "chat", id: "1", text: "hi" }));
    expect(msg).toEqual({ type: "chat", id: "1", text: "hi" });
  });

  it("parses a tool_response with data", () => {
    const raw = JSON.stringify({ type: "tool_response", requestId: "r1", data: { hp: 99 } });
    expect(parseMessage(raw)).toEqual({ type: "tool_response", requestId: "r1", data: { hp: 99 } });
  });

  it("rejects an unknown type", () => {
    expect(() => parseMessage(JSON.stringify({ type: "nope" }))).toThrow();
  });

  it("serializes an assistant_delta", () => {
    const s = serialize({ type: "assistant_delta", id: "1", text: "he" });
    expect(JSON.parse(s)).toEqual({ type: "assistant_delta", id: "1", text: "he" });
  });
});
```

- [ ] **Step 5: Run test to verify it fails**

Run: `cd sidecar && npm install && npx vitest run --maxWorkers=2 src/protocol.test.ts`
Expected: FAIL — cannot resolve `./protocol.js`.

- [ ] **Step 6: Create `sidecar/src/protocol.ts`**

```ts
export type Hello = { type: "hello"; token: string };
export type Chat = { type: "chat"; id: string; text: string };
export type ToolResponse = {
  type: "tool_response";
  requestId: string;
  data?: Record<string, unknown>;
  error?: string;
};
export type PluginToSidecar = Hello | Chat | ToolResponse;

export type HelloOk = { type: "hello_ok" };
export type HelloReject = { type: "hello_reject"; reason: string };
export type AssistantDelta = { type: "assistant_delta"; id: string; text: string };
export type AssistantDone = { type: "assistant_done"; id: string };
export type ToolRequest = {
  type: "tool_request";
  requestId: string;
  tool: string;
  args: Record<string, unknown>;
};
export type ErrorMsg = { type: "error"; id: string | null; message: string };
export type SidecarToPlugin =
  | HelloOk
  | HelloReject
  | AssistantDelta
  | AssistantDone
  | ToolRequest
  | ErrorMsg;

const INBOUND = new Set(["hello", "chat", "tool_response"]);

export function parseMessage(raw: string): PluginToSidecar {
  const obj = JSON.parse(raw) as { type?: string };
  if (!obj || typeof obj.type !== "string" || !INBOUND.has(obj.type)) {
    throw new Error(`unknown inbound message type: ${obj?.type}`);
  }
  return obj as PluginToSidecar;
}

export function serialize(msg: SidecarToPlugin): string {
  return JSON.stringify(msg);
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `cd sidecar && npx vitest run --maxWorkers=2 src/protocol.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 8: Commit**

```bash
git add sidecar/package.json sidecar/tsconfig.json sidecar/vitest.config.ts sidecar/src/protocol.ts sidecar/src/protocol.test.ts sidecar/package-lock.json
git commit -m "feat(sidecar): scaffold + wire protocol types"
```

---

### Task 2: Tool bridge (request/response correlation + timeout)

**Files:**
- Create: `sidecar/src/toolBridge.ts`
- Test: `sidecar/src/toolBridge.test.ts`

**Interfaces:**
- Consumes: `ToolRequest` shape from `protocol.ts`.
- Produces: `class ToolBridge` with `request(tool: string, args: Record<string, unknown>, timeoutMs?: number): Promise<Record<string, unknown>>`, `resolve(requestId: string, data?: Record<string, unknown>, error?: string): void`, and constructor `(send: (req: ToolRequest) => void, idGen?: () => string)`.

- [ ] **Step 1: Write the failing test `sidecar/src/toolBridge.test.ts`**

```ts
import { describe, it, expect, vi } from "vitest";
import { ToolBridge } from "./toolBridge.js";

describe("ToolBridge", () => {
  it("resolves a request when a matching response arrives", async () => {
    let sent: any;
    const bridge = new ToolBridge((req) => (sent = req), () => "req-1");
    const p = bridge.request("get_inventory", {});
    expect(sent).toEqual({ type: "tool_request", requestId: "req-1", tool: "get_inventory", args: {} });
    bridge.resolve("req-1", { items: [] });
    await expect(p).resolves.toEqual({ items: [] });
  });

  it("rejects when the response carries an error", async () => {
    const bridge = new ToolBridge(() => {}, () => "req-2");
    const p = bridge.request("get_player_state", {});
    bridge.resolve("req-2", undefined, "not logged in");
    await expect(p).rejects.toThrow("not logged in");
  });

  it("rejects on timeout", async () => {
    vi.useFakeTimers();
    const bridge = new ToolBridge(() => {}, () => "req-3");
    const p = bridge.request("get_player_state", {}, 1000);
    const assertion = expect(p).rejects.toThrow("timed out");
    await vi.advanceTimersByTimeAsync(1001);
    await assertion;
    vi.useRealTimers();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sidecar && npx vitest run --maxWorkers=2 src/toolBridge.test.ts`
Expected: FAIL — cannot resolve `./toolBridge.js`.

- [ ] **Step 3: Create `sidecar/src/toolBridge.ts`**

```ts
import { randomUUID } from "node:crypto";
import type { ToolRequest } from "./protocol.js";

type Pending = {
  resolve: (data: Record<string, unknown>) => void;
  reject: (err: Error) => void;
  timer: NodeJS.Timeout;
};

export class ToolBridge {
  private pending = new Map<string, Pending>();

  constructor(
    private send: (req: ToolRequest) => void,
    private idGen: () => string = () => randomUUID(),
  ) {}

  request(
    tool: string,
    args: Record<string, unknown>,
    timeoutMs = 5000,
  ): Promise<Record<string, unknown>> {
    const requestId = this.idGen();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        reject(new Error(`tool ${tool} timed out after ${timeoutMs}ms`));
      }, timeoutMs);
      this.pending.set(requestId, { resolve, reject, timer });
      this.send({ type: "tool_request", requestId, tool, args });
    });
  }

  resolve(requestId: string, data?: Record<string, unknown>, error?: string): void {
    const p = this.pending.get(requestId);
    if (!p) return;
    clearTimeout(p.timer);
    this.pending.delete(requestId);
    if (error) p.reject(new Error(error));
    else p.resolve(data ?? {});
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd sidecar && npx vitest run --maxWorkers=2 src/toolBridge.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add sidecar/src/toolBridge.ts sidecar/src/toolBridge.test.ts
git commit -m "feat(sidecar): tool request/response bridge with timeout"
```

---

### Task 3: WebSocket server with handshake + echo

**Files:**
- Create: `sidecar/src/server.ts`
- Test: `sidecar/src/server.test.ts`

**Interfaces:**
- Consumes: `parseMessage`, `serialize` (protocol), `ToolBridge`.
- Produces: `class SidecarServer` with constructor `({ port, token, onChat })` where `onChat: (id: string, text: string, ctx: SessionCtx) => void`; `SessionCtx = { sendDelta(id, text), sendDone(id), sendError(id, message), bridge: ToolBridge }`; methods `start(): Promise<void>`, `stop(): Promise<void>`.

- [ ] **Step 1: Write the failing test `sidecar/src/server.test.ts`**

```ts
import { describe, it, expect, afterEach } from "vitest";
import WebSocket from "ws";
import { SidecarServer } from "./server.js";

let server: SidecarServer | undefined;
afterEach(async () => { await server?.stop(); server = undefined; });

function connect(port: number): Promise<WebSocket> {
  return new Promise((res) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}`);
    ws.on("open", () => res(ws));
  });
}
function next(ws: WebSocket): Promise<any> {
  return new Promise((res) => ws.once("message", (d) => res(JSON.parse(d.toString()))));
}

describe("SidecarServer", () => {
  it("accepts a good token and echoes chat via onChat", async () => {
    server = new SidecarServer({
      port: 0, token: "secret",
      onChat: (id, text, ctx) => { ctx.sendDelta(id, text.toUpperCase()); ctx.sendDone(id); },
    });
    await server.start();
    const ws = await connect(server.port);
    ws.send(JSON.stringify({ type: "hello", token: "secret" }));
    expect(await next(ws)).toEqual({ type: "hello_ok" });
    ws.send(JSON.stringify({ type: "chat", id: "1", text: "hi" }));
    expect(await next(ws)).toEqual({ type: "assistant_delta", id: "1", text: "HI" });
    expect(await next(ws)).toEqual({ type: "assistant_done", id: "1" });
    ws.close();
  });

  it("rejects a bad token", async () => {
    server = new SidecarServer({ port: 0, token: "secret", onChat: () => {} });
    await server.start();
    const ws = await connect(server.port);
    ws.send(JSON.stringify({ type: "hello", token: "wrong" }));
    expect(await next(ws)).toEqual({ type: "hello_reject", reason: "bad token" });
    ws.close();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sidecar && npx vitest run --maxWorkers=2 src/server.test.ts`
Expected: FAIL — cannot resolve `./server.js`.

- [ ] **Step 3: Create `sidecar/src/server.ts`**

```ts
import { WebSocketServer, WebSocket } from "ws";
import { parseMessage, serialize, type SidecarToPlugin } from "./protocol.js";
import { ToolBridge } from "./toolBridge.js";

export type SessionCtx = {
  sendDelta(id: string, text: string): void;
  sendDone(id: string): void;
  sendError(id: string | null, message: string): void;
  bridge: ToolBridge;
};

export type SidecarServerOpts = {
  port: number;
  token: string;
  onChat: (id: string, text: string, ctx: SessionCtx) => void;
};

export class SidecarServer {
  private wss?: WebSocketServer;
  public port = 0;

  constructor(private opts: SidecarServerOpts) {}

  start(): Promise<void> {
    return new Promise((resolve) => {
      this.wss = new WebSocketServer({ host: "127.0.0.1", port: this.opts.port });
      this.wss.on("listening", () => {
        this.port = (this.wss!.address() as { port: number }).port;
        resolve();
      });
      this.wss.on("connection", (ws) => this.onConnection(ws));
    });
  }

  private onConnection(ws: WebSocket): void {
    let authed = false;
    const send = (m: SidecarToPlugin) => ws.send(serialize(m));
    const bridge = new ToolBridge((req) => send(req));
    const ctx: SessionCtx = {
      sendDelta: (id, text) => send({ type: "assistant_delta", id, text }),
      sendDone: (id) => send({ type: "assistant_done", id }),
      sendError: (id, message) => send({ type: "error", id, message }),
      bridge,
    };

    ws.on("message", (raw) => {
      let msg;
      try {
        msg = parseMessage(raw.toString());
      } catch {
        return; // ignore malformed frames
      }
      if (msg.type === "hello") {
        authed = msg.token === this.opts.token;
        send(authed ? { type: "hello_ok" } : { type: "hello_reject", reason: "bad token" });
        return;
      }
      if (!authed) return;
      if (msg.type === "chat") this.opts.onChat(msg.id, msg.text, ctx);
      else if (msg.type === "tool_response") bridge.resolve(msg.requestId, msg.data, msg.error);
    });
  }

  stop(): Promise<void> {
    return new Promise((resolve) => {
      if (!this.wss) return resolve();
      this.wss.close(() => resolve());
    });
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd sidecar && npx vitest run --maxWorkers=2 src/server.test.ts`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add sidecar/src/server.ts sidecar/src/server.test.ts
git commit -m "feat(sidecar): websocket server with token handshake and chat routing"
```

---

# Phase B — Plugin foundation

### Task 4: Plugin Gradle scaffold + config + descriptor

**Files:**
- Create: `plugin/build.gradle`
- Create: `plugin/settings.gradle`
- Create: `plugin/src/main/java/com/wiseoldclaude/WiseOldClaudeConfig.java`
- Create: `plugin/src/main/java/com/wiseoldclaude/WiseOldClaudePlugin.java`

**Interfaces:**
- Produces: `WiseOldClaudeConfig` with `sidecarHost()`, `sidecarPort()`, `token()`; `WiseOldClaudePlugin` (RuneLite entry point, empty behavior for now).

- [ ] **Step 1: Create `plugin/settings.gradle`**

```groovy
rootProject.name = 'wise-old-claude'
```

- [ ] **Step 2: Create `plugin/build.gradle`**

```groovy
plugins {
    id 'java'
}

repositories {
    mavenCentral()
    maven { url = 'https://repo.runelite.net' }
}

def runeLiteVersion = 'latest.release'

dependencies {
    compileOnly group: 'net.runelite', name: 'client', version: runeLiteVersion
    compileOnly 'org.projectlombok:lombok:1.18.34'
    annotationProcessor 'org.projectlombok:lombok:1.18.34'

    implementation 'org.java-websocket:Java-WebSocket:1.5.7'

    testImplementation group: 'net.runelite', name: 'client', version: runeLiteVersion
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testImplementation 'org.mockito:mockito-core:5.12.0'
    testImplementation 'com.google.code.gson:gson:2.10.1'
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

test {
    useJUnitPlatform()
}
```

- [ ] **Step 3: Create `plugin/src/main/java/com/wiseoldclaude/WiseOldClaudeConfig.java`**

```java
package com.wiseoldclaude;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("wiseoldclaude")
public interface WiseOldClaudeConfig extends Config
{
    @ConfigItem(keyName = "sidecarHost", name = "Sidecar host", position = 1,
        description = "Host the sidecar listens on")
    default String sidecarHost() { return "127.0.0.1"; }

    @ConfigItem(keyName = "sidecarPort", name = "Sidecar port", position = 2,
        description = "Port the sidecar listens on")
    default int sidecarPort() { return 8137; }

    @ConfigItem(keyName = "token", name = "Handshake token", position = 3, secret = true,
        description = "Shared secret matching the sidecar's WOC_TOKEN")
    default String token() { return ""; }
}
```

- [ ] **Step 4: Create `plugin/src/main/java/com/wiseoldclaude/WiseOldClaudePlugin.java`**

```java
package com.wiseoldclaude;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(name = "Wise Old Claude", description = "Chat with Claude using live game state")
public class WiseOldClaudePlugin extends Plugin
{
    @Inject private WiseOldClaudeConfig config;

    @Provides
    WiseOldClaudeConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(WiseOldClaudeConfig.class);
    }

    @Override
    protected void startUp()
    {
        log.info("Wise Old Claude starting (sidecar {}:{})", config.sidecarHost(), config.sidecarPort());
    }

    @Override
    protected void shutDown()
    {
        log.info("Wise Old Claude stopping");
    }
}
```

- [ ] **Step 5: Verify it compiles**

Run: `cd plugin && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL. (If no Gradle wrapper exists yet, run `gradle wrapper` first.)

- [ ] **Step 6: Commit**

```bash
git add plugin/build.gradle plugin/settings.gradle plugin/gradlew plugin/gradlew.bat plugin/gradle plugin/src/main/java/com/wiseoldclaude/WiseOldClaudeConfig.java plugin/src/main/java/com/wiseoldclaude/WiseOldClaudePlugin.java
git commit -m "feat(plugin): gradle scaffold, config, plugin descriptor"
```

---

### Task 5: Protocol DTOs (Java)

**Files:**
- Create: `plugin/src/main/java/com/wiseoldclaude/protocol/Messages.java`
- Create: `plugin/src/main/java/com/wiseoldclaude/protocol/ProtocolCodec.java`
- Test: `plugin/src/test/java/com/wiseoldclaude/protocol/ProtocolCodecTest.java`

**Interfaces:**
- Produces: `ProtocolCodec` (Gson-backed) with `String hello(String token)`, `String chat(String id, String text)`, `String toolResponse(String requestId, JsonObject data)`, `String toolError(String requestId, String error)`, and `Inbound parse(String raw)` where `Inbound` exposes `type` plus typed accessors.

- [ ] **Step 1: Write the failing test `plugin/src/test/java/com/wiseoldclaude/protocol/ProtocolCodecTest.java`**

```java
package com.wiseoldclaude.protocol;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolCodecTest
{
    private final ProtocolCodec codec = new ProtocolCodec();

    @Test
    void buildsChat()
    {
        assertEquals("{\"type\":\"chat\",\"id\":\"1\",\"text\":\"hi\"}", codec.chat("1", "hi"));
    }

    @Test
    void buildsToolResponse()
    {
        JsonObject data = new JsonObject();
        data.addProperty("hp", 99);
        assertEquals("{\"type\":\"tool_response\",\"requestId\":\"r1\",\"data\":{\"hp\":99}}",
            codec.toolResponse("r1", data));
    }

    @Test
    void parsesToolRequest()
    {
        ProtocolCodec.Inbound in = codec.parse(
            "{\"type\":\"tool_request\",\"requestId\":\"r1\",\"tool\":\"get_inventory\",\"args\":{}}");
        assertEquals("tool_request", in.type());
        assertEquals("r1", in.requestId());
        assertEquals("get_inventory", in.tool());
    }

    @Test
    void parsesAssistantDelta()
    {
        ProtocolCodec.Inbound in = codec.parse(
            "{\"type\":\"assistant_delta\",\"id\":\"1\",\"text\":\"he\"}");
        assertEquals("assistant_delta", in.type());
        assertEquals("1", in.id());
        assertEquals("he", in.text());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd plugin && ./gradlew test --tests '*ProtocolCodecTest'`
Expected: FAIL — `ProtocolCodec` does not exist.

- [ ] **Step 3: Create `plugin/src/main/java/com/wiseoldclaude/protocol/Messages.java`**

```java
package com.wiseoldclaude.protocol;

/** Marker for message type-name constants shared by the codec. */
public final class Messages
{
    public static final String HELLO = "hello";
    public static final String CHAT = "chat";
    public static final String TOOL_RESPONSE = "tool_response";
    public static final String HELLO_OK = "hello_ok";
    public static final String HELLO_REJECT = "hello_reject";
    public static final String ASSISTANT_DELTA = "assistant_delta";
    public static final String ASSISTANT_DONE = "assistant_done";
    public static final String TOOL_REQUEST = "tool_request";
    public static final String ERROR = "error";

    private Messages() {}
}
```

- [ ] **Step 4: Create `plugin/src/main/java/com/wiseoldclaude/protocol/ProtocolCodec.java`**

```java
package com.wiseoldclaude.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ProtocolCodec
{
    private final Gson gson = new Gson();

    public String hello(String token)
    {
        JsonObject o = new JsonObject();
        o.addProperty("type", Messages.HELLO);
        o.addProperty("token", token);
        return gson.toJson(o);
    }

    public String chat(String id, String text)
    {
        JsonObject o = new JsonObject();
        o.addProperty("type", Messages.CHAT);
        o.addProperty("id", id);
        o.addProperty("text", text);
        return gson.toJson(o);
    }

    public String toolResponse(String requestId, JsonObject data)
    {
        JsonObject o = new JsonObject();
        o.addProperty("type", Messages.TOOL_RESPONSE);
        o.addProperty("requestId", requestId);
        o.add("data", data);
        return gson.toJson(o);
    }

    public String toolError(String requestId, String error)
    {
        JsonObject o = new JsonObject();
        o.addProperty("type", Messages.TOOL_RESPONSE);
        o.addProperty("requestId", requestId);
        o.addProperty("error", error);
        return gson.toJson(o);
    }

    public Inbound parse(String raw)
    {
        return new Inbound(JsonParser.parseString(raw).getAsJsonObject());
    }

    /** Read-only view over an inbound message. */
    public static final class Inbound
    {
        private final JsonObject o;

        Inbound(JsonObject o) { this.o = o; }

        private String str(String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }

        public String type() { return str("type"); }
        public String id() { return str("id"); }
        public String text() { return str("text"); }
        public String requestId() { return str("requestId"); }
        public String tool() { return str("tool"); }
        public String message() { return str("message"); }
        public JsonObject args() { return o.has("args") ? o.getAsJsonObject("args") : new JsonObject(); }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd plugin && ./gradlew test --tests '*ProtocolCodecTest'`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/java/com/wiseoldclaude/protocol/ plugin/src/test/java/com/wiseoldclaude/protocol/
git commit -m "feat(plugin): protocol codec (Gson)"
```

---

### Task 6: SidecarClient (WebSocket client + dispatch)

**Files:**
- Create: `plugin/src/main/java/com/wiseoldclaude/SidecarClient.java`
- Create: `plugin/src/main/java/com/wiseoldclaude/SidecarListener.java`
- Test: `plugin/src/test/java/com/wiseoldclaude/SidecarClientTest.java`

**Interfaces:**
- Consumes: `ProtocolCodec`.
- Produces: `interface SidecarListener { void onDelta(String id, String text); void onDone(String id); void onToolRequest(String requestId, String tool, JsonObject args); void onError(String id, String message); void onConnected(); void onDisconnected(); }`; `class SidecarClient` with `void dispatch(String raw)` (package-visible for tests), `void sendChat(String id, String text)`, `void sendToolResponse(String requestId, JsonObject data)`, `void sendToolError(String requestId, String error)`.

- [ ] **Step 1: Write the failing test `plugin/src/test/java/com/wiseoldclaude/SidecarClientTest.java`**

```java
package com.wiseoldclaude;

import com.google.gson.JsonObject;
import com.wiseoldclaude.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SidecarClientTest
{
    private final List<String> sent = new ArrayList<>();

    private SidecarClient client(SidecarListener l)
    {
        // Constructor variant that captures outgoing frames instead of opening a socket.
        return new SidecarClient(new ProtocolCodec(), l, sent::add);
    }

    @Test
    void dispatchesDeltaToListener()
    {
        List<String> deltas = new ArrayList<>();
        SidecarClient c = client(new NoopListener() {
            @Override public void onDelta(String id, String text) { deltas.add(id + ":" + text); }
        });
        c.dispatch("{\"type\":\"assistant_delta\",\"id\":\"1\",\"text\":\"hi\"}");
        assertEquals(List.of("1:hi"), deltas);
    }

    @Test
    void dispatchesToolRequestToListener()
    {
        List<String> reqs = new ArrayList<>();
        SidecarClient c = client(new NoopListener() {
            @Override public void onToolRequest(String requestId, String tool, JsonObject args) {
                reqs.add(requestId + ":" + tool);
            }
        });
        c.dispatch("{\"type\":\"tool_request\",\"requestId\":\"r1\",\"tool\":\"get_player_state\",\"args\":{}}");
        assertEquals(List.of("r1:get_player_state"), reqs);
    }

    @Test
    void sendChatEmitsChatFrame()
    {
        SidecarClient c = client(new NoopListener());
        c.sendChat("1", "hello");
        assertEquals("{\"type\":\"chat\",\"id\":\"1\",\"text\":\"hello\"}", sent.get(0));
    }

    static class NoopListener implements SidecarListener {
        @Override public void onDelta(String id, String text) {}
        @Override public void onDone(String id) {}
        @Override public void onToolRequest(String requestId, String tool, JsonObject args) {}
        @Override public void onError(String id, String message) {}
        @Override public void onConnected() {}
        @Override public void onDisconnected() {}
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd plugin && ./gradlew test --tests '*SidecarClientTest'`
Expected: FAIL — `SidecarClient` / `SidecarListener` do not exist.

- [ ] **Step 3: Create `plugin/src/main/java/com/wiseoldclaude/SidecarListener.java`**

```java
package com.wiseoldclaude;

import com.google.gson.JsonObject;

public interface SidecarListener
{
    void onDelta(String id, String text);
    void onDone(String id);
    void onToolRequest(String requestId, String tool, JsonObject args);
    void onError(String id, String message);
    void onConnected();
    void onDisconnected();
}
```

- [ ] **Step 4: Create `plugin/src/main/java/com/wiseoldclaude/SidecarClient.java`**

```java
package com.wiseoldclaude;

import com.google.gson.JsonObject;
import com.wiseoldclaude.protocol.Messages;
import com.wiseoldclaude.protocol.ProtocolCodec;
import java.net.URI;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

@Slf4j
public class SidecarClient
{
    private final ProtocolCodec codec;
    private final SidecarListener listener;
    private final Consumer<String> sink;
    private WebSocketClient ws;
    private String token = "";

    /** Production constructor: frames go out over a real socket once connected. */
    public SidecarClient(ProtocolCodec codec, SidecarListener listener)
    {
        this(codec, listener, null);
        this.sink = this::rawSend;
    }

    /** Test/seam constructor: frames go to the supplied sink. */
    SidecarClient(ProtocolCodec codec, SidecarListener listener, Consumer<String> sink)
    {
        this.codec = codec;
        this.listener = listener;
        this.sink = sink;
    }

    public void connect(String host, int port, String token)
    {
        this.token = token;
        ws = new WebSocketClient(URI.create("ws://" + host + ":" + port))
        {
            @Override public void onOpen(ServerHandshake h) { rawSend(codec.hello(token)); }
            @Override public void onMessage(String message) { dispatch(message); }
            @Override public void onClose(int code, String reason, boolean remote) { listener.onDisconnected(); }
            @Override public void onError(Exception ex) { log.warn("sidecar ws error", ex); }
        };
        ws.connect();
    }

    public void close()
    {
        if (ws != null) ws.close();
    }

    void dispatch(String raw)
    {
        ProtocolCodec.Inbound in;
        try { in = codec.parse(raw); } catch (RuntimeException e) { return; }
        String type = in.type();
        if (type == null) return;
        switch (type)
        {
            case Messages.HELLO_OK: listener.onConnected(); break;
            case Messages.HELLO_REJECT: listener.onError(null, "sidecar rejected token"); break;
            case Messages.ASSISTANT_DELTA: listener.onDelta(in.id(), in.text()); break;
            case Messages.ASSISTANT_DONE: listener.onDone(in.id()); break;
            case Messages.TOOL_REQUEST: listener.onToolRequest(in.requestId(), in.tool(), in.args()); break;
            case Messages.ERROR: listener.onError(in.id(), in.message()); break;
            default: break;
        }
    }

    public void sendChat(String id, String text) { sink.accept(codec.chat(id, text)); }
    public void sendToolResponse(String requestId, JsonObject data) { sink.accept(codec.toolResponse(requestId, data)); }
    public void sendToolError(String requestId, String error) { sink.accept(codec.toolError(requestId, error)); }

    private void rawSend(String frame)
    {
        if (ws != null && ws.isOpen()) ws.send(frame);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd plugin && ./gradlew test --tests '*SidecarClientTest'`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/java/com/wiseoldclaude/SidecarClient.java plugin/src/main/java/com/wiseoldclaude/SidecarListener.java plugin/src/test/java/com/wiseoldclaude/SidecarClientTest.java
git commit -m "feat(plugin): websocket sidecar client with message dispatch"
```

---

### Task 7: Chat panel (Swing) wired to the client

**Files:**
- Create: `plugin/src/main/java/com/wiseoldclaude/WiseOldClaudePanel.java`
- Modify: `plugin/src/main/java/com/wiseoldclaude/WiseOldClaudePlugin.java`

**Interfaces:**
- Consumes: `SidecarClient`, `SidecarListener`.
- Produces: `WiseOldClaudePanel extends PluginPanel implements SidecarListener` with `void setSubmitHandler(Consumer<String> onSubmit)`; the plugin creates the panel, a `SidecarClient`, wires submit → `sendChat`, and registers the panel via `ClientToolbar`.

- [ ] **Step 1: Create `plugin/src/main/java/com/wiseoldclaude/WiseOldClaudePanel.java`**

```java
package com.wiseoldclaude;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.util.function.Consumer;
import javax.swing.*;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.SwingUtil;

public class WiseOldClaudePanel extends PluginPanel implements SidecarListener
{
    private final JTextArea transcript = new JTextArea();
    private final JTextField input = new JTextField();
    private final JLabel status = new JLabel("Disconnected");
    private Consumer<String> onSubmit = t -> {};
    private String streamingId = null;

    public WiseOldClaudePanel()
    {
        setLayout(new BorderLayout());
        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        status.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);

        add(status, BorderLayout.NORTH);
        add(new JScrollPane(transcript), BorderLayout.CENTER);
        add(input, BorderLayout.SOUTH);

        input.addActionListener(e -> {
            String text = input.getText().trim();
            if (text.isEmpty()) return;
            append("You: " + text + "\n");
            input.setText("");
            onSubmit.accept(text);
        });
    }

    public void setSubmitHandler(Consumer<String> onSubmit) { this.onSubmit = onSubmit; }

    private void append(String s)
    {
        SwingUtilities.invokeLater(() -> transcript.append(s));
    }

    // SidecarListener — all UI mutations bounce onto the EDT.
    @Override public void onConnected()
    {
        SwingUtilities.invokeLater(() -> {
            status.setText("Connected");
            status.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
        });
    }

    @Override public void onDisconnected()
    {
        SwingUtilities.invokeLater(() -> {
            status.setText("Disconnected");
            status.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
        });
    }

    @Override public void onDelta(String id, String text)
    {
        if (!id.equals(streamingId)) { append("\nClaude: "); streamingId = id; }
        append(text);
    }

    @Override public void onDone(String id) { streamingId = null; append("\n\n"); }

    @Override public void onError(String id, String message) { append("\n[error] " + message + "\n"); }

    // Not a UI concern; handled by the plugin's tool router.
    @Override public void onToolRequest(String requestId, String tool, JsonObject args) {}
}
```

- [ ] **Step 2: Modify `WiseOldClaudePlugin.java` to wire the panel and client**

Replace the class body with:

```java
package com.wiseoldclaude;

import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.UUID;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import com.wiseoldclaude.protocol.ProtocolCodec;

@Slf4j
@PluginDescriptor(name = "Wise Old Claude", description = "Chat with Claude using live game state")
public class WiseOldClaudePlugin extends Plugin implements SidecarListener
{
    @Inject private WiseOldClaudeConfig config;
    @Inject private ClientToolbar clientToolbar;

    private WiseOldClaudePanel panel;
    private SidecarClient client;
    private NavigationButton navButton;

    @Provides
    WiseOldClaudeConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(WiseOldClaudeConfig.class);
    }

    @Override
    protected void startUp()
    {
        panel = new WiseOldClaudePanel();
        client = new SidecarClient(new ProtocolCodec(), this);
        panel.setSubmitHandler(text -> client.sendChat(UUID.randomUUID().toString(), text));

        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        navButton = NavigationButton.builder().tooltip("Wise Old Claude").icon(icon).panel(panel).build();
        clientToolbar.addNavigation(navButton);

        client.connect(config.sidecarHost(), config.sidecarPort(), config.token());
    }

    @Override
    protected void shutDown()
    {
        if (client != null) client.close();
        if (navButton != null) clientToolbar.removeNavigation(navButton);
    }

    // SidecarListener — chat events forward to the panel; tool requests handled in Task 11.
    @Override public void onDelta(String id, String text) { panel.onDelta(id, text); }
    @Override public void onDone(String id) { panel.onDone(id); }
    @Override public void onError(String id, String message) { panel.onError(id, message); }
    @Override public void onConnected() { panel.onConnected(); }
    @Override public void onDisconnected() { panel.onDisconnected(); }
    @Override public void onToolRequest(String requestId, String tool, JsonObject args)
    {
        // Filled in Task 11.
        client.sendToolError(requestId, "no tools wired yet");
    }
}
```

Here `client` is the `SidecarClient` field. Task 11 introduces the RuneLite `Client` under a distinct field name (`runeliteClient`) so the two never collide.

- [ ] **Step 3: Verify it compiles**

Run: `cd plugin && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add plugin/src/main/java/com/wiseoldclaude/WiseOldClaudePanel.java plugin/src/main/java/com/wiseoldclaude/WiseOldClaudePlugin.java
git commit -m "feat(plugin): swing chat panel wired to sidecar client"
```

---

# Phase C — Claude wiring

### Task 8: Agent driver (system prompt + stream translation)

**Files:**
- Create: `sidecar/src/agent.ts`
- Test: `sidecar/src/agent.test.ts`

**Interfaces:**
- Consumes: `SessionCtx` (server), the Agent SDK `query`.
- Produces: `function runChat(deps: { queryFn, mcpServer, model }, id: string, text: string, ctx: SessionCtx): Promise<void>`, where `queryFn` matches the SDK `query` signature and the function emits `assistant_delta`/`assistant_done` (or `error`) on `ctx`. Also `const SYSTEM_PROMPT: string`.

- [ ] **Step 1: Write the failing test `sidecar/src/agent.test.ts`**

```ts
import { describe, it, expect } from "vitest";
import { runChat } from "./agent.js";

// Fake query() yielding SDK-shaped streaming messages.
async function* fakeQuery() {
  yield { type: "assistant", message: { content: [{ type: "text", text: "Hel" }] } };
  yield { type: "assistant", message: { content: [{ type: "text", text: "lo" }] } };
  yield { type: "result", subtype: "success" };
}

function recordingCtx() {
  const events: string[] = [];
  return {
    events,
    ctx: {
      sendDelta: (_id: string, t: string) => events.push("delta:" + t),
      sendDone: () => events.push("done"),
      sendError: (_id: string | null, m: string) => events.push("error:" + m),
      bridge: {} as any,
    },
  };
}

describe("runChat", () => {
  it("streams text deltas then done", async () => {
    const { events, ctx } = recordingCtx();
    await runChat({ queryFn: fakeQuery as any, mcpServer: {} as any, model: "m" }, "1", "hi", ctx);
    expect(events).toEqual(["delta:Hel", "delta:lo", "done"]);
  });

  it("emits error when query throws", async () => {
    const { events, ctx } = recordingCtx();
    const throwing = () => { throw new Error("boom"); };
    await runChat({ queryFn: throwing as any, mcpServer: {} as any, model: "m" }, "1", "hi", ctx);
    expect(events).toEqual(["error:boom"]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sidecar && npx vitest run --maxWorkers=2 src/agent.test.ts`
Expected: FAIL — cannot resolve `./agent.js`.

- [ ] **Step 3: Create `sidecar/src/agent.ts`**

```ts
import type { SessionCtx } from "./server.js";

export const SYSTEM_PROMPT = [
  "You are Wise Old Claude, an Old School RuneScape advisor shown in a side panel.",
  "You have tools that read the player's LIVE game state: get_player_state,",
  "get_inventory, get_nearby_entities. Call them when a question depends on the",
  "player's current situation rather than guessing or asking.",
  "Keep answers short and skimmable — this is a narrow panel, not an essay.",
].join(" ");

// The SDK prefixes MCP tool names as mcp__<serverName>__<tool>. Our server is
// "gielinor" (see main.ts). Listing them in allowedTools auto-approves the
// read-only tools so the headless sidecar never blocks on a permission prompt.
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

export async function runChat(deps: Deps, id: string, text: string, ctx: SessionCtx): Promise<void> {
  try {
    const stream = deps.queryFn({
      prompt: text,
      options: {
        mcpServers: { gielinor: deps.mcpServer },
        systemPrompt: SYSTEM_PROMPT,
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd sidecar && npx vitest run --maxWorkers=2 src/agent.test.ts`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add sidecar/src/agent.ts sidecar/src/agent.test.ts
git commit -m "feat(sidecar): agent driver with system prompt and stream translation"
```

---

### Task 9: Sidecar entry point (plain chat end to end)

**Files:**
- Create: `sidecar/src/main.ts`

**Interfaces:**
- Consumes: `SidecarServer`, `runChat`, Agent SDK `query` + `createSdkMcpServer`.
- Produces: runnable process. Tools attached in Task 12; for now the MCP server is empty.

- [ ] **Step 1: Create `sidecar/src/main.ts`**

```ts
import { query, createSdkMcpServer } from "@anthropic-ai/claude-agent-sdk";
import { SidecarServer } from "./server.js";
import { runChat } from "./agent.js";

const port = Number(process.env.WOC_PORT ?? "8137");
const token = process.env.WOC_TOKEN ?? "";
const model = process.env.WOC_MODEL ?? "claude-sonnet-4-6";

if (!token) {
  console.error("WOC_TOKEN must be set");
  process.exit(1);
}
if (!process.env.CLAUDE_CODE_OAUTH_TOKEN) {
  console.error("CLAUDE_CODE_OAUTH_TOKEN must be set");
  process.exit(1);
}

const server = new SidecarServer({
  port,
  token,
  onChat: (id, text, ctx) => {
    // Tools added in Task 12; empty MCP server for now.
    const mcpServer = createSdkMcpServer({ name: "gielinor", version: "0.1.0", tools: [] });
    void runChat({ queryFn: query as any, mcpServer, model }, id, text, ctx);
  },
});

await server.start();
console.log(`Wise Old Claude sidecar listening on 127.0.0.1:${server.port}`);
```

- [ ] **Step 2: Manual end-to-end check (documented, not automated)**

Run:
```bash
cd sidecar
export WOC_TOKEN=devtoken
export CLAUDE_CODE_OAUTH_TOKEN=$(claude setup-token)   # or an existing token
npm run dev
```
Then in another shell, connect with a WebSocket client, send `{"type":"hello","token":"devtoken"}`, expect `hello_ok`, send a `chat`, and observe `assistant_delta` frames followed by `assistant_done`.
Expected: a streamed answer with no tool calls yet.

- [ ] **Step 3: Commit**

```bash
git add sidecar/src/main.ts
git commit -m "feat(sidecar): entry point wiring server + agent for plain chat"
```

---

# Phase D — Tools + game state

### Task 10: GameStateProvider — player state on the game thread

**Files:**
- Create: `plugin/src/main/java/com/wiseoldclaude/game/GameThreadExecutor.java`
- Create: `plugin/src/main/java/com/wiseoldclaude/game/GameStateProvider.java`
- Test: `plugin/src/test/java/com/wiseoldclaude/game/GameStateProviderTest.java`

**Interfaces:**
- Consumes: RuneLite `Client`.
- Produces: `interface GameThreadExecutor { void run(Runnable r); }` (prod impl = `clientThread::invoke`); `class GameStateProvider(Client client, GameThreadExecutor gameThread)` with `JsonObject playerState()` returning either the state or `{"error": "not logged in"}`.

- [ ] **Step 1: Write the failing test `plugin/src/test/java/com/wiseoldclaude/game/GameStateProviderTest.java`**

```java
package com.wiseoldclaude.game;

import com.google.gson.JsonObject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GameStateProviderTest
{
    // Executor that runs the runnable inline (simulating the game thread synchronously).
    private final GameThreadExecutor inline = Runnable::run;

    @Test
    void reportsNotLoggedIn()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        GameStateProvider p = new GameStateProvider(client, inline);
        JsonObject out = p.playerState();
        assertEquals("not logged in", out.get("error").getAsString());
    }

    @Test
    void reportsCombatLevelAndHp()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        when(client.getLocalPlayer().getCombatLevel()).thenReturn(70);
        when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(62);
        when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(70);
        GameStateProvider p = new GameStateProvider(client, inline);
        JsonObject out = p.playerState();
        assertFalse(out.has("error"));
        assertEquals(70, out.get("combatLevel").getAsInt());
        assertEquals(62, out.getAsJsonObject("hitpoints").get("current").getAsInt());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd plugin && ./gradlew test --tests '*GameStateProviderTest'`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Create `plugin/src/main/java/com/wiseoldclaude/game/GameThreadExecutor.java`**

```java
package com.wiseoldclaude.game;

/** Runs a task on the RuneLite client game thread. Prod impl: clientThread::invoke. */
@FunctionalInterface
public interface GameThreadExecutor
{
    void run(Runnable r);
}
```

- [ ] **Step 4: Create `plugin/src/main/java/com/wiseoldclaude/game/GameStateProvider.java`**

```java
package com.wiseoldclaude.game;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;

public class GameStateProvider
{
    private final Client client;
    private final GameThreadExecutor gameThread;

    public GameStateProvider(Client client, GameThreadExecutor gameThread)
    {
        this.client = client;
        this.gameThread = gameThread;
    }

    /** Runs a read on the game thread and blocks (bounded by the caller's tool timeout). */
    private JsonObject onGameThread(java.util.function.Supplier<JsonObject> read)
    {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        gameThread.run(() -> {
            try { future.complete(read.get()); }
            catch (RuntimeException e) { future.completeExceptionally(e); }
        });
        try { return future.get(); }
        catch (Exception e)
        {
            JsonObject err = new JsonObject();
            err.addProperty("error", "read failed: " + e.getMessage());
            return err;
        }
    }

    public JsonObject playerState()
    {
        return onGameThread(() -> {
            JsonObject o = new JsonObject();
            if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
            {
                o.addProperty("error", "not logged in");
                return o;
            }
            o.addProperty("combatLevel", client.getLocalPlayer().getCombatLevel());
            o.add("hitpoints", skill(Skill.HITPOINTS));
            o.add("prayer", skill(Skill.PRAYER));
            o.addProperty("runEnergy", client.getEnergy() / 100);
            return o;
        });
    }

    private JsonObject skill(Skill s)
    {
        JsonObject o = new JsonObject();
        o.addProperty("current", client.getBoostedSkillLevel(s));
        o.addProperty("base", client.getRealSkillLevel(s));
        return o;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd plugin && ./gradlew test --tests '*GameStateProviderTest'`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/java/com/wiseoldclaude/game/ plugin/src/test/java/com/wiseoldclaude/game/
git commit -m "feat(plugin): GameStateProvider.playerState on the game thread"
```

---

### Task 11: Route tool requests through a tool router

**Files:**
- Create: `plugin/src/main/java/com/wiseoldclaude/game/ToolRouter.java`
- Create: `plugin/src/test/java/com/wiseoldclaude/game/ToolRouterTest.java`
- Modify: `plugin/src/main/java/com/wiseoldclaude/WiseOldClaudePlugin.java` (`onToolRequest`, `startUp`)

**Interfaces:**
- Consumes: `GameStateProvider`.
- Produces: `class ToolRouter(GameStateProvider provider)` with `JsonObject handle(String tool, JsonObject args)`; unknown tool → `{"error": "unknown tool: <name>"}`.

- [ ] **Step 1: Write the failing test `plugin/src/test/java/com/wiseoldclaude/game/ToolRouterTest.java`**

```java
package com.wiseoldclaude.game;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolRouterTest
{
    @Test
    void routesPlayerState()
    {
        GameStateProvider provider = mock(GameStateProvider.class);
        JsonObject state = new JsonObject();
        state.addProperty("combatLevel", 3);
        when(provider.playerState()).thenReturn(state);

        ToolRouter router = new ToolRouter(provider);
        assertEquals(3, router.handle("get_player_state", new JsonObject()).get("combatLevel").getAsInt());
    }

    @Test
    void unknownToolReturnsError()
    {
        ToolRouter router = new ToolRouter(mock(GameStateProvider.class));
        assertEquals("unknown tool: nope", router.handle("nope", new JsonObject()).get("error").getAsString());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd plugin && ./gradlew test --tests '*ToolRouterTest'`
Expected: FAIL — `ToolRouter` does not exist.

- [ ] **Step 3: Create `plugin/src/main/java/com/wiseoldclaude/game/ToolRouter.java`**

```java
package com.wiseoldclaude.game;

import com.google.gson.JsonObject;

public class ToolRouter
{
    private final GameStateProvider provider;

    public ToolRouter(GameStateProvider provider) { this.provider = provider; }

    public JsonObject handle(String tool, JsonObject args)
    {
        switch (tool)
        {
            case "get_player_state": return provider.playerState();
            // get_inventory and get_nearby_entities added in Tasks 13-14.
            default:
                JsonObject err = new JsonObject();
                err.addProperty("error", "unknown tool: " + tool);
                return err;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd plugin && ./gradlew test --tests '*ToolRouterTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Wire the router into the plugin**

In `WiseOldClaudePlugin.java`, add these imports:

```java
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import com.wiseoldclaude.game.GameStateProvider;
import com.wiseoldclaude.game.ToolRouter;
```

Add these fields (the RuneLite `Client` is named `runeliteClient` so it never collides with the `SidecarClient` field named `client`):

```java
@Inject private Client runeliteClient;
@Inject private ClientThread clientThread;
private ToolRouter toolRouter;
```

In `startUp()`, build the router before starting the connection:

```java
toolRouter = new ToolRouter(new GameStateProvider(runeliteClient, clientThread::invoke));
```

Replace the `onToolRequest` body so it runs the router and returns the result over the sidecar connection:

```java
@Override public void onToolRequest(String requestId, String tool, JsonObject args)
{
    try
    {
        JsonObject data = toolRouter.handle(tool, args);
        client.sendToolResponse(requestId, data);
    }
    catch (RuntimeException e)
    {
        client.sendToolError(requestId, e.getMessage());
    }
}
```

- [ ] **Step 6: Verify it compiles**

Run: `cd plugin && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add plugin/src/main/java/com/wiseoldclaude/game/ToolRouter.java plugin/src/test/java/com/wiseoldclaude/game/ToolRouterTest.java plugin/src/main/java/com/wiseoldclaude/WiseOldClaudePlugin.java
git commit -m "feat(plugin): tool router wired to sidecar tool requests"
```

---

### Task 12: Sidecar tools — get_player_state end to end

**Files:**
- Create: `sidecar/src/tools.ts`
- Test: `sidecar/src/tools.test.ts`
- Modify: `sidecar/src/main.ts` (build MCP server from tools)

**Interfaces:**
- Consumes: `ToolBridge`, Agent SDK `tool` + `createSdkMcpServer`, `zod`.
- Produces: `function buildTools(bridge: ToolBridge)` returning an array usable by `createSdkMcpServer`; each tool proxies to `bridge.request(name, args)` and returns `{ content: [{ type: "text", text: JSON.stringify(data) }] }`.

- [ ] **Step 1: Write the failing test `sidecar/src/tools.test.ts`**

```ts
import { describe, it, expect } from "vitest";
import { runTool } from "./tools.js";

describe("runTool", () => {
  it("proxies to the bridge and wraps the result as text content", async () => {
    const fakeBridge = { request: async (_t: string, _a: any) => ({ combatLevel: 42 }) } as any;
    const out = await runTool(fakeBridge, "get_player_state", {});
    expect(out).toEqual({ content: [{ type: "text", text: JSON.stringify({ combatLevel: 42 }) }] });
  });

  it("wraps a bridge error as an error text result", async () => {
    const fakeBridge = { request: async () => { throw new Error("not logged in"); } } as any;
    const out = await runTool(fakeBridge, "get_player_state", {});
    expect(out.content[0].text).toContain("not logged in");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sidecar && npx vitest run --maxWorkers=2 src/tools.test.ts`
Expected: FAIL — cannot resolve `./tools.js`.

- [ ] **Step 3: Create `sidecar/src/tools.ts`**

```ts
import { z } from "zod";
import { tool } from "@anthropic-ai/claude-agent-sdk";
import type { ToolBridge } from "./toolBridge.js";

type TextResult = { content: { type: "text"; text: string }[] };

export async function runTool(
  bridge: ToolBridge,
  name: string,
  args: Record<string, unknown>,
): Promise<TextResult> {
  try {
    const data = await bridge.request(name, args);
    return { content: [{ type: "text", text: JSON.stringify(data) }] };
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    return { content: [{ type: "text", text: JSON.stringify({ error: message }) }] };
  }
}

export function buildTools(bridge: ToolBridge) {
  const empty = z.object({});
  return [
    tool("get_player_state", "Combat level, skills, HP/prayer/run energy, location.",
      empty, async () => runTool(bridge, "get_player_state", {})),
    tool("get_inventory", "Inventory items, worn equipment, and bank contents if the bank is open.",
      empty, async () => runTool(bridge, "get_inventory", {})),
    tool("get_nearby_entities", "Nearby NPCs, players, ground items, and objects.",
      empty, async () => runTool(bridge, "get_nearby_entities", {})),
  ];
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd sidecar && npx vitest run --maxWorkers=2 src/tools.test.ts`
Expected: PASS (2 tests).

- [ ] **Step 5: Wire tools into `main.ts`**

In `sidecar/src/main.ts`, replace the `onChat` body so the MCP server is built from the session's bridge:

```ts
import { buildTools } from "./tools.js";
// ...
  onChat: (id, text, ctx) => {
    const mcpServer = createSdkMcpServer({
      name: "gielinor",
      version: "0.1.0",
      tools: buildTools(ctx.bridge),
    });
    void runChat({ queryFn: query as any, mcpServer, model }, id, text, ctx);
  },
```

- [ ] **Step 6: Verify build**

Run: `cd sidecar && npx tsc --noEmit`
Expected: no type errors.

- [ ] **Step 7: Commit**

```bash
git add sidecar/src/tools.ts sidecar/src/tools.test.ts sidecar/src/main.ts
git commit -m "feat(sidecar): MCP tools proxying game state via the bridge"
```

---

### Task 13: get_inventory (provider + router)

**Files:**
- Modify: `plugin/src/main/java/com/wiseoldclaude/game/GameStateProvider.java` (add `inventory()`)
- Modify: `plugin/src/main/java/com/wiseoldclaude/game/ToolRouter.java` (route it)
- Modify: `plugin/src/test/java/com/wiseoldclaude/game/GameStateProviderTest.java` (add cases)

**Interfaces:**
- Produces: `JsonObject inventory()` — `{ inventory: [{id,name,quantity}], equipment: [...], bank: [...]|null }`; `bank` is `null` when the bank interface is closed.

- [ ] **Step 1: Add the failing tests to `GameStateProviderTest.java`**

```java
    @Test
    void inventoryReportsItemsAndNullBankWhenClosed()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));

        net.runelite.api.ItemContainer inv = mock(net.runelite.api.ItemContainer.class);
        when(inv.getItems()).thenReturn(new net.runelite.api.Item[]{ new net.runelite.api.Item(995, 100) });
        when(client.getItemContainer(net.runelite.api.InventoryID.INVENTORY)).thenReturn(inv);
        when(client.getItemContainer(net.runelite.api.InventoryID.EQUIPMENT)).thenReturn(null);
        when(client.getItemContainer(net.runelite.api.InventoryID.BANK)).thenReturn(null);

        net.runelite.api.ItemComposition coin = mock(net.runelite.api.ItemComposition.class);
        when(coin.getName()).thenReturn("Coins");
        when(client.getItemDefinition(995)).thenReturn(coin);

        GameStateProvider p = new GameStateProvider(client, inline);
        JsonObject out = p.inventory();
        assertTrue(out.get("bank").isJsonNull());
        assertEquals("Coins", out.getAsJsonArray("inventory").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(100, out.getAsJsonArray("inventory").get(0).getAsJsonObject().get("quantity").getAsInt());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd plugin && ./gradlew test --tests '*GameStateProviderTest'`
Expected: FAIL — `inventory()` does not exist.

- [ ] **Step 3: Add `inventory()` to `GameStateProvider.java`**

```java
import com.google.gson.JsonArray;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;

    public JsonObject inventory()
    {
        return onGameThread(() -> {
            JsonObject o = new JsonObject();
            if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
            {
                o.addProperty("error", "not logged in");
                return o;
            }
            o.add("inventory", items(client.getItemContainer(InventoryID.INVENTORY)));
            o.add("equipment", items(client.getItemContainer(InventoryID.EQUIPMENT)));
            ItemContainer bank = client.getItemContainer(InventoryID.BANK);
            if (bank == null) o.add("bank", com.google.gson.JsonNull.INSTANCE);
            else o.add("bank", items(bank));
            return o;
        });
    }

    private JsonArray items(ItemContainer container)
    {
        JsonArray arr = new JsonArray();
        if (container == null) return arr;
        for (Item item : container.getItems())
        {
            if (item.getId() < 0 || item.getQuantity() <= 0) continue;
            JsonObject j = new JsonObject();
            j.addProperty("id", item.getId());
            j.addProperty("name", client.getItemDefinition(item.getId()).getName());
            j.addProperty("quantity", item.getQuantity());
            arr.add(j);
        }
        return arr;
    }
```

- [ ] **Step 4: Route it in `ToolRouter.java`**

```java
            case "get_inventory": return provider.inventory();
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd plugin && ./gradlew test --tests '*GameStateProviderTest' --tests '*ToolRouterTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/java/com/wiseoldclaude/game/GameStateProvider.java plugin/src/main/java/com/wiseoldclaude/game/ToolRouter.java plugin/src/test/java/com/wiseoldclaude/game/GameStateProviderTest.java
git commit -m "feat(plugin): get_inventory tool (inventory, equipment, bank-if-open)"
```

---

### Task 14: get_nearby_entities (provider + router)

**Files:**
- Modify: `plugin/src/main/java/com/wiseoldclaude/game/GameStateProvider.java` (add `nearbyEntities()`)
- Modify: `plugin/src/main/java/com/wiseoldclaude/game/ToolRouter.java` (route it)
- Modify: `plugin/src/test/java/com/wiseoldclaude/game/GameStateProviderTest.java` (add case)

**Interfaces:**
- Produces: `JsonObject nearbyEntities()` — `{ npcs: [{name, combatLevel}], players: [{name}] }` (v1 covers NPCs and players; ground items/objects deferred).

- [ ] **Step 1: Add the failing test to `GameStateProviderTest.java`**

```java
    @Test
    void nearbyReportsNpcNames()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));

        net.runelite.api.NPC goblin = mock(net.runelite.api.NPC.class);
        when(goblin.getName()).thenReturn("Goblin");
        when(goblin.getCombatLevel()).thenReturn(2);
        when(client.getNpcs()).thenReturn(java.util.List.of(goblin));
        when(client.getPlayers()).thenReturn(java.util.List.of());

        GameStateProvider p = new GameStateProvider(client, inline);
        JsonObject out = p.nearbyEntities();
        assertEquals("Goblin", out.getAsJsonArray("npcs").get(0).getAsJsonObject().get("name").getAsString());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd plugin && ./gradlew test --tests '*GameStateProviderTest'`
Expected: FAIL — `nearbyEntities()` does not exist.

- [ ] **Step 3: Add `nearbyEntities()` to `GameStateProvider.java`**

```java
import net.runelite.api.NPC;
import net.runelite.api.Player;

    public JsonObject nearbyEntities()
    {
        return onGameThread(() -> {
            JsonObject o = new JsonObject();
            if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
            {
                o.addProperty("error", "not logged in");
                return o;
            }
            JsonArray npcs = new JsonArray();
            for (NPC npc : client.getNpcs())
            {
                if (npc == null || npc.getName() == null) continue;
                JsonObject j = new JsonObject();
                j.addProperty("name", npc.getName());
                j.addProperty("combatLevel", npc.getCombatLevel());
                npcs.add(j);
            }
            o.add("npcs", npcs);

            JsonArray players = new JsonArray();
            for (Player pl : client.getPlayers())
            {
                if (pl == null || pl.getName() == null || pl == client.getLocalPlayer()) continue;
                JsonObject j = new JsonObject();
                j.addProperty("name", pl.getName());
                players.add(j);
            }
            o.add("players", players);
            return o;
        });
    }
```

- [ ] **Step 4: Route it in `ToolRouter.java`**

```java
            case "get_nearby_entities": return provider.nearbyEntities();
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd plugin && ./gradlew test`
Expected: PASS (all plugin tests).

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/java/com/wiseoldclaude/game/GameStateProvider.java plugin/src/main/java/com/wiseoldclaude/game/ToolRouter.java plugin/src/test/java/com/wiseoldclaude/game/GameStateProviderTest.java
git commit -m "feat(plugin): get_nearby_entities tool (npcs + players)"
```

---

# Phase E — Resilience

### Task 15: Reconnect with backoff + robust disconnect handling

**Files:**
- Create: `plugin/src/main/java/com/wiseoldclaude/ReconnectingConnection.java`
- Test: `plugin/src/test/java/com/wiseoldclaude/ReconnectingConnectionTest.java`
- Modify: `plugin/src/main/java/com/wiseoldclaude/WiseOldClaudePlugin.java` (use it)

**Interfaces:**
- Consumes: `SidecarClient`, `SidecarListener.onDisconnected`.
- Produces: `class ReconnectingConnection(Runnable connectAttempt, ScheduledExecutorService scheduler, long baseMs, long maxMs)` with `void start()`, `void onConnected()`, `void onDisconnected()`, `long currentDelayMs()`; exponential backoff capped at `maxMs`, reset to `baseMs` on connect.

- [ ] **Step 1: Write the failing test `plugin/src/test/java/com/wiseoldclaude/ReconnectingConnectionTest.java`**

```java
package com.wiseoldclaude;

import org.junit.jupiter.api.Test;
import java.util.concurrent.ScheduledExecutorService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReconnectingConnectionTest
{
    @Test
    void backoffDoublesAndCaps()
    {
        ScheduledExecutorService sched = mock(ScheduledExecutorService.class);
        ReconnectingConnection rc = new ReconnectingConnection(() -> {}, sched, 1000, 8000);
        assertEquals(1000, rc.currentDelayMs());
        rc.onDisconnected(); assertEquals(2000, rc.currentDelayMs());
        rc.onDisconnected(); assertEquals(4000, rc.currentDelayMs());
        rc.onDisconnected(); assertEquals(8000, rc.currentDelayMs());
        rc.onDisconnected(); assertEquals(8000, rc.currentDelayMs()); // capped
    }

    @Test
    void connectResetsBackoff()
    {
        ScheduledExecutorService sched = mock(ScheduledExecutorService.class);
        ReconnectingConnection rc = new ReconnectingConnection(() -> {}, sched, 1000, 8000);
        rc.onDisconnected(); rc.onDisconnected();
        rc.onConnected();
        assertEquals(1000, rc.currentDelayMs());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd plugin && ./gradlew test --tests '*ReconnectingConnectionTest'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Create `plugin/src/main/java/com/wiseoldclaude/ReconnectingConnection.java`**

```java
package com.wiseoldclaude;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ReconnectingConnection
{
    private final Runnable connectAttempt;
    private final ScheduledExecutorService scheduler;
    private final long baseMs;
    private final long maxMs;
    private long delayMs;

    public ReconnectingConnection(Runnable connectAttempt, ScheduledExecutorService scheduler, long baseMs, long maxMs)
    {
        this.connectAttempt = connectAttempt;
        this.scheduler = scheduler;
        this.baseMs = baseMs;
        this.maxMs = maxMs;
        this.delayMs = baseMs;
    }

    public long currentDelayMs() { return delayMs; }

    public void start() { connectAttempt.run(); }

    public void onConnected() { delayMs = baseMs; }

    public void onDisconnected()
    {
        long next = Math.min(delayMs * 2, maxMs);
        scheduler.schedule(connectAttempt, delayMs, TimeUnit.MILLISECONDS);
        delayMs = next;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd plugin && ./gradlew test --tests '*ReconnectingConnectionTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Wire reconnection into the plugin**

In `WiseOldClaudePlugin.java`: create a single-thread `ScheduledExecutorService` in `startUp`, build a `ReconnectingConnection` whose `connectAttempt` calls `client.connect(host, port, token)`, call `reconnect.start()` instead of `client.connect(...)` directly, and delegate:

```java
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

private ScheduledExecutorService scheduler;
private ReconnectingConnection reconnect;

// in startUp(), replace the direct client.connect(...) call
scheduler = Executors.newSingleThreadScheduledExecutor();
reconnect = new ReconnectingConnection(
    () -> client.connect(config.sidecarHost(), config.sidecarPort(), config.token()),
    scheduler, 1000, 8000);
reconnect.start();

// in shutDown()
if (scheduler != null) scheduler.shutdownNow();

// update listener delegation
@Override public void onConnected() { reconnect.onConnected(); panel.onConnected(); }
@Override public void onDisconnected() { reconnect.onDisconnected(); panel.onDisconnected(); }
```

- [ ] **Step 6: Verify build + full test run**

Run: `cd plugin && ./gradlew build` and `cd sidecar && npx vitest run --maxWorkers=2`
Expected: both green.

- [ ] **Step 7: Commit**

```bash
git add plugin/src/main/java/com/wiseoldclaude/ReconnectingConnection.java plugin/src/test/java/com/wiseoldclaude/ReconnectingConnectionTest.java plugin/src/main/java/com/wiseoldclaude/WiseOldClaudePlugin.java
git commit -m "feat(plugin): reconnect with exponential backoff"
```

---

## Final manual verification (in-client)

1. Start the sidecar: `cd sidecar && WOC_TOKEN=devtoken CLAUDE_CODE_OAUTH_TOKEN=$(claude setup-token) npm run dev`.
2. Run RuneLite with the plugin (`./gradlew run` from a RuneLite plugin dev setup, or install the built jar).
3. Set the plugin config token to `devtoken`; confirm the panel shows **Connected**.
4. Log into a character. Ask: "what's in my inventory?" — confirm Claude calls `get_inventory` and answers with real items.
5. Ask a stats question — confirm `get_player_state`. Ask about nearby monsters — confirm `get_nearby_entities`.
6. Kill the sidecar; confirm the panel flips to **Disconnected** and reconnects within ~8s when restarted.
