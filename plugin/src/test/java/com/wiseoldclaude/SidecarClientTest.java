package com.wiseoldclaude;

import com.google.gson.JsonObject;
import com.wiseoldclaude.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SidecarClientTest
{
    private final List<String> sent = new ArrayList<>();

    private SidecarClient client(SidecarListener l)
    {
        // Constructor variant that captures outgoing frames instead of opening a socket.
        return new SidecarClient(new ProtocolCodec(), l, sent::add);
    }

    @Test
    void dispatchesDeltaToListener()
    {
        List<String> deltas = new ArrayList<>();
        SidecarClient c = client(new NoopListener() {
            @Override public void onDelta(String id, String text) { deltas.add(id + ":" + text); }
        });
        c.dispatch("{\"type\":\"assistant_delta\",\"id\":\"1\",\"text\":\"hi\"}");
        assertEquals(List.of("1:hi"), deltas);
    }

    @Test
    void dispatchesToolRequestToListener()
    {
        List<String> reqs = new ArrayList<>();
        SidecarClient c = client(new NoopListener() {
            @Override public void onToolRequest(String requestId, String tool, JsonObject args) {
                reqs.add(requestId + ":" + tool);
            }
        });
        c.dispatch("{\"type\":\"tool_request\",\"requestId\":\"r1\",\"tool\":\"get_player_state\",\"args\":{}}");
        assertEquals(List.of("r1:get_player_state"), reqs);
    }

    @Test
    void sendChatEmitsChatFrame()
    {
        SidecarClient c = client(new NoopListener());
        c.sendChat("1", "hello");
        assertEquals("{\"type\":\"chat\",\"id\":\"1\",\"text\":\"hello\"}", sent.get(0));
    }

    static class NoopListener implements SidecarListener {
        @Override public void onDelta(String id, String text) {}
        @Override public void onDone(String id) {}
        @Override public void onToolRequest(String requestId, String tool, JsonObject args) {}
        @Override public void onError(String id, String message) {}
        @Override public void onConnected() {}
        @Override public void onDisconnected() {}
    }
}
