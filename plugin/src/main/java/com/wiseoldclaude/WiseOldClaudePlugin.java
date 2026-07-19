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
import com.wiseoldclaude.game.EventWatcher;
import com.wiseoldclaude.game.GameStateProvider;
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

    private WiseOldClaudePanel panel;
    private SidecarClient client;
    private NavigationButton navButton;
    private ToolRouter toolRouter;
    private ScheduledExecutorService scheduler;
    private ReconnectingConnection reconnect;
    private ExecutorService worker;
    private ProactiveDispatcher dispatcher;
    private EventWatcher eventWatcher;
    private SidecarProcess sidecarProcess;

    @Provides
    WiseOldClaudeConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(WiseOldClaudeConfig.class);
    }

    @Override
    protected void startUp()
    {
        toolRouter = new ToolRouter(new GameStateProvider(runeliteClient, clientThread::invoke));
        panel = new WiseOldClaudePanel();
        client = new SidecarClient(new ProtocolCodec(), this);
        panel.setSubmitHandler(text -> client.sendChat(UUID.randomUUID().toString(), text));

        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        navButton = NavigationButton.builder().tooltip("Wise Old Claude").icon(icon).panel(panel).build();
        clientToolbar.addNavigation(navButton);

        // Build the item name -> id catalog on the game thread (getItemDefinition needs it),
        // then hand it to the panel so it can render real in-game sprites inline in chat.
        clientThread.invoke(this::buildItemCatalog);

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
        eventBus.register(eventWatcher);
    }

    // Runs on the game thread. Maps lowercased item names to their canonical (lowest) id,
    // skipping empty/"null" names; putIfAbsent keeps the base item over noted/placeholder
    // variants (which have higher ids). One-time cost at startup.
    private void buildItemCatalog()
    {
        try
        {
            int count = runeliteClient.getItemCount();
            Map<String, Integer> index = new HashMap<>(count * 2);
            for (int id = 0; id < count; id++)
            {
                net.runelite.api.ItemComposition comp;
                try { comp = runeliteClient.getItemDefinition(id); }
                catch (RuntimeException e) { continue; }
                if (comp == null) continue;
                String name = comp.getName();
                if (name == null) continue;
                name = name.trim();
                if (name.isEmpty() || name.equalsIgnoreCase("null")) continue;
                index.putIfAbsent(name.toLowerCase(Locale.ROOT), id);
            }
            panel.setItemCatalog(index, id -> itemManager.getImage(id));
        }
        catch (RuntimeException e)
        {
            log.debug("item catalog build failed", e);
        }
    }

    @Override
    protected void shutDown()
    {
        if (eventWatcher != null) eventBus.unregister(eventWatcher);
        if (worker != null) worker.shutdownNow();
        if (scheduler != null) scheduler.shutdownNow();
        if (client != null) client.close();
        if (navButton != null) clientToolbar.removeNavigation(navButton);
        if (sidecarProcess != null) sidecarProcess.stop();
    }

    // SidecarListener — chat events forward to the panel.
    @Override public void onDelta(String id, String text) { panel.onDelta(id, text); }
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
                client.sendToolResponse(requestId, data);
            }
            catch (RuntimeException e)
            {
                client.sendToolError(requestId, e.getMessage());
            }
        });
    }
}
