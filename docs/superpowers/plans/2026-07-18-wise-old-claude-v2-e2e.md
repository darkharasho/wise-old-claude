# Wise Old Claude v2 — Automated End-to-End Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deterministic, automated end-to-end test of the real sidecar transport + protocol + tool round-trip, driven by a real WebSocket client, faking only the LLM.

**Architecture:** A single new vitest file stands up the real `SidecarServer` on an ephemeral port with **scripted** `onChat`/`onEvent` handlers that drive the real `SessionCtx` (`bridge.request` + `sendDelta`/`sendDone`/`sendError`), and drives it with a real `ws` client that does the handshake, sends chat/event frames, answers `tool_request`s, and asserts the streamed responses. A separate contract-fixture test asserts `parseMessage` accepts the exact frames the Java `ProtocolCodec` emits.

**Tech Stack:** TypeScript (Node ≥ 20, ESM), `ws`, `vitest`. Sidecar-only. **Test-only — no production code changes.**

## Global Constraints

- Branch: `design/v2-e2e` (already created off `main`). Verify `git branch --show-current` == `design/v2-e2e` before each commit; do NOT switch branches.
- vitest runs with `--maxWorkers=2`.
- **No production code changes.** This sub-project adds one test file only (`sidecar/src/e2e.test.ts`). If a test seems to require a production change, STOP and report it — it likely means a wrong assumption about an existing API.
- Real `SidecarServer` API (already built, do NOT modify): `new SidecarServer({ port, token, onChat, onEvent? })`; `await server.start()` (binds; sets `server.port`); `await server.stop()`. `onChat: (id, text, ctx) => void`; `onEvent: (id, kind, detail, ctx) => void`. `SessionCtx = { sendDelta(id, text), sendDone(id), sendError(id, message), bridge }`; `bridge.request(tool, args, timeoutMs?) => Promise<Record<string, unknown>>`. The server resolves `bridge` when the client sends a `tool_response` (with `data` → resolve, `error` → reject). Non-`hello` frames are ignored until a successful `hello`.
- Each test uses `port: 0` (ephemeral) and stops its server in `afterEach` — no shared port/state.
- Git commit signing (1Password) can intermittently fail with "failed to fill whole buffer"; retry 2-3×, else report DONE_WITH_CONCERNS (do not disable signing).

---

### Task 1: E2E round-trip tests (chat→tool→stream, event→tool→stream, tool-error)

**Files:**
- Create: `sidecar/src/e2e.test.ts`

**Interfaces:**
- Consumes: `SidecarServer` (`./server.js`).
- Produces: a `TestClient` helper (local to the test file) and three passing e2e tests.

- [ ] **Step 1: Write the failing test file `sidecar/src/e2e.test.ts`**

```ts
import { describe, it, expect, afterEach } from "vitest";
import WebSocket from "ws";
import { SidecarServer } from "./server.js";

// A real ws client that acts as the plugin: queues incoming parsed frames and
// lets a test await the next one (so a rapid second frame is never dropped).
class TestClient {
  private ws: WebSocket;
  private queue: any[] = [];
  private waiters: ((m: any) => void)[] = [];

  private constructor(ws: WebSocket) {
    this.ws = ws;
    ws.on("message", (d) => {
      const msg = JSON.parse(d.toString());
      const w = this.waiters.shift();
      if (w) w(msg);
      else this.queue.push(msg);
    });
  }

  static connect(port: number): Promise<TestClient> {
    return new Promise((res) => {
      const ws = new WebSocket(`ws://127.0.0.1:${port}`);
      ws.on("open", () => res(new TestClient(ws)));
    });
  }

  send(obj: unknown): void {
    this.ws.send(JSON.stringify(obj));
  }

  next(): Promise<any> {
    const queued = this.queue.shift();
    if (queued) return Promise.resolve(queued);
    return new Promise((res) => this.waiters.push(res));
  }

  close(): void {
    this.ws.close();
  }
}

let server: SidecarServer | undefined;
afterEach(async () => {
  await server?.stop();
  server = undefined;
});

