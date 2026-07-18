package com.wiseoldclaude;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(name = "Wise Old Claude", description = "Chat with Claude using live game state")
public class WiseOldClaudePlugin extends Plugin
{
    @Inject private WiseOldClaudeConfig config;

    @Provides
    WiseOldClaudeConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(WiseOldClaudeConfig.class);
    }

    @Override
    protected void startUp()
    {
        log.info("Wise Old Claude starting (sidecar {}:{})", config.sidecarHost(), config.sidecarPort());
    }

    @Override
    protected void shutDown()
    {
        log.info("Wise Old Claude stopping");
    }
}
