package com.wiseoldclaude.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wiseoldclaude.ProactiveThrottle;
import com.wiseoldclaude.WiseOldClaudeConfig;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

/**
 * Detects proactive triggers on the game thread and, when the throttle allows,
 * hands a compact payload to onFire. All @Subscribe handlers run on the client
 * game thread; they only read state and build a payload (no blocking).
 */
public class EventWatcher
{
    private final Client client;
    private final ItemManager itemManager;
    private final WiseOldClaudeConfig config;
    private final ProactiveThrottle throttle;
    private final Consumer<EventPayload> onFire;
    private final Map<Skill, Integer> lastLevels = new EnumMap<>(Skill.class);

    public EventWatcher(Client client, ItemManager itemManager, WiseOldClaudeConfig config,
                        ProactiveThrottle throttle, Consumer<EventPayload> onFire)
    {
        this.client = client;
        this.itemManager = itemManager;
        this.config = config;
        this.throttle = throttle;
        this.onFire = onFire;
    }

    private boolean active()
    {
        return config.proactiveEnabled()
            && client.getGameState() == GameState.LOGGED_IN
            && client.getLocalPlayer() != null;
    }

    private static boolean isMilestone(int level)
    {
        return level == 99 || level % 10 == 0;
    }

    private void fire(EventPayload p)
    {
        if (throttle.tryFire()) onFire.accept(p);
    }

    @Subscribe
    public void onStatChanged(StatChanged e)
    {
        Skill skill = e.getSkill();
        int level = e.getLevel();
        Integer prev = lastLevels.put(skill, level);
        if (!active()) return;
        if (prev != null && level > prev && isMilestone(level))
        {
            JsonObject d = new JsonObject();
            d.addProperty("skill", skill.getName());
            d.addProperty("level", level);
            fire(new EventPayload("level_up", d));
        }
    }

    @Subscribe
    public void onActorDeath(ActorDeath e)
    {
        if (!active()) return;
        if (e.getActor() == client.getLocalPlayer())
        {
            fire(new EventPayload("death", new JsonObject()));
        }
    }

    @Subscribe
    public void onLootReceived(LootReceived e)
    {
        if (!active()) return;
        long total = 0;
        JsonArray items = new JsonArray();
        for (ItemStack s : e.getItems())
        {
            long value = (long) itemManager.getItemPrice(s.getId()) * s.getQuantity();
            total += value;
            JsonObject j = new JsonObject();
            j.addProperty("name", itemManager.getItemComposition(s.getId()).getName());
            j.addProperty("quantity", s.getQuantity());
            j.addProperty("value", value);
            items.add(j);
        }
        if (total >= config.dropValueThreshold())
        {
            JsonObject d = new JsonObject();
            d.add("items", items);
            d.addProperty("totalValue", total);
            fire(new EventPayload("drop", d));
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged e)
    {
        if (e.getGameState() == GameState.LOGIN_SCREEN)
        {
            lastLevels.clear();
        }
    }
}