describe("e2e", () => {
  it("chat -> tool_request -> tool_response -> streamed answer (round-trip)", async () => {
    server = new SidecarServer({
      port: 0,
      token: "e2e",
      onChat: (id, _text, ctx) => {
        ctx.bridge
          .request("get_player_state", {})
          .then((data) => {
            ctx.sendDelta(id, `You are combat level ${(data as any).combatLevel}`);
            ctx.sendDone(id);
          })
          .catch((e) => ctx.sendError(id, e.message));
      },
      onEvent: () => {},
    });
    await server.start();

    const client = await TestClient.connect(server.port);
    client.send({ type: "hello", token: "e2e" });
    expect(await client.next()).toEqual({ type: "hello_ok" });

    client.send({ type: "chat", id: "c1", text: "how am I doing?" });

    const toolReq = await client.next();
    expect(toolReq.type).toBe("tool_request");
    expect(toolReq.tool).toBe("get_player_state");
    expect(typeof toolReq.requestId).toBe("string");

    client.send({ type: "tool_response", requestId: toolReq.requestId, data: { combatLevel: 99 } });

    const delta = await client.next();
    expect(delta.type).toBe("assistant_delta");
    expect(delta.id).toBe("c1");
    expect(delta.text).toContain("99");

    expect(await client.next()).toEqual({ type: "assistant_done", id: "c1" });
    client.close();
  });

  it("proactive event -> tool_request -> streamed comment (round-trip)", async () => {
    server = new SidecarServer({
      port: 0,
      token: "e2e",
      onChat: () => {},
      onEvent: (id, kind, detail, ctx) => {
        ctx.bridge
          .request("get_player_state", {})
          .then((data) => {
            ctx.sendDelta(id, `GZ on ${(detail as any).level} ${(detail as any).skill}! (cb ${(data as any).combatLevel})`);
            ctx.sendDone(id);
          })
          .catch((e) => ctx.sendError(id, e.message));
      },
    });
    await server.start();

    const client = await TestClient.connect(server.port);
    client.send({ type: "hello", token: "e2e" });
    expect(await client.next()).toEqual({ type: "hello_ok" });

    client.send({ type: "event", id: "e1", kind: "level_up", detail: { skill: "Attack", level: 70 } });

    const toolReq = await client.next();
    expect(toolReq.type).toBe("tool_request");
    client.send({ type: "tool_response", requestId: toolReq.requestId, data: { combatLevel: 80 } });

    const delta = await client.next();
    expect(delta.type).toBe("assistant_delta");
    expect(delta.id).toBe("e1");
    expect(delta.text).toContain("70");

    expect(await client.next()).toEqual({ type: "assistant_done", id: "e1" });
    client.close();
  });

  it("tool error propagates to the handler (error branch)", async () => {
    server = new SidecarServer({
      port: 0,
      token: "e2e",
      onChat: (id, _text, ctx) => {
        ctx.bridge
          .request("get_player_state", {})
          .then((data) => ctx.sendDone(id))
          .catch((e) => ctx.sendError(id, e.message));
      },
      onEvent: () => {},
    });
    await server.start();

    const client = await TestClient.connect(server.port);
    client.send({ type: "hello", token: "e2e" });
    expect(await client.next()).toEqual({ type: "hello_ok" });

    client.send({ type: "chat", id: "c1", text: "?" });
    const toolReq = await client.next();
    expect(toolReq.type).toBe("tool_request");

    client.send({ type: "tool_response", requestId: toolReq.requestId, error: "not logged in" });

    const err = await client.next();
    expect(err.type).toBe("error");
    expect(err.id).toBe("c1");
    expect(err.message).toContain("not logged in");
    client.close();
  });
});
```

- [ ] **Step 2: Run the tests to verify they pass (this is the deliverable)**

Run: `cd sidecar && npx vitest run --maxWorkers=2 src/e2e.test.ts`
Expected: PASS (3 tests), pristine output. (There is no separate RED phase for a black-box integration test — the failure mode being guarded against is a real wire/protocol break, which would make these fail. If they pass on first run, that confirms the real transport + protocol + tool bridge cohere.)

- [ ] **Step 3: Confirm the whole sidecar suite still passes**

Run: `cd sidecar && npx vitest run --maxWorkers=2`
Expected: all suites pass (the existing 17 + the 3 new e2e tests).

- [ ] **Step 4: Commit**

```bash
git add sidecar/src/e2e.test.ts
git commit -m "test(sidecar): e2e round-trip (chat/event -> tool -> stream, tool-error)"
```

---

### Task 2: Cross-language protocol contract fixture

**Files:**
- Modify: `sidecar/src/e2e.test.ts`

**Interfaces:**
- Consumes: `parseMessage` (`./protocol.js`).

- [ ] **Step 1: Add the contract-fixture test**

Add this import at the top of `sidecar/src/e2e.test.ts` (alongside the existing imports):

```ts
import { parseMessage } from "./protocol.js";
```

Add this `describe` block at the end of the file:

```ts
// The plugin's Java ProtocolCodec emits these exact frames (the literal strings
// pinned in the Java ProtocolCodecTest / SidecarClientTest). This guards against
// the TS and Java protocols silently drifting apart.
describe("cross-language protocol contract", () => {
  it("parses the exact frames the Java ProtocolCodec emits", () => {
    expect(parseMessage('{"type":"hello","token":"t"}')).toEqual({ type: "hello", token: "t" });

    expect(parseMessage('{"type":"chat","id":"1","text":"hi"}')).toEqual({ type: "chat", id: "1", text: "hi" });

    expect(parseMessage('{"type":"tool_response","requestId":"r1","data":{"hp":99}}')).toEqual({
      type: "tool_response",
      requestId: "r1",
      data: { hp: 99 },
    });

    expect(parseMessage('{"type":"tool_response","requestId":"r1","error":"not logged in"}')).toEqual({
      type: "tool_response",
      requestId: "r1",
      error: "not logged in",
    });

    expect(parseMessage('{"type":"event","id":"e1","kind":"level_up","detail":{"skill":"Attack","level":70}}')).toEqual({
      type: "event",
      id: "e1",
      kind: "level_up",
      detail: { skill: "Attack", level: 70 },
    });
  });
});
```

- [ ] **Step 2: Run the tests to verify they pass**

Run: `cd sidecar && npx vitest run --maxWorkers=2 src/e2e.test.ts`
Expected: PASS (4 tests total in the file now).

- [ ] **Step 3: Confirm the whole sidecar suite + typecheck**

Run: `cd sidecar && npx vitest run --maxWorkers=2 && npx tsc --noEmit`
Expected: all suites pass; tsc clean.

- [ ] **Step 4: Commit**

```bash
git add sidecar/src/e2e.test.ts
git commit -m "test(sidecar): cross-language protocol contract fixture"
```

---

## Final verification

Run: `cd sidecar && npx vitest run --maxWorkers=2 && npx tsc --noEmit`
Expected: the full sidecar suite (existing + 4 new e2e/contract tests) green, tsc clean. No production files changed — `git diff --stat main..HEAD` should show only `sidecar/src/e2e.test.ts` (plus the spec/plan docs).
