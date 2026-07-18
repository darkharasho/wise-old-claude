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
