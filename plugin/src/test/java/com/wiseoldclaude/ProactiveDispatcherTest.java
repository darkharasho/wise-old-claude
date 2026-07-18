package com.wiseoldclaude;

import com.wiseoldclaude.game.EventPayload;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProactiveDispatcherTest
{
    private final java.util.concurrent.Executor inline = Runnable::run;

    @Test
    void dispatchSendsEventWithGeneratedId()
    {
        SidecarClient client = mock(SidecarClient.class);
        ProactiveDispatcher d = new ProactiveDispatcher(inline, client, () -> "fixed-id");
        JsonObject detail = new JsonObject();
        detail.addProperty("level", 70);
        d.dispatch(new EventPayload("level_up", detail));
        verify(client).sendEvent("fixed-id", "level_up", detail);
    }

    @Test
    void submitRunsOnExecutor()
    {
        SidecarClient client = mock(SidecarClient.class);
        ProactiveDispatcher d = new ProactiveDispatcher(inline, client, () -> "x");
        boolean[] ran = {false};
        d.submit(() -> ran[0] = true);
        assertTrue(ran[0]);
    }
}
