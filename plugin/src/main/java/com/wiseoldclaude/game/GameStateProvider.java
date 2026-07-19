package com.wiseoldclaude.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Experience;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

public class GameStateProvider
{
    private final Client client;
    private final GameThreadExecutor gameThread;
    private final ItemManager itemManager;

    public GameStateProvider(Client client, GameThreadExecutor gameThread)
    {
        this(client, gameThread, null);
    }

    public GameStateProvider(Client client, GameThreadExecutor gameThread, ItemManager itemManager)
    {
        this.client = client;
        this.gameThread = gameThread;
        this.itemManager = itemManager;
    }

    /**
     * Runs a read on the game thread and blocks until it completes, with a hard cap so a
     * stalled game thread can never wedge the calling (WebSocket) thread indefinitely.
     */
    private JsonObject onGameThread(java.util.function.Supplier<JsonObject> read)
    {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        gameThread.run(() -> {
            try { future.complete(read.get()); }
            catch (Exception e) { future.completeExceptionally(e); }
        });
        try { return future.get(5, TimeUnit.SECONDS); }
        catch (TimeoutException e)
        {
            JsonObject err = new JsonObject();
            err.addProperty("error", "game thread timed out");
            return err;
        }
        catch (Exception e)
        {
            JsonObject err = new JsonObject();
            err.addProperty("error", "read failed: " + e.getMessage());
            return err;
        }
    }

    public JsonObject playerState()
    {
        return onGameThread(() -> {
            JsonObject o = new JsonObject();
            if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
            {
                o.addProperty("error", "not logged in");
                return o;
            }
            o.addProperty("combatLevel", client.getLocalPlayer().getCombatLevel());
            o.add("hitpoints", skill(Skill.HITPOINTS));
            o.add("prayer", skill(Skill.PRAYER));
            o.addProperty("runEnergy", client.getEnergy() / 100);
            return o;
        });
    }

    public JsonObject inventory()
    {
        return onGameThread(() -> {
            JsonObject o = new JsonObject();
            if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
            {
                o.addProperty("error", "not logged in");
                return o;
            }
            o.add("inventory", items(client.getItemContainer(InventoryID.INVENTORY)));
            o.add("equipment", items(client.getItemContainer(InventoryID.EQUIPMENT)));
            ItemContainer bank = client.getItemContainer(InventoryID.BANK);
            if (bank == null) o.add("bank", com.google.gson.JsonNull.INSTANCE);
            else o.add("bank", items(bank));
            return o;
        });
    }

    public JsonObject nearbyEntities()
    {
        return onGameThread(() -> {
            JsonObject o = new JsonObject();
            if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
            {
                o.addProperty("error", "not logged in");
                return o;
            }
            JsonArray npcs = new JsonArray();
            for (NPC npc : client.getNpcs())
            {
                if (npc == null || npc.getName() == null) continue;
                JsonObject j = new JsonObject();
                j.addProperty("name", npc.getName());
                j.addProperty("combatLevel", npc.getCombatLevel());
                npcs.add(j);
            }
            o.add("npcs", npcs);

            JsonArray players = new JsonArray();
            for (Player pl : client.getPlayers())
            {
                if (pl == null || pl.getName() == null || pl == client.getLocalPlayer()) continue;
                JsonObject j = new JsonObject();
                j.addProperty("name", pl.getName());
                players.add(j);
            }
            o.add("players", players);
            return o;
        });
    }

    private JsonArray items(ItemContainer container)
    {
        JsonArray arr = new JsonArray();
        if (container == null) return arr;
        for (Item item : container.getItems())
        {
            if (item.getId() < 0 || item.getQuantity() <= 0) continue;
            JsonObject j = new JsonObject();
            j.addProperty("id", item.getId());
            j.addProperty("name", client.getItemDefinition(item.getId()).getName());
            j.addProperty("quantity", item.getQuantity());
            arr.add(j);
        }
        return arr;
    }

    private JsonObject skill(Skill s)
    {
        JsonObject o = new JsonObject();
        o.addProperty("current", client.getBoostedSkillLevel(s));
        o.addProperty("base", client.getRealSkillLevel(s));
        return o;
    }

