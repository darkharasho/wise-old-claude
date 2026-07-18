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

    @ConfigItem(keyName = "proactiveEnabled", name = "Proactive comments", position = 4,
        description = "Let Claude comment unprompted on notable events")
    default boolean proactiveEnabled() { return true; }

    @ConfigItem(keyName = "dropValueThreshold", name = "Drop value threshold", position = 6,
        description = "Minimum total GE value of a drop to comment on")
    default int dropValueThreshold() { return 100000; }
}
