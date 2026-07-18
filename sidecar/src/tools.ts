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
