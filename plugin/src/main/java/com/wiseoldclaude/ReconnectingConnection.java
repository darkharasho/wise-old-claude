package com.wiseoldclaude;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ReconnectingConnection
{
    private final Runnable connectAttempt;
    private final ScheduledExecutorService scheduler;
    private final long baseMs;
    private final long maxMs;
    private long delayMs;

    public ReconnectingConnection(Runnable connectAttempt, ScheduledExecutorService scheduler, long baseMs, long maxMs)
    {
        this.connectAttempt = connectAttempt;
        this.scheduler = scheduler;
        this.baseMs = baseMs;
        this.maxMs = maxMs;
        this.delayMs = baseMs;
    }

    public long currentDelayMs() { return delayMs; }

    public void start() { connectAttempt.run(); }

    public void onConnected() { delayMs = baseMs; }

    public void onDisconnected()
    {
        long next = Math.min(delayMs * 2, maxMs);
        scheduler.schedule(connectAttempt, delayMs, TimeUnit.MILLISECONDS);
        delayMs = next;
    }
}
