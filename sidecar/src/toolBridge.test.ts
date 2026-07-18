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
