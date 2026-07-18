package com.wiseoldclaude;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Optionally manages the sidecar as a child process. Probe + launch are injected
 * seams so the spawn-vs-attach decision and env assembly are unit-testable.
 */
@Slf4j
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

    private final String nodePath;
    private final String sidecarDir;
    private final String host;
    private final int port;
    private final String token;
    private final String envFilePath;
    private final PortProbe probe;
    private final ProcessLauncher launcher;
    private Process process;

    public SidecarProcess(String nodePath, String sidecarDir, String host, int port, String token,
                          String envFilePath, PortProbe probe, ProcessLauncher launcher)
    {
        this.nodePath = nodePath;
        this.sidecarDir = sidecarDir;
        this.host = host;
        this.port = port;
        this.token = token;
        this.envFilePath = envFilePath;
        this.probe = probe;
        this.launcher = launcher;
    }

    public void start()
    {
        if (probe.isOpen(host, port))
        {
            log.info("Sidecar already listening on {}:{}, attaching (not spawning)", host, port);
            return;
        }
        Map<String, String> env = buildChildEnv(System.getenv(), token, port, readEnvFile());
        List<String> command = java.util.Arrays.asList(nodePath, "dist/main.js");
        try
        {
            process = launcher.launch(command, new File(sidecarDir), env);
            pipeOutput(process);
            log.info("Spawned sidecar: {} dist/main.js (cwd {})", nodePath, sidecarDir);
        }
        catch (Exception e)
        {
            log.warn("Failed to spawn sidecar ({}); will still try to connect", e.getMessage());
            process = null;
        }
    }

    public void stop()
    {
        if (process == null) return;
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroyForcibly();
        process = null;
    }

    private Map<String, String> readEnvFile()
    {
        if (envFilePath == null || envFilePath.trim().isEmpty()) return java.util.Collections.emptyMap();
        try
        {
            return parseEnvFile(Files.readAllLines(Paths.get(envFilePath)));
        }
        catch (Exception e)
        {
            log.warn("Could not read sidecar env file {}: {}", envFilePath, e.getMessage());
            return java.util.Collections.emptyMap();
        }
    }

    private void pipeOutput(Process p)
    {
        if (p.getInputStream() == null) return;
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = r.readLine()) != null) log.info("[sidecar] {}", line);
            }
            catch (Exception ignored) { }
        }, "woc-sidecar-log");
        t.setDaemon(true);
        t.start();
    }

    /** Real TCP probe: a short connect attempt to host:port. */
    public static PortProbe tcpProbe()
    {
        return (host, port) -> {
            try (Socket s = new Socket())
            {
                s.connect(new InetSocketAddress(host, port), 300);
                return true;
            }
            catch (Exception e)
            {
                return false;
            }
        };
    }

    /** Real launcher: ProcessBuilder with the exact env and merged stderr. */
    public static ProcessLauncher processBuilderLauncher()
    {
        return (command, workingDir, env) -> {
            ProcessBuilder pb = new ProcessBuilder(command).directory(workingDir).redirectErrorStream(true);
            pb.environment().clear();
            pb.environment().putAll(env);
            return pb.start();
        };
    }
}