    // Full quest log: every quest bucketed by completion state (reads varbits, so game thread).
    public JsonObject quests()
    {
        return onGameThread(() -> {
            JsonObject o = new JsonObject();
            if (client.getGameState() != GameState.LOGGED_IN)
            {
                o.addProperty("error", "not logged in");
                return o;
            }
            JsonArray finished = new JsonArray();
            JsonArray inProgress = new JsonArray();
            JsonArray notStarted = new JsonArray();
            for (net.runelite.api.Quest q : net.runelite.api.Quest.values())
            {
                net.runelite.api.QuestState state;
                try { state = q.getState(client); }
                catch (RuntimeException e) { continue; }
                switch (state)
                {
                    case FINISHED: finished.add(q.getName()); break;
                    case IN_PROGRESS: inProgress.add(q.getName()); break;
                    default: notStarted.add(q.getName()); break;
                }
            }
            o.addProperty("finishedCount", finished.size());
            o.addProperty("inProgressCount", inProgress.size());
            o.add("inProgress", inProgress);
            o.add("finished", finished);
            o.add("notStarted", notStarted);
            return o;
        });
    }

    // Per-skill level, boosted level, xp, and xp-to-next; plus totals.
    public JsonObject skills()
    {
        return onGameThread(() -> {
            JsonObject o = new JsonObject();
            if (client.getGameState() != GameState.LOGGED_IN)
            {
                o.addProperty("error", "not logged in");
                return o;
            }
            JsonObject skills = new JsonObject();
            for (Skill s : Skill.values())
            {
                if (s == Skill.OVERALL) continue;
                int level, xp;
                try { level = client.getRealSkillLevel(s); xp = client.getSkillExperience(s); }
                catch (RuntimeException e) { continue; }
                JsonObject j = new JsonObject();
                j.addProperty("level", level);
                j.addProperty("boosted", client.getBoostedSkillLevel(s));
                j.addProperty("xp", xp);
                if (level < 99) j.addProperty("xpToNext", Math.max(0, Experience.getXpForLevel(level + 1) - xp));
                skills.add(s.name(), j);
            }
            o.add("skills", skills);
            o.addProperty("totalLevel", client.getTotalLevel());
            o.addProperty("totalXp", client.getOverallExperience());
            return o;
        });
    }

    // Worn equipment, combined offensive/defensive bonuses, weapon, and attack-style index.
    public JsonObject equipment()
    {
        return onGameThread(() -> {
            JsonObject o = new JsonObject();
            if (client.getGameState() != GameState.LOGGED_IN)
            {
                o.addProperty("error", "not logged in");
                return o;
            }
            ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
            JsonArray worn = new JsonArray();
            long astab = 0, aslash = 0, acrush = 0, amagic = 0, arange = 0;
            long dstab = 0, dslash = 0, dcrush = 0, dmagic = 0, drange = 0;
            long str = 0, rstr = 0, prayer = 0;
            double mdmg = 0;
            if (eq != null)
            {
                for (Item item : eq.getItems())
                {
                    if (item.getId() < 0) continue;
                    JsonObject j = new JsonObject();
                    j.addProperty("id", item.getId());
                    j.addProperty("name", client.getItemDefinition(item.getId()).getName());
                    worn.add(j);
                    if (itemManager != null)
                    {
                        ItemStats st = itemManager.getItemStats(item.getId());
                        if (st != null && st.getEquipment() != null)
                        {
                            ItemEquipmentStats e = st.getEquipment();
                            astab += e.getAstab(); aslash += e.getAslash(); acrush += e.getAcrush();
                            amagic += e.getAmagic(); arange += e.getArange();
                            dstab += e.getDstab(); dslash += e.getDslash(); dcrush += e.getDcrush();
                            dmagic += e.getDmagic(); drange += e.getDrange();
                            str += e.getStr(); rstr += e.getRstr(); prayer += e.getPrayer();
                            mdmg += e.getMdmg();
                        }
                    }
                }
                Item weapon = eq.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
                if (weapon != null && weapon.getId() >= 0)
                    o.addProperty("weapon", client.getItemDefinition(weapon.getId()).getName());
            }
            o.add("worn", worn);
            if (itemManager != null)
            {
                JsonObject b = new JsonObject();
                b.addProperty("stabAttack", astab); b.addProperty("slashAttack", aslash); b.addProperty("crushAttack", acrush);
                b.addProperty("magicAttack", amagic); b.addProperty("rangedAttack", arange);
                b.addProperty("stabDefence", dstab); b.addProperty("slashDefence", dslash); b.addProperty("crushDefence", dcrush);
                b.addProperty("magicDefence", dmagic); b.addProperty("rangedDefence", drange);
                b.addProperty("meleeStrength", str); b.addProperty("rangedStrength", rstr);
                b.addProperty("magicDamagePercent", mdmg); b.addProperty("prayerBonus", prayer);
                o.add("bonuses", b);
            }
            o.addProperty("attackStyleIndex", client.getVarpValue(VarPlayer.ATTACK_STYLE));
            return o;
        });
    }

