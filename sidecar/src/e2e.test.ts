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
    expect(toolReq.tool).toBe("get_player_state");
    expect(typeof toolReq.requestId).toBe("string");
    client.send({ type: "tool_response", requestId: toolReq.requestId, data: { combatLevel: 80 } });

    const delta = await client.next();
    expect(delta.type).toBe("assistant_delta");
    expect(delta.id).toBe("e1");
    expect(delta.text).toContain("70");
    expect(delta.text).toContain("Attack");

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
