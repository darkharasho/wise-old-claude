package com.wiseoldclaude;

import java.util.function.LongSupplier;

/** Global cooldown gate. Owned by the game thread; volatile for visibility. */
public class ProactiveThrottle
{
    private final long cooldownMs;
    private final LongSupplier clock;
    private volatile long lastFire;

    public ProactiveThrottle(long cooldownMs, LongSupplier clock)
    {
        this.cooldownMs = cooldownMs;
        this.clock = clock;
        this.lastFire = clock.getAsLong() - cooldownMs; // allow the first fire
    }

    public boolean tryFire()
    {
        long now = clock.getAsLong();
        if (now - lastFire >= cooldownMs)
        {
            lastFire = now;
            return true;
        }
        return false;
    }
}
