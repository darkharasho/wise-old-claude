package com.wiseoldclaude;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optionally manages the sidecar as a child process. Probe + launch are injected
 * seams so the spawn-vs-attach decision and env assembly are unit-testable.
 */
public class SidecarProcess
{
    /** Returns true if something is already listening at host:port. */
    @FunctionalInterface
    public interface PortProbe
    {
        boolean isOpen(String host, int port);
    }

    /** Launches a child process. */
    @FunctionalInterface
    public interface ProcessLauncher
    {
        Process launch(List<String> command, File workingDir, Map<String, String> env) throws IOException;
    }

    /** Parse KEY=VALUE lines, skipping blanks and #-comments; values may contain '='. */
    public static Map<String, String> parseEnvFile(List<String> lines)
    {
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : lines)
        {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            out.put(line.substring(0, eq).trim(), line.substring(eq + 1));
        }
        return out;
    }

    /** Inherited base + WOC_TOKEN/WOC_PORT, with env-file entries overriding everything. */
    public static Map<String, String> buildChildEnv(Map<String, String> base, String token, int port,
                                                     Map<String, String> envFileEntries)
    {
        Map<String, String> env = new HashMap<>(base);
        env.put("WOC_TOKEN", token);
        env.put("WOC_PORT", String.valueOf(port));
        env.putAll(envFileEntries);
        return env;
    }
}
