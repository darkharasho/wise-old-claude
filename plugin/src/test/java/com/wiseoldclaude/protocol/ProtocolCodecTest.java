package com.wiseoldclaude.protocol;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolCodecTest
{
    private final ProtocolCodec codec = new ProtocolCodec();

    @Test
    void buildsChat()
    {
        assertEquals("{\"type\":\"chat\",\"id\":\"1\",\"text\":\"hi\"}", codec.chat("1", "hi"));
    }

    @Test
    void buildsToolResponse()
    {
        JsonObject data = new JsonObject();
        data.addProperty("hp", 99);
        assertEquals("{\"type\":\"tool_response\",\"requestId\":\"r1\",\"data\":{\"hp\":99}}",
            codec.toolResponse("r1", data));
    }

    @Test
    void parsesToolRequest()
    {
        ProtocolCodec.Inbound in = codec.parse(
            "{\"type\":\"tool_request\",\"requestId\":\"r1\",\"tool\":\"get_inventory\",\"args\":{}}");
        assertEquals("tool_request", in.type());
        assertEquals("r1", in.requestId());
        assertEquals("get_inventory", in.tool());
    }

    @Test
    void parsesAssistantDelta()
    {
        ProtocolCodec.Inbound in = codec.parse(
            "{\"type\":\"assistant_delta\",\"id\":\"1\",\"text\":\"he\"}");
        assertEquals("assistant_delta", in.type());
        assertEquals("1", in.id());
        assertEquals("he", in.text());
    }

    @Test
    void buildsToolError()
    {
        assertEquals("{\"type\":\"tool_response\",\"requestId\":\"r1\",\"error\":\"not logged in\"}",
            codec.toolError("r1", "not logged in"));
    }

    @Test
    void buildsEvent()
    {
        JsonObject detail = new JsonObject();
        detail.addProperty("skill", "Attack");
        detail.addProperty("level", 70);
        assertEquals("{\"type\":\"event\",\"id\":\"e1\",\"kind\":\"level_up\",\"detail\":{\"skill\":\"Attack\",\"level\":70}}",
            codec.event("e1", "level_up", detail));
    }
}
