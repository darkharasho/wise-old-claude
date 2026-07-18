package com.wiseoldclaude.game;

import com.google.gson.JsonObject;

/** Immutable proactive-event payload: a kind and its detail object. */
public final class EventPayload
{
    private final String kind;
    private final JsonObject detail;

    public EventPayload(String kind, JsonObject detail)
    {
        this.kind = kind;
        this.detail = detail;
    }

    public String kind() { return kind; }
    public JsonObject detail() { return detail; }
}
