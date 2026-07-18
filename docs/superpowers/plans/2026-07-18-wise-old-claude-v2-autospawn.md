# Wise Old Claude v2 — Auto-spawn the Sidecar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the RuneLite plugin optionally launch the Node sidecar as a child process on startup (attaching to one already running) and kill it on shutdown, without ever handling the OAuth credential.

**Architecture:** A new plugin-side `SidecarProcess` class probes the configured host:port; if a sidecar is already listening it attaches (no spawn), otherwise it launches `node dist/main.js` in the sidecar directory with the non-secret run vars + an optional env-file merged in, pipes the child's output to the RuneLite log, and kills the process tree on stop. Port-probe and process-launch are injected seams so the decision + env assembly are unit-testable without launching a real process. A `manageSidecar` config toggle (default off) gates it.

**Tech Stack:** Java 11, Gradle, RuneLite `net.runelite:client` (1.12.33), Lombok, JUnit 5 + Mockito. Plugin-only — the sidecar is unchanged.

## Global Constraints

- Branch: `design/v2-autospawn` (already created off `main`). Verify `git branch --show-current` == `design/v2-autospawn` before each commit; do NOT switch branches.
- Package `com.wiseoldclaude`. Java 11.
- **The plugin never handles the OAuth credential.** It passes the child only `WOC_TOKEN` (the handshake secret, from `config.token()`) and `WOC_PORT` (from `config.sidecarPort()`). It does NOT pass `WOC_MODEL` (the plugin has no model config; the sidecar's own default `claude-sonnet-4-6` or an env-file entry provides it). The credential is inherited by the child from RuneLite's environment or supplied via the optional env-file.
- Env-file precedence: entries from `sidecarEnvFile` **override** the inherited env and the WOC_* vars (the file is authoritative).
- Config defaults: `manageSidecar=false`, `nodePath="node"`, `sidecarDir=""`, `sidecarEnvFile=""`.
- Injected seams for testing: `SidecarProcess.PortProbe` (`boolean isOpen(String host, int port)`) and `SidecarProcess.ProcessLauncher` (`Process launch(List<String> command, File dir, Map<String,String> env) throws IOException`). Real implementations (`tcpProbe()`, `processBuilderLauncher()`) are used by the plugin and verified manually, not unit-tested.
- Git commit signing (1Password) can intermittently fail with "failed to fill whole buffer"; retry 2-3×, else report DONE_WITH_CONCERNS (do not disable signing).

---

### Task 1: SidecarProcess — seams + pure helpers

**Files:**
- Create: `plugin/src/main/java/com/wiseoldclaude/SidecarProcess.java`
- Test: `plugin/src/test/java/com/wiseoldclaude/SidecarProcessTest.java`

**Interfaces:**
- Produces: nested `SidecarProcess.PortProbe` and `SidecarProcess.ProcessLauncher` functional interfaces; static `Map<String,String> parseEnvFile(List<String> lines)`; static `Map<String,String> buildChildEnv(Map<String,String> base, String token, int port, Map<String,String> envFileEntries)`.

- [ ] **Step 1: Write the failing test `plugin/src/test/java/com/wiseoldclaude/SidecarProcessTest.java`**

```java
package com.wiseoldclaude;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SidecarProcessTest
{
    @Test
    void parseEnvFileSkipsBlanksAndComments()
    {
        Map<String, String> env = SidecarProcess.parseEnvFile(List.of(
            "# a comment", "", "  ", "FOO=bar", "CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-xyz"));
        assertEquals(2, env.size());
        assertEquals("bar", env.get("FOO"));
        assertEquals("sk-ant-oat01-xyz", env.get("CLAUDE_CODE_OAUTH_TOKEN"));
    }

    @Test
    void parseEnvFileKeepsEqualsInValue()
    {
        Map<String, String> env = SidecarProcess.parseEnvFile(List.of("TOKEN=a=b=c"));
        assertEquals("a=b=c", env.get("TOKEN"));
    }

    @Test
    void buildChildEnvMergesRunVarsOverBaseAndEnvFileWins()
    {
        Map<String, String> base = Map.of("PATH", "/usr/bin", "WOC_TOKEN", "old");
        Map<String, String> fromFile = Map.of("WOC_TOKEN", "fromfile", "EXTRA", "1");
        Map<String, String> env = SidecarProcess.buildChildEnv(base, "cfgtoken", 8137, fromFile);

        assertEquals("/usr/bin", env.get("PATH"));         // inherited preserved
        assertEquals("8137", env.get("WOC_PORT"));          // run var added
        assertEquals("fromfile", env.get("WOC_TOKEN"));     // env-file overrides both base and the run var
        assertEquals("1", env.get("EXTRA"));                // env-file entry added
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd plugin && ./gradlew test --tests '*SidecarProcessTest'`
Expected: FAIL — `SidecarProcess` does not exist.

- [ ] **Step 3: Create `plugin/src/main/java/com/wiseoldclaude/SidecarProcess.java`**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd plugin && ./gradlew test --tests '*SidecarProcessTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add plugin/src/main/java/com/wiseoldclaude/SidecarProcess.java plugin/src/test/java/com/wiseoldclaude/SidecarProcessTest.java
git commit -m "feat(plugin): SidecarProcess seams + env helpers"
```

---

### Task 2: SidecarProcess — start()/stop() (probe-then-spawn) + real seams

**Files:**
- Modify: `plugin/src/main/java/com/wiseoldclaude/SidecarProcess.java`
- Test: `plugin/src/test/java/com/wiseoldclaude/SidecarProcessTest.java`

**Interfaces:**
- Consumes: the nested seams + static helpers from Task 1.
- Produces: constructor `SidecarProcess(String nodePath, String sidecarDir, String host, int port, String token, String envFilePath, PortProbe probe, ProcessLauncher launcher)`; `void start()`; `void stop()`; static `PortProbe tcpProbe()`; static `ProcessLauncher processBuilderLauncher()`.

- [ ] **Step 1: Add failing tests to `SidecarProcessTest.java`**

Add these imports at the top of the test file (alongside the existing ones):

```java
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
```

Add these test methods:

```java
    @Test
    void startAttachesWithoutSpawningWhenPortAlreadyOpen()
    {
        boolean[] launched = {false};
        SidecarProcess.ProcessLauncher launcher = (cmd, dir, env) -> { launched[0] = true; return null; };
        SidecarProcess sp = new SidecarProcess("node", "/side", "127.0.0.1", 8137, "tok", "",
            (host, port) -> true, launcher);
        sp.start();
        assertFalse(launched[0], "must not spawn when a sidecar is already listening");
    }

    @Test
    void startSpawnsWithExpectedCommandAndEnvWhenPortClosed() throws Exception
    {
        AtomicReference<List<String>> cmd = new AtomicReference<>();
        AtomicReference<File> dir = new AtomicReference<>();
        AtomicReference<Map<String, String>> env = new AtomicReference<>();
        Process fake = mock(Process.class);
        when(fake.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        SidecarProcess.ProcessLauncher launcher = (c, d, e) -> { cmd.set(c); dir.set(d); env.set(e); return fake; };

        SidecarProcess sp = new SidecarProcess("node", "/side", "127.0.0.1", 8137, "tok", "",
            (host, port) -> false, launcher);
        sp.start();

        assertEquals(List.of("node", "dist/main.js"), cmd.get());
        assertEquals(new File("/side"), dir.get());
        assertEquals("tok", env.get().get("WOC_TOKEN"));
        assertEquals("8137", env.get().get("WOC_PORT"));
    }

    @Test
    void startSwallowsLauncherFailure()
    {
        SidecarProcess.ProcessLauncher launcher = (c, d, e) -> { throw new java.io.IOException("node not found"); };
        SidecarProcess sp = new SidecarProcess("node", "/side", "127.0.0.1", 8137, "tok", "",
            (host, port) -> false, launcher);
        assertDoesNotThrow(sp::start);
    }

    @Test
    void stopDestroysSpawnedProcess() throws Exception
    {
        Process fake = mock(Process.class);
        when(fake.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(fake.descendants()).thenReturn(java.util.stream.Stream.empty());
        SidecarProcess sp = new SidecarProcess("node", "/side", "127.0.0.1", 8137, "tok", "",
            (host, port) -> false, (c, d, e) -> fake);
        sp.start();
        sp.stop();
        verify(fake).destroyForcibly();
    }

    @Test
    void stopDoesNothingWhenAttached()
    {
        // Port open → attach, no process retained → stop() is a no-op (no exception).
        SidecarProcess sp = new SidecarProcess("node", "/side", "127.0.0.1", 8137, "tok", "",
            (host, port) -> true, (c, d, e) -> { throw new AssertionError("should not launch"); });
        sp.start();
        assertDoesNotThrow(sp::stop);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd plugin && ./gradlew test --tests '*SidecarProcessTest'`
Expected: FAIL — constructor / `start` / `stop` do not exist.

- [ ] **Step 3: Extend `SidecarProcess.java`**

Add these imports:

```java
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
```

Add `@Slf4j` to the class and these instance members + methods (inside the class):

```java
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd plugin && ./gradlew test --tests '*SidecarProcessTest'`
Expected: PASS (8 tests total). If Mockito cannot stub `descendants()` on `Process` in this JDK, the `stopDestroysSpawnedProcess` test still works because `descendants()` is stubbed to `Stream.empty()`; leave it as written.

- [ ] **Step 5: Commit**

```bash
git add plugin/src/main/java/com/wiseoldclaude/SidecarProcess.java plugin/src/test/java/com/wiseoldclaude/SidecarProcessTest.java
git commit -m "feat(plugin): SidecarProcess start/stop (probe-then-spawn) + real seams"
```

---

### Task 3: Config keys + plugin wiring + README

**Files:**
- Modify: `plugin/src/main/java/com/wiseoldclaude/WiseOldClaudeConfig.java`
- Modify: `plugin/src/main/java/com/wiseoldclaude/WiseOldClaudePlugin.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: `SidecarProcess` (constructor + `start`/`stop` + `tcpProbe()`/`processBuilderLauncher()`).

- [ ] **Step 1: Add config keys to `WiseOldClaudeConfig.java`**

Add these items (keep the existing ones; positions continue after the current highest):

```java
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
```

- [ ] **Step 2: Wire into `WiseOldClaudePlugin.java`**

Add a field:

```java
    private SidecarProcess sidecarProcess;
```

In `startUp()`, BEFORE the existing `reconnect.start();` (or before the existing `client.connect(...)`/reconnect construction — it must run before the connection is attempted), add:

```java
        if (config.manageSidecar())
        {
            sidecarProcess = new SidecarProcess(
                config.nodePath(), config.sidecarDir(), config.sidecarHost(), config.sidecarPort(),
                config.token(), config.sidecarEnvFile(),
                SidecarProcess.tcpProbe(), SidecarProcess.processBuilderLauncher());
            sidecarProcess.start();
        }
```

In `shutDown()`, add (alongside the existing cleanup):

```java
        if (sidecarProcess != null) sidecarProcess.stop();
```

- [ ] **Step 3: Verify compile + full build**

Run: `cd plugin && ./gradlew build`
Expected: BUILD SUCCESSFUL (all existing tests + the new SidecarProcess tests pass).

- [ ] **Step 4: Update `README.md`**

Add a short "Auto-spawn the sidecar (optional)" subsection near the run instructions:

```markdown
### Auto-spawn the sidecar (optional)

By default you start the sidecar yourself (see above). To have the plugin launch
it for you, first build the sidecar once (`cd sidecar && npm install && npm run
build`), then in the plugin config:

- Enable **Manage sidecar**.
- Set **Sidecar directory** to the path of the `sidecar/` folder (it must contain
  `dist/main.js`).
- Set **Node path** if `node` is not on RuneLite's PATH.

The plugin spawns `node dist/main.js`, passing the handshake token and port, and
pipes the sidecar's output into RuneLite's log. It **does not** pass your Claude
credential — the spawned sidecar inherits `CLAUDE_CODE_OAUTH_TOKEN` from your
environment. If RuneLite is launched such that it doesn't inherit that variable,
point **Sidecar env file** at a `KEY=VALUE` file containing
`CLAUDE_CODE_OAUTH_TOKEN=...` (entries there override the inherited environment).

If a sidecar is already running on the configured port, the plugin attaches to it
instead of spawning a second one. The plugin kills a sidecar it spawned when the
plugin is disabled.
```

- [ ] **Step 5: Commit**

```bash
git add plugin/src/main/java/com/wiseoldclaude/WiseOldClaudeConfig.java plugin/src/main/java/com/wiseoldclaude/WiseOldClaudePlugin.java README.md
git commit -m "feat(plugin): manageSidecar config + wiring; README auto-spawn section"
```

---

## Final manual verification (in-client)

1. Build the sidecar once (`cd sidecar && npm install && npm run build`).
2. In the plugin config, enable **Manage sidecar**, set **Sidecar directory** to the `sidecar/` path, and (if needed) the **Sidecar env file** with your `CLAUDE_CODE_OAUTH_TOKEN`.
3. Enable the plugin with no sidecar running → confirm the RuneLite log shows "Spawned sidecar", the panel connects, and `[sidecar]` log lines appear.
4. Disable the plugin → confirm the sidecar process (and its `claude` child) exits.
5. Start a sidecar manually, then enable the plugin → confirm the log shows "already listening … attaching" and no second process spawns.
6. Toggle **Manage sidecar** off → confirm behavior returns to manual start.