    // Current world, coordinates, region, and wilderness state.
    public JsonObject location()
    {
        return onGameThread(() -> {
            JsonObject o = new JsonObject();
            Player me = client.getLocalPlayer();
            if (client.getGameState() != GameState.LOGGED_IN || me == null)
            {
                o.addProperty("error", "not logged in");
                return o;
            }
            o.addProperty("world", client.getWorld());
            WorldPoint wp = me.getWorldLocation();
            if (wp != null)
            {
                o.addProperty("x", wp.getX());
                o.addProperty("y", wp.getY());
                o.addProperty("plane", wp.getPlane());
                o.addProperty("regionId", wp.getRegionID());
                boolean inWild = client.getVarbitValue(Varbits.IN_WILDERNESS) > 0;
                o.addProperty("inWilderness", inWild);
                if (inWild)
                {
                    int y = wp.getY();
                    if (y > 6400) y -= 6400;
                    o.addProperty("wildernessLevel", (y - 3520) / 8 + 1);
                }
            }
            return o;
        });
    }

    // Active Grand Exchange offers with live market prices.
    public JsonObject grandExchange()
    {
        return onGameThread(() -> {
            JsonObject o = new JsonObject();
            JsonArray arr = new JsonArray();
            GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
            if (offers != null)
            {
                for (int slot = 0; slot < offers.length; slot++)
                {
                    GrandExchangeOffer offer = offers[slot];
                    if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY) continue;
                    JsonObject j = new JsonObject();
                    j.addProperty("slot", slot);
                    j.addProperty("state", offer.getState().name());
                    int id = offer.getItemId();
                    j.addProperty("itemId", id);
                    try { j.addProperty("item", client.getItemDefinition(id).getName()); }
                    catch (RuntimeException ignored) {}
                    j.addProperty("price", offer.getPrice());
                    j.addProperty("totalQuantity", offer.getTotalQuantity());
                    j.addProperty("quantitySold", offer.getQuantitySold());
                    j.addProperty("spent", offer.getSpent());
                    if (itemManager != null) j.addProperty("marketPrice", itemManager.getItemPrice(id));
                    arr.add(j);
                }
            }
            o.add("offers", arr);
            return o;
        });
    }

    // Current slayer task numbers (creature/location are raw enum ids — resolve names via the wiki).
    public JsonObject slayer()
    {
        return onGameThread(() -> {
            JsonObject o = new JsonObject();
            if (client.getGameState() != GameState.LOGGED_IN)
            {
                o.addProperty("error", "not logged in");
                return o;
            }
            int remaining = client.getVarpValue(VarPlayer.SLAYER_TASK_SIZE);
            o.addProperty("hasTask", remaining > 0);
            o.addProperty("amountRemaining", remaining);
            o.addProperty("points", client.getVarbitValue(Varbits.SLAYER_POINTS));
            o.addProperty("streak", client.getVarbitValue(Varbits.SLAYER_TASK_STREAK));
            o.addProperty("creatureId", client.getVarpValue(VarPlayer.SLAYER_TASK_CREATURE));
            o.addProperty("locationId", client.getVarpValue(VarPlayer.SLAYER_TASK_LOCATION));
            return o;
        });
    }

    // Achievement diary + combat achievement tier varbits (enumerated by reflection over Varbits).
    public JsonObject diaries()
    {
        return onGameThread(() -> {
            JsonObject o = new JsonObject();
            if (client.getGameState() != GameState.LOGGED_IN)
            {
                o.addProperty("error", "not logged in");
                return o;
            }
            JsonObject diaries = new JsonObject();
            JsonObject combat = new JsonObject();
            for (Field f : Varbits.class.getFields())
            {
                String name = f.getName();
                boolean isDiary = name.startsWith("DIARY_");
                boolean isCombat = name.startsWith("COMBAT_ACHIEVEMENT_TIER_");
                if (!isDiary && !isCombat) continue;
                try
                {
                    int value = client.getVarbitValue(f.getInt(null));
                    if (isDiary) diaries.addProperty(name, value);
                    else combat.addProperty(name, value);
                }
                catch (ReflectiveOperationException ignored) {}
            }
            o.add("achievementDiaries", diaries);
            o.add("combatAchievementTiers", combat);
            return o;
        });
    }

    // Generic power-user readers: any varbit / varp by id.
    public JsonObject varbit(int id)
    {
        return onGameThread(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("value", client.getVarbitValue(id));
            return o;
        });
    }

    public JsonObject varp(int id)
    {
        return onGameThread(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("value", client.getVarpValue(id));
            return o;
        });
    }
}
