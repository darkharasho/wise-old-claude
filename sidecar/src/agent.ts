import type { SessionCtx } from "./server.js";
import { goalsSummary } from "./memory.js";
import { sendAlert } from "./alerts.js";
import { speak } from "./tts.js";

export const SYSTEM_PROMPT = [
  "You are Wise Old Claude, an Old School RuneScape advisor shown in a side panel.",
  "You have tools that read the player's LIVE game state: get_player_state,",
  "get_inventory, get_bank, get_nearby_entities, get_quests, get_skills, get_equipment,",
  "get_location, get_grand_exchange, get_slayer, get_diaries, and generic",
  "get_varbit/get_varp readers. Call them when a question depends on the",
  "player's current situation rather than guessing or asking.",
  "You also have search_osrs_wiki — use it to confirm mechanics, drop tables,",
  "requirements, or strategy from the Old School RuneScape Wiki rather than",
  "relying on memory. When it helps, cite the wiki page URL it returns.",
  "You can draw on the player's screen: highlight_npc (outline NPCs by name),",
  "highlight_object (banks, trees, rocks, doors, altars…), highlight_tile (mark a",
  "world tile, optional label), and clear_highlights. Use these to point things out",
  "visually when it helps; clear when no longer needed.",
  "You can also capture_screen to actually SEE the player's screen (open interfaces,",
  "the minimap, chat) when a question needs looking rather than reading state.",
  "You have persistent memory across sessions: set_goal / complete_goal / get_goals",
  "for the player's long-term goals, and log_activity / get_journal for what they've",
  "done. Record goals they mention and log notable achievements so you remember later.",
  "For 'am I ready for X?' style questions (a boss, quest, or raid), combine your",
  "tools — get_skills, get_equipment, get_quests, get_bank — with search_osrs_wiki",
  "for the requirements, then give a clear ready/not-ready verdict with the gaps,",
  "and suggest gear the player already owns (from their bank) where relevant.",
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
  "get_bank",
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
  "highlight_npc",
  "highlight_object",
  "highlight_tile",
  "clear_highlights",
  "capture_screen",
  "set_goal",
  "complete_goal",
  "get_goals",
  "log_activity",
  "get_journal",
  "search_osrs_wiki",
].map((t) => MCP_PREFIX + t);

type Deps = {
  queryFn: (args: { prompt: string; options: Record<string, unknown> }) => AsyncIterable<unknown>;
  mcpServer: unknown;
  model: string;
};

type StreamOpts = {
  resume?: string;
  onSessionId?: (sessionId: string) => void;
  onComplete?: (fullText: string) => void;
};

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
    let answer = "";
    for await (const msg of stream as AsyncIterable<any>) {
      if (typeof msg?.session_id === "string") sessionId = msg.session_id;
      if (msg?.type === "assistant") {
        for (const block of msg.message?.content ?? []) {
          if (block?.type === "thinking" && block.thinking) ctx.sendThinking(id, block.thinking);
          else if (block?.type === "text" && block.text) { answer += block.text; ctx.sendDelta(id, block.text); }
        }
      }
    }
    if (sessionId) opts.onSessionId?.(sessionId);
    ctx.sendDone(id);
    if (answer.trim()) opts.onComplete?.(answer);
  } catch (e) {
    ctx.sendError(id, e instanceof Error ? e.message : String(e));
  }
}

// Chat keeps a conversation: resume the prior session id and record the new one.
export function runChat(deps: Deps, id: string, text: string, ctx: SessionCtx): Promise<void> {
  const goals = goalsSummary();
  const sys = goals ? `${SYSTEM_PROMPT}\n\n${goals}` : SYSTEM_PROMPT;
  return streamAgent(deps, text, sys, id, ctx, {
    resume: ctx.resumeSessionId,
    onSessionId: (sid) => ctx.onSessionId?.(sid),
    onComplete: (t) => speak(t),
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
    case "low_hp":
      return `The player's hitpoints just dropped to ${detail.current}/${detail.max} — they're in danger. Give ONE urgent, in-character warning (eat/pray/flee).`;
    case "low_prayer":
      return `The player's prayer just dropped to ${detail.current}/${detail.max}. Give ONE brief in-character heads-up (drink a potion or recharge soon).`;
    case "low_run":
      return `The player's run energy is low (${detail.runEnergy}%). One short in-character aside; mention stamina/rest only if apt.`;
    case "ge_complete":
      return `The player's Grand Exchange offer just completed: ${detail.action} ${detail.quantity}x ${detail.item} at ${detail.price} gp each. React briefly, in character.`;
    default:
      return `Something happened in-game (${kind}). React briefly, in character.`;
  }
}

export function runProactive(
  deps: Deps, id: string, kind: string, detail: Record<string, unknown>, ctx: SessionCtx,
): Promise<void> {
  return streamAgent(deps, proactivePrompt(kind, detail), PROACTIVE_SYSTEM_PROMPT, id, ctx, {
    onComplete: (t) => { speak(t); void sendAlert(t); },
  });
}
