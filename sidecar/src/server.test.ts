import { describe, it, expect, afterEach } from "vitest";
import WebSocket from "ws";
import { SidecarServer } from "./server.js";

let server: SidecarServer | undefined;
afterEach(async () => { await server?.stop(); server = undefined; });

class MessageWaiter {
  private queue: any[] = [];
  private resolvers: ((msg: any) => void)[] = [];

  constructor(ws: WebSocket) {
    ws.on("message", (d) => {
      const msg = JSON.parse(d.toString());
      if (this.resolvers.length > 0) {
        this.resolvers.shift()!(msg);
      } else {
        this.queue.push(msg);
      }
    });
  }

  next(): Promise<any> {
    return new Promise((res) => {
      if (this.queue.length > 0) {
        res(this.queue.shift());
      } else {
        this.resolvers.push(res);
      }
    });
  }
}

function connect(port: number): Promise<WebSocket> {
  return new Promise((res) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}`);
    ws.on("open", () => res(ws));
  });
}

describe("SidecarServer", () => {
  it("accepts a good token and echoes chat via onChat", async () => {
    server = new SidecarServer({
      port: 0, token: "secret",
      onChat: (id, text, ctx) => { ctx.sendDelta(id, text.toUpperCase()); ctx.sendDone(id); },
    });
    await server.start();
    const ws = await connect(server.port);
    const waiter = new MessageWaiter(ws);

    ws.send(JSON.stringify({ type: "hello", token: "secret" }));
    expect(await waiter.next()).toEqual({ type: "hello_ok" });

    ws.send(JSON.stringify({ type: "chat", id: "1", text: "hi" }));
    expect(await waiter.next()).toEqual({ type: "assistant_delta", id: "1", text: "HI" });
    expect(await waiter.next()).toEqual({ type: "assistant_done", id: "1" });

    ws.close();
  });

  it("rejects a bad token", async () => {
    server = new SidecarServer({ port: 0, token: "secret", onChat: () => {} });
    await server.start();
    const ws = await connect(server.port);
    const waiter = new MessageWaiter(ws);

    ws.send(JSON.stringify({ type: "hello", token: "wrong" }));
    expect(await waiter.next()).toEqual({ type: "hello_reject", reason: "bad token" });

    ws.close();
  });

  it("ignores chat before hello, then works after valid hello", async () => {
    server = new SidecarServer({
      port: 0, token: "secret",
      onChat: (id, text, ctx) => { ctx.sendDelta(id, text.toUpperCase()); ctx.sendDone(id); },
    });
    await server.start();
    const ws = await connect(server.port);

    // Send a chat before any hello — the server must not respond
    ws.send(JSON.stringify({ type: "chat", id: "pre", text: "early" }));

    // Collect any messages that arrive within 200ms
    const received: any[] = [];
    const listener = (d: WebSocket.RawData) => received.push(JSON.parse(d.toString()));
    ws.on("message", listener);
    await new Promise<void>((res) => setTimeout(res, 200));
    ws.off("message", listener);

    expect(received).toHaveLength(0);

    // Now authenticate and verify the connection still works
    const waiter = new MessageWaiter(ws);
    ws.send(JSON.stringify({ type: "hello", token: "secret" }));
    expect(await waiter.next()).toEqual({ type: "hello_ok" });

    ws.send(JSON.stringify({ type: "chat", id: "1", text: "hi" }));
    expect(await waiter.next()).toEqual({ type: "assistant_delta", id: "1", text: "HI" });
    expect(await waiter.next()).toEqual({ type: "assistant_done", id: "1" });

    ws.close();
  });
});
