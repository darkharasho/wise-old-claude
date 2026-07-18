package com.wiseoldclaude;

import com.google.gson.JsonObject;

public interface SidecarListener
{
    void onDelta(String id, String text);
    void onDone(String id);
    void onToolRequest(String requestId, String tool, JsonObject args);
    void onError(String id, String message);
    void onConnected();
    void onDisconnected();
}
