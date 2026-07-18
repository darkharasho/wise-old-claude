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
