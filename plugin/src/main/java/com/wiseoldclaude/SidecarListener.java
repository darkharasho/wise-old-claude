package com.wiseoldclaude;

import com.google.gson.JsonObject;

public interface SidecarListener
{
    void onDelta(String id, String text);
    /** Extended-thinking / reasoning text (default no-op so non-UI listeners can ignore it). */
    default void onThinking(String id, String text) {}
    void onDone(String id);
    void onToolRequest(String requestId, String tool, JsonObject args);
    void onError(String id, String message);
    void onConnected();
    void onDisconnected();
}
