import { WebSocketServer, WebSocket } from "ws";
import { parseMessage, serialize, type SidecarToPlugin } from "./protocol.js";
import { ToolBridge } from "./toolBridge.js";

export type SessionCtx = {
  sendDelta(id: string, text: string): void;
  sendThinking(id: string, text: string): void;
  sendDone(id: string): void;
  sendError(id: string | null, message: string): void;
  bridge: ToolBridge;
  // Chat conversation continuity: the last chat session id to resume, and a sink
  // to record the id after each chat turn. Optional so proactive/test paths can skip it.
  resumeSessionId?: string;
  onSessionId?(sessionId: string): void;
};

export type SidecarServerOpts = {
  port: number;
  token: string;
  onChat: (id: string, text: string, ctx: SessionCtx) => void;
  onEvent?: (id: string, kind: string, detail: Record<string, unknown>, ctx: SessionCtx) => void;
};

export class SidecarServer {
  private wss?: WebSocketServer;
  public port = 0;

  constructor(private opts: SidecarServerOpts) {}

  start(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.wss = new WebSocketServer({ host: "127.0.0.1", port: this.opts.port });
      this.wss.on("error", reject);
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
    let chatSessionId: string | undefined;
    const ctx: SessionCtx = {
      sendDelta: (id, text) => send({ type: "assistant_delta", id, text }),
      sendThinking: (id, text) => send({ type: "assistant_thinking", id, text }),
      sendDone: (id) => send({ type: "assistant_done", id }),
      sendError: (id, message) => send({ type: "error", id, message }),
      bridge,
      get resumeSessionId() { return chatSessionId; },
      onSessionId: (sessionId) => { chatSessionId = sessionId; },
    };

    ws.on("message", (raw) => {
      let msg;
      try {
        msg = parseMessage(raw.toString());
      } catch {
        return; // ignore malformed frames
      }
      if (msg.type === "hello") {
        if (authed) return;
        authed = msg.token === this.opts.token;
        send(authed ? { type: "hello_ok" } : { type: "hello_reject", reason: "bad token" });
        return;
      }
      if (!authed) return;
      if (msg.type === "chat") this.opts.onChat(msg.id, msg.text, ctx);
      else if (msg.type === "tool_response") bridge.resolve(msg.requestId, msg.data, msg.error);
      else if (msg.type === "event") this.opts.onEvent?.(msg.id, msg.kind, msg.detail, ctx);
    });
  }

  stop(): Promise<void> {
    return new Promise((resolve) => {
      if (!this.wss) return resolve();
      // Close all existing connections first
      for (const client of this.wss.clients) {
        client.close();
      }
      this.wss.close(() => resolve());
    });
  }
}
