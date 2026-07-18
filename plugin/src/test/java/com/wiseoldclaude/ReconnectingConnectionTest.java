package com.wiseoldclaude;

import org.junit.jupiter.api.Test;
import java.util.concurrent.ScheduledExecutorService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReconnectingConnectionTest
{
    @Test
    void backoffDoublesAndCaps()
    {
        ScheduledExecutorService sched = mock(ScheduledExecutorService.class);
        ReconnectingConnection rc = new ReconnectingConnection(() -> {}, sched, 1000, 8000);
        assertEquals(1000, rc.currentDelayMs());
        rc.onDisconnected(); assertEquals(2000, rc.currentDelayMs());
        rc.onDisconnected(); assertEquals(4000, rc.currentDelayMs());
        rc.onDisconnected(); assertEquals(8000, rc.currentDelayMs());
        rc.onDisconnected(); assertEquals(8000, rc.currentDelayMs()); // capped
    }

    @Test
    void connectResetsBackoff()
    {
        ScheduledExecutorService sched = mock(ScheduledExecutorService.class);
        ReconnectingConnection rc = new ReconnectingConnection(() -> {}, sched, 1000, 8000);
        rc.onDisconnected(); rc.onDisconnected();
        rc.onConnected();
        assertEquals(1000, rc.currentDelayMs());
    }
}
