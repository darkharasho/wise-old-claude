package com.wiseoldclaude.protocol;

/** Marker for message type-name constants shared by the codec. */
public final class Messages
{
    public static final String HELLO = "hello";
    public static final String CHAT = "chat";
    public static final String TOOL_RESPONSE = "tool_response";
    public static final String HELLO_OK = "hello_ok";
    public static final String HELLO_REJECT = "hello_reject";
    public static final String ASSISTANT_DELTA = "assistant_delta";
    public static final String ASSISTANT_THINKING = "assistant_thinking";
    public static final String ASSISTANT_DONE = "assistant_done";
    public static final String TOOL_REQUEST = "tool_request";
    public static final String ERROR = "error";
    public static final String EVENT = "event";

    private Messages() {}
}
