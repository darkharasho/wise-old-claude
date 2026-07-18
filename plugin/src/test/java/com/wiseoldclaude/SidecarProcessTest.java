package com.wiseoldclaude;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
}
