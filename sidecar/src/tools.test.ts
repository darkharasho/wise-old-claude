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
