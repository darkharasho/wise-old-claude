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
        assertTrue(out.has("prayer"));
        assertTrue(out.has("runEnergy"));
    }

    @Test
    void nearbyReportsNpcNames()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));

        net.runelite.api.NPC goblin = mock(net.runelite.api.NPC.class);
        when(goblin.getName()).thenReturn("Goblin");
        when(goblin.getCombatLevel()).thenReturn(2);
        when(client.getNpcs()).thenReturn(java.util.List.of(goblin));
        when(client.getPlayers()).thenReturn(java.util.List.of());

        GameStateProvider p = new GameStateProvider(client, inline);
        JsonObject out = p.nearbyEntities();
        assertEquals("Goblin", out.getAsJsonArray("npcs").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void inventoryReportsItemsAndNullBankWhenClosed()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));

        net.runelite.api.ItemContainer inv = mock(net.runelite.api.ItemContainer.class);
        when(inv.getItems()).thenReturn(new net.runelite.api.Item[]{ new net.runelite.api.Item(995, 100) });
        when(client.getItemContainer(net.runelite.api.InventoryID.INVENTORY)).thenReturn(inv);
        when(client.getItemContainer(net.runelite.api.InventoryID.EQUIPMENT)).thenReturn(null);
        when(client.getItemContainer(net.runelite.api.InventoryID.BANK)).thenReturn(null);

        net.runelite.api.ItemComposition coin = mock(net.runelite.api.ItemComposition.class);
        when(coin.getName()).thenReturn("Coins");
        when(client.getItemDefinition(995)).thenReturn(coin);

        GameStateProvider p = new GameStateProvider(client, inline);
        JsonObject out = p.inventory();
        assertTrue(out.get("bank").isJsonNull());
        assertEquals("Coins", out.getAsJsonArray("inventory").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(100, out.getAsJsonArray("inventory").get(0).getAsJsonObject().get("quantity").getAsInt());
    }
}
