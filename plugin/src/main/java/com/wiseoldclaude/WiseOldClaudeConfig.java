package com.wiseoldclaude;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("wiseoldclaude")
public interface WiseOldClaudeConfig extends Config
{
    @ConfigItem(keyName = "sidecarHost", name = "Sidecar host", position = 1,
        description = "Host the sidecar listens on")
    default String sidecarHost() { return "127.0.0.1"; }

    @ConfigItem(keyName = "sidecarPort", name = "Sidecar port", position = 2,
        description = "Port the sidecar listens on")
    default int sidecarPort() { return 8137; }

    @ConfigItem(keyName = "token", name = "Handshake token", position = 3, secret = true,
        description = "Shared secret matching the sidecar's WOC_TOKEN")
    default String token() { return ""; }
}
