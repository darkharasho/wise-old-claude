package com.wiseoldclaude;

import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
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

    private WiseOldClaudePanel panel;
    private SidecarClient client;
    private NavigationButton navButton;
    private ToolRouter toolRouter;
    private ScheduledExecutorService scheduler;
    private ReconnectingConnection reconnect;

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

        scheduler = Executors.newSingleThreadScheduledExecutor();
        reconnect = new ReconnectingConnection(
            () -> client.connect(config.sidecarHost(), config.sidecarPort(), config.token()),
            scheduler, 1000, 8000);
        reconnect.start();
    }

    @Override
    protected void shutDown()
    {
        if (scheduler != null) scheduler.shutdownNow();
        if (client != null) client.close();
        if (navButton != null) clientToolbar.removeNavigation(navButton);
    }

    // SidecarListener — chat events forward to the panel.
    @Override public void onDelta(String id, String text) { panel.onDelta(id, text); }
    @Override public void onDone(String id) { panel.onDone(id); }
    @Override public void onError(String id, String message) { panel.onError(id, message); }
    @Override public void onConnected() { reconnect.onConnected(); panel.onConnected(); }
    @Override public void onDisconnected() { reconnect.onDisconnected(); panel.onDisconnected(); }

    // Routes tool requests through ToolRouter. NB: this runs on the WebSocket read thread and
    // blocks up to ~5s on the game-thread read, so tool calls are handled serially in v1.
    @Override public void onToolRequest(String requestId, String tool, JsonObject args)
    {
        try
        {
            JsonObject data = toolRouter.handle(tool, args);
            client.sendToolResponse(requestId, data);
        }
        catch (RuntimeException e)
        {
            client.sendToolError(requestId, e.getMessage());
        }
    }
}
