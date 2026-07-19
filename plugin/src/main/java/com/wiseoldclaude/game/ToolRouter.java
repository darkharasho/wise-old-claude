package com.wiseoldclaude.game;

import com.google.gson.JsonObject;

public class ToolRouter
{
    private final GameStateProvider provider;

    public ToolRouter(GameStateProvider provider) { this.provider = provider; }

    public JsonObject handle(String tool, JsonObject args)
    {
        switch (tool)
        {
            case "get_player_state": return provider.playerState();
            case "get_inventory": return provider.inventory();
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
}
