import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import type { ToolBridge } from "./toolBridge.js";
import { addGoal, completeGoal, listGoals, addJournal, recentJournal } from "./memory.js";

type TextResult = { content: { type: "text"; text: string }[] };

// The OSRS Wiki API rejects requests without a descriptive User-Agent.
const WIKI_UA = "WiseOldClaude/0.1 (RuneLite plugin; project96@gmail.com)";
const WIKI_API = "https://oldschool.runescape.wiki/api.php";

// Look a topic up on the Old School RuneScape Wiki: find the best-matching page,
// then return its intro extract, canonical URL, and main image URL.
export async function searchWiki(query: string): Promise<Record<string, unknown>> {
  const searchUrl =
    `${WIKI_API}?action=query&list=search&srlimit=1&format=json&srsearch=${encodeURIComponent(query)}`;
  const searchRes = await fetch(searchUrl, { headers: { "User-Agent": WIKI_UA } });
  if (!searchRes.ok) return { error: `wiki search failed: HTTP ${searchRes.status}` };
  const searchJson: any = await searchRes.json();
  const hit = searchJson?.query?.search?.[0];
  if (!hit) return { error: `no OSRS Wiki page found for "${query}"` };

  const title: string = hit.title;
  const pageUrl =
    `${WIKI_API}?action=query&prop=extracts|pageimages&exintro=1&explaintext=1&piprop=original` +
    `&format=json&titles=${encodeURIComponent(title)}`;
  const pageRes = await fetch(pageUrl, { headers: { "User-Agent": WIKI_UA } });
  if (!pageRes.ok) return { error: `wiki fetch failed: HTTP ${pageRes.status}` };
  const pageJson: any = await pageRes.json();
  const page: any = Object.values(pageJson?.query?.pages ?? {})[0] ?? {};

  let extract: string = (page.extract ?? "").trim();
  if (extract.length > 1500) extract = extract.slice(0, 1500) + "…";
  return {
    title,
    url: `https://oldschool.runescape.wiki/w/${encodeURIComponent(title.replace(/ /g, "_"))}`,
    extract,
    imageUrl: page.original?.source ?? null,
  };
}

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

