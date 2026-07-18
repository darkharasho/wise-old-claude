package com.wiseoldclaude;

import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.UUID;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import com.wiseoldclaude.protocol.ProtocolCodec;

@Slf4j
@PluginDescriptor(name = "Wise Old Claude", description = "Chat with Claude using live game state")
public class WiseOldClaudePlugin extends Plugin implements SidecarListener
{
    @Inject private WiseOldClaudeConfig config;
    @Inject private ClientToolbar clientToolbar;

    private WiseOldClaudePanel panel;
    private SidecarClient client;
    private NavigationButton navButton;

    @Provides
    WiseOldClaudeConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(WiseOldClaudeConfig.class);
    }

    @Override
    protected void startUp()
    {
        panel = new WiseOldClaudePanel();
        client = new SidecarClient(new ProtocolCodec(), this);
        panel.setSubmitHandler(text -> client.sendChat(UUID.randomUUID().toString(), text));

        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        navButton = NavigationButton.builder().tooltip("Wise Old Claude").icon(icon).panel(panel).build();
        clientToolbar.addNavigation(navButton);

        client.connect(config.sidecarHost(), config.sidecarPort(), config.token());
    }

    @Override
    protected void shutDown()
    {
        if (client != null) client.close();
        if (navButton != null) clientToolbar.removeNavigation(navButton);
    }

    // SidecarListener — chat events forward to the panel; tool requests handled in Task 11.
    @Override public void onDelta(String id, String text) { panel.onDelta(id, text); }
    @Override public void onDone(String id) { panel.onDone(id); }
    @Override public void onError(String id, String message) { panel.onError(id, message); }
    @Override public void onConnected() { panel.onConnected(); }
    @Override public void onDisconnected() { panel.onDisconnected(); }
    @Override public void onToolRequest(String requestId, String tool, JsonObject args)
    {
        // Filled in Task 11.
        client.sendToolError(requestId, "no tools wired yet");
    }
}
