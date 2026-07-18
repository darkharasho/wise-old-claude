package com.wiseoldclaude;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProactiveThrottleTest
{
    @Test
    void firesThenSuppressesWithinCooldownThenFiresAfter()
    {
        long[] now = {1000};
        ProactiveThrottle t = new ProactiveThrottle(60_000, () -> now[0]);
        assertTrue(t.tryFire(), "first call fires");
        now[0] = 1000 + 30_000;
        assertFalse(t.tryFire(), "within cooldown suppressed");
        now[0] = 1000 + 60_000;
        assertTrue(t.tryFire(), "cooldown elapsed fires");
        now[0] = 1000 + 61_000;
        assertFalse(t.tryFire(), "suppressed again right after");
    }
}
