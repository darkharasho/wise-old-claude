package com.wiseoldclaude;

import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import com.wiseoldclaude.game.EventWatcher;
import com.wiseoldclaude.game.GameStateProvider;
import com.wiseoldclaude.game.HighlightOverlay;
import com.wiseoldclaude.game.ToolRouter;
import com.wiseoldclaude.protocol.ProtocolCodec;

@Slf4j
@PluginDescriptor(name = "Wise Old Claude", description = "Chat with Claude using live game state")
public class WiseOldClaudePlugin extends Plugin implements SidecarListener
{
    @Inject private WiseOldClaudeConfig config;
    @Inject private ClientToolbar clientToolbar;
    @Inject private Client runeliteClient;
    @Inject private ClientThread clientThread;
    @Inject private EventBus eventBus;
    @Inject private ItemManager itemManager;
    @Inject private OverlayManager overlayManager;

    private WiseOldClaudePanel panel;
    private SidecarClient client;
    private NavigationButton navButton;
    private ToolRouter toolRouter;
    private ScheduledExecutorService scheduler;
    private ReconnectingConnection reconnect;
    private ExecutorService worker;
    private ProactiveDispatcher dispatcher;
    private EventWatcher eventWatcher;
    private HighlightOverlay highlightOverlay;
    private final java.util.List<net.runelite.client.eventbus.EventBus.Subscriber> eventSubs = new java.util.ArrayList<>();
    private SidecarProcess sidecarProcess;

    @Provides
    WiseOldClaudeConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(WiseOldClaudeConfig.class);
    }

    @Override
    protected void startUp()
    {
        highlightOverlay = new HighlightOverlay(runeliteClient, System::currentTimeMillis);
        overlayManager.add(highlightOverlay);
        toolRouter = new ToolRouter(
            new GameStateProvider(runeliteClient, clientThread::invoke, itemManager), highlightOverlay);
        panel = new WiseOldClaudePanel();
        client = new SidecarClient(new ProtocolCodec(), this);
        panel.setSubmitHandler(text -> client.sendChat(UUID.randomUUID().toString(), text));

        BufferedImage icon = ImageUtil.loadImageResource(WiseOldClaudePlugin.class, "/com/wiseoldclaude/icon.png");
        navButton = NavigationButton.builder().tooltip("Wise Old Claude").icon(icon).panel(panel).build();
        clientToolbar.addNavigation(navButton);

        // Give the panel a sprite provider; item names are learned lazily from tool
        // responses (see onToolRequest) rather than force-loading the whole item cache.
        panel.setItemImageProvider(id -> itemManager.getImage(id));

        if (config.manageSidecar())
        {
            sidecarProcess = new SidecarProcess(
                config.nodePath(), config.sidecarDir(), config.sidecarHost(), config.sidecarPort(),
                config.token(), config.sidecarEnvFile(),
                SidecarProcess.tcpProbe(), SidecarProcess.processBuilderLauncher());
            sidecarProcess.start();
        }

        scheduler = Executors.newSingleThreadScheduledExecutor();
        reconnect = new ReconnectingConnection(
            () -> client.connect(config.sidecarHost(), config.sidecarPort(), config.token()),
            scheduler, 1000, 8000);
        reconnect.start();

        worker = Executors.newSingleThreadExecutor();
        dispatcher = new ProactiveDispatcher(worker, client);
        // Cooldown is read once here at startUp; a config change takes effect on plugin re-enable
        // (proactiveEnabled itself is checked live per-event via config.proactiveEnabled()).
        ProactiveThrottle throttle = new ProactiveThrottle(
            config.proactiveCooldownSeconds() * 1000L, System::currentTimeMillis);
        eventWatcher = new EventWatcher(runeliteClient, itemManager, config, throttle,
            payload -> dispatcher.dispatch(payload));
        // Register each handler as a Consumer instead of eventBus.register(eventWatcher):
        // the annotation-scanning path builds handler lambdas via ReflectUtil.privateLookupIn +
        // LambdaMetafactory, which throws "Invalid caller" for sideloaded-plugin classloaders
        // (breaks all @Subscribe handlers). The typed overload just stores our own Consumer,
        // whose lambda is synthesized by our classloader, so it works when sideloaded.
        eventSubs.add(eventBus.register(net.runelite.api.events.StatChanged.class, eventWatcher::onStatChanged, 0f));
        eventSubs.add(eventBus.register(net.runelite.api.events.ActorDeath.class, eventWatcher::onActorDeath, 0f));
        eventSubs.add(eventBus.register(net.runelite.client.plugins.loottracker.LootReceived.class, eventWatcher::onLootReceived, 0f));
        eventSubs.add(eventBus.register(net.runelite.api.events.GameStateChanged.class, eventWatcher::onGameStateChanged, 0f));
    }

    // Pull item name->id pairs out of a tool response so the panel can icon them later.
    // Only objects with a name + an item id (and not an NPC/player, which carry combatLevel)
    // are treated as items.
    private static void collectItems(com.google.gson.JsonElement el, Map<String, Integer> out)
    {
        if (el == null) return;
        if (el.isJsonArray())
        {
            for (com.google.gson.JsonElement c : el.getAsJsonArray()) collectItems(c, out);
        }
        else if (el.isJsonObject())
        {
            JsonObject o = el.getAsJsonObject();
            com.google.gson.JsonElement nameEl = o.get("name");
            com.google.gson.JsonElement idEl = o.has("id") ? o.get("id") : o.get("itemId");
            if (!o.has("combatLevel") && nameEl != null && nameEl.isJsonPrimitive()
                && idEl != null && idEl.isJsonPrimitive())
            {
                try
                {
                    String name = nameEl.getAsString();
                    if (name != null && !name.trim().isEmpty())
                        out.put(name.trim().toLowerCase(Locale.ROOT), idEl.getAsInt());
                }
                catch (RuntimeException ignored) {}
            }
            for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) collectItems(e.getValue(), out);
        }
    }

    @Override
    protected void shutDown()
    {
        for (net.runelite.client.eventbus.EventBus.Subscriber s : eventSubs) eventBus.unregister(s);
        eventSubs.clear();
        if (highlightOverlay != null) overlayManager.remove(highlightOverlay);
        if (worker != null) worker.shutdownNow();
        if (scheduler != null) scheduler.shutdownNow();
        if (client != null) client.close();
        if (navButton != null) clientToolbar.removeNavigation(navButton);
        if (sidecarProcess != null) sidecarProcess.stop();
    }

    // SidecarListener — chat events forward to the panel.
    @Override public void onDelta(String id, String text) { panel.onDelta(id, text); }
    @Override public void onThinking(String id, String text) { panel.onThinking(id, text); }
    @Override public void onDone(String id) { panel.onDone(id); }
    @Override public void onError(String id, String message) { panel.onError(id, message); }
    @Override public void onConnected() { reconnect.onConnected(); panel.onConnected(); }
    @Override public void onDisconnected() { reconnect.onDisconnected(); panel.onDisconnected(); }

    @Override public void onToolRequest(String requestId, String tool, JsonObject args)
    {
        dispatcher.submit(() -> {
            try
            {
                JsonObject data = toolRouter.handle(tool, args);
                Map<String, Integer> items = new HashMap<>();
                collectItems(data, items);
                if (!items.isEmpty()) panel.addItems(items);
                client.sendToolResponse(requestId, data);
            }
            catch (RuntimeException e)
            {
                client.sendToolError(requestId, e.getMessage());
            }
        });
    }
}
