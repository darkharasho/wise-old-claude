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
    // Must stay strictly greater than the plugin's 5s game-thread timeout so the
    // plugin's structured {error:"game thread timed out"} response wins the race
    // and the bridge never fires before the plugin can reply.
    timeoutMs = 8000,
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
