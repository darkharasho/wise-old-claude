package com.wiseoldclaude.game;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;

public class GameStateProvider
{
    private final Client client;
    private final GameThreadExecutor gameThread;

    public GameStateProvider(Client client, GameThreadExecutor gameThread)
    {
        this.client = client;
        this.gameThread = gameThread;
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

    private JsonObject skill(Skill s)
    {
        JsonObject o = new JsonObject();
        o.addProperty("current", client.getBoostedSkillLevel(s));
        o.addProperty("base", client.getRealSkillLevel(s));
        return o;
    }
}
