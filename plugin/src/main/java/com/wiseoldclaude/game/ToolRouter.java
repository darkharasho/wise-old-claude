package com.wiseoldclaude.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class ToolRouter
{
    private static final long HIGHLIGHT_TTL_MS = 60_000;

    private final GameStateProvider provider;
    private final HighlightOverlay highlight;

    public ToolRouter(GameStateProvider provider) { this(provider, null); }

    public ToolRouter(GameStateProvider provider, HighlightOverlay highlight)
    {
        this.provider = provider;
        this.highlight = highlight;
    }

    public JsonObject handle(String tool, JsonObject args)
    {
        switch (tool)
        {
            case "get_player_state": return provider.playerState();
            case "get_inventory": return provider.inventory();
            case "get_bank": return provider.bank();
            case "get_nearby_entities": return provider.nearbyEntities();
            case "get_quests": return provider.quests();
            case "get_skills": return provider.skills();
            case "get_equipment": return provider.equipment();
            case "get_location": return provider.location();
            case "get_grand_exchange": return provider.grandExchange();
            case "get_slayer": return provider.slayer();
            case "get_diaries": return provider.diaries();
            case "get_varbit": return provider.varbit(argInt(args, "id"));
            case "get_varp": return provider.varp(argInt(args, "id"));
            case "highlight_npc":
            {
                if (highlight == null) return error("highlighting unavailable");
                String name = argStr(args, "name");
                if (name == null || name.trim().isEmpty()) return error("name required");
                highlight.highlightNpc(name.trim(), HIGHLIGHT_TTL_MS);
                return ok("highlighting NPCs named " + name.trim());
            }
            case "highlight_tile":
            {
                if (highlight == null) return error("highlighting unavailable");
                int plane = (args != null && args.has("plane")) ? argInt(args, "plane") : 0;
                highlight.highlightTile(argInt(args, "x"), argInt(args, "y"), plane, argStr(args, "label"), HIGHLIGHT_TTL_MS);
                return ok("tile marked");
            }
            case "highlight_object":
            {
                if (highlight == null) return error("highlighting unavailable");
                String name = argStr(args, "name");
                if (name == null || name.trim().isEmpty()) return error("name required");
                JsonObject found = provider.findObjectTiles(name.trim());
                if (found.has("error")) return found;
                JsonArray objs = found.getAsJsonArray("objects");
                for (JsonElement el : objs)
                {
                    JsonObject j = el.getAsJsonObject();
                    highlight.highlightTile(j.get("x").getAsInt(), j.get("y").getAsInt(),
                        j.get("plane").getAsInt(), null, HIGHLIGHT_TTL_MS);
                }
                return ok("highlighted " + objs.size() + " '" + name.trim() + "' object tile(s)");
            }
            case "clear_highlights":
            {
                if (highlight != null) highlight.clear();
                return ok("cleared");
            }
            default:
                JsonObject err = new JsonObject();
                err.addProperty("error", "unknown tool: " + tool);
                return err;
        }
    }

    private static int argInt(JsonObject args, String key)
    {
        return (args != null && args.has(key) && !args.get(key).isJsonNull()) ? args.get(key).getAsInt() : -1;
    }

    private static String argStr(JsonObject args, String key)
    {
        return (args != null && args.has(key) && !args.get(key).isJsonNull()) ? args.get(key).getAsString() : null;
    }

    private static JsonObject ok(String message)
    {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("message", message);
        return o;
    }

    private static JsonObject error(String message)
    {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o;
    }
}
