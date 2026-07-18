package com.wiseoldclaude;

import com.wiseoldclaude.game.EventPayload;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Runs sidecar I/O on a worker so neither the game thread nor the WebSocket read
 * thread blocks. Sends proactive events, and also carries tool-request handling
 * off the read thread via submit().
 */
public class ProactiveDispatcher
{
    private final Executor executor;
    private final SidecarClient client;
    private final Supplier<String> idGen;

    public ProactiveDispatcher(Executor executor, SidecarClient client)
    {
        this(executor, client, () -> UUID.randomUUID().toString());
    }

    public ProactiveDispatcher(Executor executor, SidecarClient client, Supplier<String> idGen)
    {
        this.executor = executor;
        this.client = client;
        this.idGen = idGen;
    }

    public void dispatch(EventPayload p)
    {
        final String id = idGen.get();
        executor.execute(() -> client.sendEvent(id, p.kind(), p.detail()));
    }

    public void submit(Runnable r)
    {
        executor.execute(r);
    }
}
