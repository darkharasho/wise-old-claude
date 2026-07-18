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

    @ConfigItem(keyName = "proactiveCooldownSeconds", name = "Proactive cooldown (s)", position = 5,
        description = "Minimum seconds between proactive comments")
    default int proactiveCooldownSeconds() { return 60; }

    @ConfigItem(keyName = "dropValueThreshold", name = "Drop value threshold", position = 6,
        description = "Minimum total GE value of a drop to comment on (requires the Loot Tracker plugin)")
    default int dropValueThreshold() { return 100000; }

    @ConfigItem(keyName = "manageSidecar", name = "Manage sidecar", position = 7,
        description = "Launch and stop the Node sidecar automatically with the plugin")
    default boolean manageSidecar() { return false; }

    @ConfigItem(keyName = "nodePath", name = "Node path", position = 8,
        description = "Path to the node executable (or just 'node' if on PATH)")
    default String nodePath() { return "node"; }

    @ConfigItem(keyName = "sidecarDir", name = "Sidecar directory", position = 9,
        description = "Path to the built sidecar folder containing dist/main.js")
    default String sidecarDir() { return ""; }

    @ConfigItem(keyName = "sidecarEnvFile", name = "Sidecar env file", position = 10,
        description = "Optional KEY=VALUE file merged into the sidecar's environment (e.g. CLAUDE_CODE_OAUTH_TOKEN)")
    default String sidecarEnvFile() { return ""; }
}
