package com.wiseoldclaude.game;

import com.wiseoldclaude.ProactiveThrottle;
import com.wiseoldclaude.WiseOldClaudeConfig;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.game.ItemManager;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventWatcherTest
{
    private final ProactiveThrottle alwaysFire = new ProactiveThrottle(0, () -> 0L);

    private EventWatcher watcher(Client client, ItemManager items, List<EventPayload> out)
    {
        WiseOldClaudeConfig config = mock(WiseOldClaudeConfig.class);
        when(config.proactiveEnabled()).thenReturn(true);
        when(config.dropValueThreshold()).thenReturn(100000);
        return new EventWatcher(client, items, config, alwaysFire, out::add);
    }

    private StatChanged stat(Skill s, int level)
    {
        StatChanged e = mock(StatChanged.class);
        when(e.getSkill()).thenReturn(s);
        when(e.getLevel()).thenReturn(level);
        return e;
    }

    @Test
    void firesOnMilestoneLevelButNotBetween()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        List<EventPayload> out = new ArrayList<>();
        EventWatcher w = watcher(client, mock(ItemManager.class), out);

        w.onStatChanged(stat(Skill.ATTACK, 69)); // baseline, no fire
        w.onStatChanged(stat(Skill.ATTACK, 70)); // milestone, fires
        w.onStatChanged(stat(Skill.ATTACK, 71)); // not milestone
        assertEquals(1, out.size());
        assertEquals("level_up", out.get(0).kind());
        assertEquals(70, out.get(0).detail().get("level").getAsInt());
    }

    @Test
    void fires99()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        List<EventPayload> out = new ArrayList<>();
        EventWatcher w = watcher(client, mock(ItemManager.class), out);
        w.onStatChanged(stat(Skill.SLAYER, 98));
        w.onStatChanged(stat(Skill.SLAYER, 99));
        assertEquals(1, out.size());
        assertEquals(99, out.get(0).detail().get("level").getAsInt());
    }

    @Test
    void firesOnLocalPlayerDeathOnly()
    {
        Client client = mock(Client.class);
        net.runelite.api.Player me = mock(net.runelite.api.Player.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(me);
        List<EventPayload> out = new ArrayList<>();
        EventWatcher w = watcher(client, mock(ItemManager.class), out);

        ActorDeath other = mock(ActorDeath.class);
        when(other.getActor()).thenReturn(mock(net.runelite.api.NPC.class));
        w.onActorDeath(other);
        assertEquals(0, out.size());

        ActorDeath mine = mock(ActorDeath.class);
        when(mine.getActor()).thenReturn(me);
        w.onActorDeath(mine);
        assertEquals(1, out.size());
        assertEquals("death", out.get(0).kind());
    }

    @Test
    void firesOnDropAtOrAboveThreshold()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        ItemManager items = mock(ItemManager.class);
        when(items.getItemPrice(4151)).thenReturn(2_000_000); // abyssal whip
        net.runelite.api.ItemComposition whip = mock(net.runelite.api.ItemComposition.class);
        when(whip.getName()).thenReturn("Abyssal whip");
        when(items.getItemComposition(4151)).thenReturn(whip);

        List<EventPayload> out = new ArrayList<>();
        EventWatcher w = watcher(client, items, out);

        net.runelite.client.game.ItemStack stack = mock(net.runelite.client.game.ItemStack.class);
        when(stack.getId()).thenReturn(4151);
        when(stack.getQuantity()).thenReturn(1);
        net.runelite.client.plugins.loottracker.LootReceived loot =
            mock(net.runelite.client.plugins.loottracker.LootReceived.class);
        when(loot.getItems()).thenReturn(java.util.List.of(stack));
        w.onLootReceived(loot);

        assertEquals(1, out.size());
        assertEquals("drop", out.get(0).kind());
        assertEquals(2_000_000, out.get(0).detail().get("totalValue").getAsLong());
    }

    @Test
    void logoutResetsLevelBaseline()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        List<EventPayload> out = new ArrayList<>();
        EventWatcher w = watcher(client, mock(ItemManager.class), out);

        w.onStatChanged(stat(Skill.ATTACK, 70)); // baseline (first sighting), no fire
        GameStateChanged logout = mock(GameStateChanged.class);
        when(logout.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        w.onGameStateChanged(logout); // clears baseline
        w.onStatChanged(stat(Skill.ATTACK, 70)); // re-baseline after relog, still no fire
        assertEquals(0, out.size());
    }
}
