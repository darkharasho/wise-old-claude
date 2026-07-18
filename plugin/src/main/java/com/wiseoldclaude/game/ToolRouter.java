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
            // get_nearby_entities added in Task 14.
            default:
                JsonObject err = new JsonObject();
                err.addProperty("error", "unknown tool: " + tool);
                return err;
        }
    }
}
