package com.wiseoldclaude.game;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolRouterTest
{
    @Test
    void routesPlayerState()
    {
        GameStateProvider provider = mock(GameStateProvider.class);
        JsonObject state = new JsonObject();
        state.addProperty("combatLevel", 3);
        when(provider.playerState()).thenReturn(state);

        ToolRouter router = new ToolRouter(provider);
        assertEquals(3, router.handle("get_player_state", new JsonObject()).get("combatLevel").getAsInt());
    }

    @Test
    void unknownToolReturnsError()
    {
        ToolRouter router = new ToolRouter(mock(GameStateProvider.class));
        assertEquals("unknown tool: nope", router.handle("nope", new JsonObject()).get("error").getAsString());
    }
}
