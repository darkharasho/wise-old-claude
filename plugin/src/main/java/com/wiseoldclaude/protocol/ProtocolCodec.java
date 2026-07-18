package com.wiseoldclaude.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class ProtocolCodec
{
    private final Gson gson = new Gson();

    public String hello(String token)
    {
        JsonObject o = new JsonObject();
        o.addProperty("type", Messages.HELLO);
        o.addProperty("token", token);
        return gson.toJson(o);
    }

    public String chat(String id, String text)
    {
        JsonObject o = new JsonObject();
        o.addProperty("type", Messages.CHAT);
        o.addProperty("id", id);
        o.addProperty("text", text);
        return gson.toJson(o);
    }

    public String toolResponse(String requestId, JsonObject data)
    {
        JsonObject o = new JsonObject();
        o.addProperty("type", Messages.TOOL_RESPONSE);
        o.addProperty("requestId", requestId);
        o.add("data", data);
        return gson.toJson(o);
    }

    public String toolError(String requestId, String error)
    {
        // Tool errors reuse type "tool_response" with an "error" field (frozen protocol); the sidecar branches on the error field, not a distinct type.
        JsonObject o = new JsonObject();
        o.addProperty("type", Messages.TOOL_RESPONSE);
        o.addProperty("requestId", requestId);
        o.addProperty("error", error);
        return gson.toJson(o);
    }

    public Inbound parse(String raw)
    {
        return new Inbound(gson.fromJson(raw, JsonObject.class));
    }

    /** Read-only view over an inbound message. */
    public static final class Inbound
    {
        private final JsonObject o;

        Inbound(JsonObject o) { this.o = o; }

        private String str(String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }

        public String type() { return str("type"); }
        public String id() { return str("id"); }
        public String text() { return str("text"); }
        public String requestId() { return str("requestId"); }
        public String tool() { return str("tool"); }
        public String message() { return str("message"); }
        public JsonObject args() { return o.has("args") ? o.getAsJsonObject("args") : new JsonObject(); }
    }
}
