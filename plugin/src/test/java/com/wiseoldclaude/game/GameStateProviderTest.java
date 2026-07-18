package com.wiseoldclaude.game;

import com.google.gson.JsonObject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GameStateProviderTest
{
    // Executor that runs the runnable inline (simulating the game thread synchronously).
    private final GameThreadExecutor inline = Runnable::run;

    @Test
    void reportsNotLoggedIn()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        GameStateProvider p = new GameStateProvider(client, inline);
        JsonObject out = p.playerState();
        assertEquals("not logged in", out.get("error").getAsString());
    }

    @Test
    void reportsCombatLevelAndHp()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        when(client.getLocalPlayer().getCombatLevel()).thenReturn(70);
        when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(62);
        when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(70);
        GameStateProvider p = new GameStateProvider(client, inline);
        JsonObject out = p.playerState();
        assertFalse(out.has("error"));
        assertEquals(70, out.get("combatLevel").getAsInt());
        assertEquals(62, out.getAsJsonObject("hitpoints").get("current").getAsInt());
    }
}
