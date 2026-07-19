import type { SessionCtx } from "./server.js";

export const SYSTEM_PROMPT = [
  "You are Wise Old Claude, an Old School RuneScape advisor shown in a side panel.",
  "You have tools that read the player's LIVE game state: get_player_state,",
  "get_inventory, get_nearby_entities, get_quests, get_skills, get_equipment,",
  "get_location, get_grand_exchange, get_slayer, get_diaries, and generic",
  "get_varbit/get_varp readers. Call them when a question depends on the",
  "player's current situation rather than guessing or asking.",
  "You also have search_osrs_wiki — use it to confirm mechanics, drop tables,",
  "requirements, or strategy from the Old School RuneScape Wiki rather than",
  "relying on memory. When it helps, cite the wiki page URL it returns.",
  "Keep answers short and skimmable — this is a narrow panel, not an essay.",
  "Do NOT use emoji or emoticons. The panel renders real in-game item icons",
  "automatically next to item names, so never add decorative symbols yourself.",
].join(" ");

export const PROACTIVE_SYSTEM_PROMPT = [
  "You are Wise Old Claude, an Old School RuneScape companion. Something just",
  "happened to the player. React with ONE short, in-character remark (a sentence",
  "or two). You may call your tools to make it specific to their situation, but",
  "keep it brief — this is an unprompted aside, not a lecture.",
].join(" ");

// The SDK prefixes MCP tool names as mcp__<serverName>__<tool>. Our server is
// "gielinor" (see main.ts). Listing them in allowedTools auto-approves the
// read-only tools so the headless sidecar never blocks on a permission prompt.
const MCP_PREFIX = "mcp__gielinor__";
export const ALLOWED_TOOLS = [
  "get_player_state",
  "get_inventory",
  "get_nearby_entities",
  "get_quests",
  "get_skills",
  "get_equipment",
  "get_location",
  "get_grand_exchange",
  "get_slayer",
  "get_diaries",
  "get_varbit",
  "get_varp",
  "search_osrs_wiki",
].map((t) => MCP_PREFIX + t);

type Deps = {
  queryFn: (args: { prompt: string; options: Record<string, unknown> }) => AsyncIterable<unknown>;
  mcpServer: unknown;
  model: string;
};

type StreamOpts = { resume?: string; onSessionId?: (sessionId: string) => void };

async function streamAgent(
  deps: Deps, prompt: string, systemPrompt: string, id: string, ctx: SessionCtx, opts: StreamOpts = {},
): Promise<void> {
  try {
    const stream = deps.queryFn({
      prompt,
      options: {
        mcpServers: { gielinor: deps.mcpServer },
        systemPrompt,
        model: deps.model,
        includePartialMessages: false,
        thinking: { type: "adaptive" },
        tools: [],
        allowedTools: ALLOWED_TOOLS,
        ...(opts.resume ? { resume: opts.resume } : {}),
      },
    });
    let sessionId: string | undefined;
    for await (const msg of stream as AsyncIterable<any>) {
      if (typeof msg?.session_id === "string") sessionId = msg.session_id;
      if (msg?.type === "assistant") {
        for (const block of msg.message?.content ?? []) {
          if (block?.type === "thinking" && block.thinking) ctx.sendThinking(id, block.thinking);
          else if (block?.type === "text" && block.text) ctx.sendDelta(id, block.text);
        }
      }
    }
    if (sessionId) opts.onSessionId?.(sessionId);
    ctx.sendDone(id);
  } catch (e) {
    ctx.sendError(id, e instanceof Error ? e.message : String(e));
  }
}

// Chat keeps a conversation: resume the prior session id and record the new one.
export function runChat(deps: Deps, id: string, text: string, ctx: SessionCtx): Promise<void> {
  return streamAgent(deps, text, SYSTEM_PROMPT, id, ctx, {
    resume: ctx.resumeSessionId,
    onSessionId: (sid) => ctx.onSessionId?.(sid),
  });
}

export function proactivePrompt(kind: string, detail: Record<string, unknown>): string {
  switch (kind) {
    case "level_up":
      return `The player just reached level ${detail.level} ${detail.skill}. React briefly, in character.`;
    case "death":
      return `The player just died. React briefly, in character — wry or encouraging.`;
    case "drop":
      return `The player just received a valuable drop worth ${detail.totalValue} gp: ${JSON.stringify(detail.items)}. React briefly, in character; you may check their inventory.`;
    default:
      return `Something happened in-game (${kind}). React briefly, in character.`;
  }
}

export function runProactive(
  deps: Deps, id: string, kind: string, detail: Record<string, unknown>, ctx: SessionCtx,
): Promise<void> {
  return streamAgent(deps, proactivePrompt(kind, detail), PROACTIVE_SYSTEM_PROMPT, id, ctx);
}