// The Agent SDK's tool() takes a zod raw shape (a plain object of field->schema),
// not a ZodObject. Our v1 tools take no arguments, so the shape is empty ({}).
// Passing {} inline avoids any zod version dependency here entirely.
export function buildTools(bridge: ToolBridge) {
  return [
    tool("get_player_state", "Combat level, skills, HP/prayer/run energy, location.",
      {}, async () => runTool(bridge, "get_player_state", {})),
    tool("get_inventory", "The player's current inventory items and worn equipment.",
      {}, async () => runTool(bridge, "get_inventory", {})),
    tool("get_bank",
      "The player's bank contents (as of the last time they opened the bank this session) with each item's quantity and the total GE value.",
      {}, async () => runTool(bridge, "get_bank", {})),
    tool("get_nearby_entities", "Nearby NPCs, players, ground items, and objects.",
      {}, async () => runTool(bridge, "get_nearby_entities", {})),
    tool("get_quests", "The player's full quest log: which quests are finished, in progress, or not started.",
      {}, async () => runTool(bridge, "get_quests", {})),
    tool("get_skills", "Every skill's level, boosted level, current XP, and XP to next level, plus totals.",
      {}, async () => runTool(bridge, "get_skills", {})),
    tool("get_equipment", "Worn gear, combined attack/defence/strength/prayer bonuses, weapon, and attack style.",
      {}, async () => runTool(bridge, "get_equipment", {})),
    tool("get_location", "Current world, coordinates, region id, and wilderness state.",
      {}, async () => runTool(bridge, "get_location", {})),
    tool("get_grand_exchange", "The player's active Grand Exchange offers with live market prices.",
      {}, async () => runTool(bridge, "get_grand_exchange", {})),
    tool("get_slayer", "Current slayer task: amount remaining, points, streak, and raw creature/location ids.",
      {}, async () => runTool(bridge, "get_slayer", {})),
    tool("get_diaries", "Achievement diary and combat achievement tier completion varbits.",
      {}, async () => runTool(bridge, "get_diaries", {})),
    tool("get_varbit", "Read any RuneScape varbit by numeric id (power-user escape hatch).",
      { id: z.number().int().describe("The varbit id to read") },
      async (args: { id: number }) => runTool(bridge, "get_varbit", { id: args.id })),
    tool("get_varp", "Read any RuneScape player variable (varp) by numeric id (power-user escape hatch).",
      { id: z.number().int().describe("The varp id to read") },
      async (args: { id: number }) => runTool(bridge, "get_varp", { id: args.id })),
    tool("highlight_npc",
      "Draw a highlight outline on nearby NPCs whose name matches, on the player's screen. Auto-clears after ~60s.",
      { name: z.string().describe("NPC name to highlight, e.g. 'Banker' or 'Zulrah'") },
      async (args: { name: string }) => runTool(bridge, "highlight_npc", { name: args.name })),
    tool("highlight_object",
      "Highlight scene objects (banks, trees, rocks, doors, altars, etc.) whose name matches, on the player's screen. Auto-clears after ~60s.",
      { name: z.string().describe("Object name to highlight, e.g. 'Bank booth' or 'Altar'") },
      async (args: { name: string }) => runTool(bridge, "highlight_object", { name: args.name })),
    tool("highlight_tile",
      "Mark a world tile on the player's screen with an outline and optional label. Auto-clears after ~60s.",
      {
        x: z.number().int().describe("World X coordinate"),
        y: z.number().int().describe("World Y coordinate"),
        plane: z.number().int().optional().describe("Plane/floor (default 0)"),
        label: z.string().optional().describe("Optional text label drawn on the tile"),
      },
      async (args: { x: number; y: number; plane?: number; label?: string }) =>
        runTool(bridge, "highlight_tile", {
          x: args.x, y: args.y, plane: args.plane ?? 0, label: args.label ?? "",
        })),
    tool("clear_highlights", "Remove all on-screen highlights you've drawn.",
      {}, async () => runTool(bridge, "clear_highlights", {})),
    tool("capture_screen",
      "Take a screenshot of the player's game screen and look at it. Use when a question needs actually seeing the screen — an open interface, the minimap, chat, or what's happening right now.",
      {},
      async () => {
        const data = (await bridge.request("capture_screen", {})) as {
          image?: string; mimeType?: string; width?: number; height?: number; error?: string;
        };
        if (!data || typeof data.image !== "string") {
          return { content: [{ type: "text" as const, text: JSON.stringify(data ?? { error: "no image" }) }] };
        }
        return {
          content: [
            { type: "image" as const, data: data.image, mimeType: data.mimeType ?? "image/png" },
            { type: "text" as const, text: `Screenshot of the game (${data.width}x${data.height}).` },
          ],
        };
      }),
    tool("set_goal", "Remember a long-term goal the player is working toward (persists across sessions).",
      { goal: z.string().describe("The goal, e.g. 'get a fire cape' or '99 fishing'") },
      async (args: { goal: string }) => ({
        content: [{ type: "text" as const, text: JSON.stringify({ goals: addGoal(args.goal) }) }],
      })),
    tool("complete_goal", "Mark a remembered goal as done.",
      { goal: z.string().describe("The goal text (or part of it) to mark complete") },
      async (args: { goal: string }) => ({
        content: [{ type: "text" as const, text: JSON.stringify({ goals: completeGoal(args.goal) }) }],
      })),
    tool("get_goals", "List the player's remembered goals.",
      {}, async () => ({ content: [{ type: "text" as const, text: JSON.stringify({ goals: listGoals() }) }] })),
    tool("log_activity", "Add a note to the player's activity journal (what they did/achieved).",
      { note: z.string().describe("A short note, e.g. 'killed Vorkath 5 times, got a dragon bones stack'") },
      async (args: { note: string }) => {
        addJournal(args.note);
        return { content: [{ type: "text" as const, text: JSON.stringify({ ok: true }) }] };
      }),
    tool("get_journal", "Read the player's recent activity journal entries.",
      {}, async () => ({ content: [{ type: "text" as const, text: JSON.stringify({ journal: recentJournal() }) }] })),
    tool(
      "search_osrs_wiki",
      "Look up an item, monster, quest, or mechanic on the Old School RuneScape Wiki. " +
        "Returns the page's intro text, canonical URL, and main image URL.",
      { query: z.string().describe("What to look up, e.g. an item or monster name") },
      async (args: { query: string }) => ({
        content: [{ type: "text" as const, text: JSON.stringify(await searchWiki(args.query)) }],
      }),
    ),
  ];
}
