package com.wiseoldclaude;

import com.google.gson.JsonObject;
import com.wiseoldclaude.protocol.Messages;
import com.wiseoldclaude.protocol.ProtocolCodec;
import java.net.URI;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

@Slf4j
public class SidecarClient
{
    private final ProtocolCodec codec;
    private final SidecarListener listener;
    private Consumer<String> sink;
    private WebSocketClient ws;
    private String token = "";

    /** Production constructor: frames go out over a real socket once connected. */
    public SidecarClient(ProtocolCodec codec, SidecarListener listener)
    {
        this(codec, listener, null);
        this.sink = this::rawSend;
    }

    /** Test/seam constructor: frames go to the supplied sink. */
    SidecarClient(ProtocolCodec codec, SidecarListener listener, Consumer<String> sink)
    {
        this.codec = codec;
        this.listener = listener;
        this.sink = sink;
    }

    public void connect(String host, int port, String token)
    {
        this.token = token;
        ws = new WebSocketClient(URI.create("ws://" + host + ":" + port))
        {
            @Override public void onOpen(ServerHandshake h) { rawSend(codec.hello(token)); }
            @Override public void onMessage(String message) { dispatch(message); }
            @Override public void onClose(int code, String reason, boolean remote) { listener.onDisconnected(); }
            @Override public void onError(Exception ex) { log.warn("sidecar ws error", ex); }
        };
        ws.connect();
    }

    public void close()
    {
        if (ws != null) ws.close();
    }

    void dispatch(String raw)
    {
        ProtocolCodec.Inbound in;
        try { in = codec.parse(raw); } catch (RuntimeException e) { return; }
        String type = in.type();
        if (type == null) return;
        switch (type)
        {
            case Messages.HELLO_OK: listener.onConnected(); break;
            case Messages.HELLO_REJECT: listener.onError(null, "sidecar rejected token"); break;
            case Messages.ASSISTANT_DELTA: listener.onDelta(in.id(), in.text()); break;
            case Messages.ASSISTANT_THINKING: listener.onThinking(in.id(), in.text()); break;
            case Messages.ASSISTANT_DONE: listener.onDone(in.id()); break;
            case Messages.TOOL_REQUEST: listener.onToolRequest(in.requestId(), in.tool(), in.args()); break;
            case Messages.ERROR: listener.onError(in.id(), in.message()); break;
            default: break;
        }
    }

    public void sendChat(String id, String text) { sink.accept(codec.chat(id, text)); }
    public void sendEvent(String id, String kind, JsonObject detail) { sink.accept(codec.event(id, kind, detail)); }
    public void sendToolResponse(String requestId, JsonObject data) { sink.accept(codec.toolResponse(requestId, data)); }
    public void sendToolError(String requestId, String error) { sink.accept(codec.toolError(requestId, error)); }

    private void rawSend(String frame)
    {
        if (ws != null && ws.isOpen()) ws.send(frame);
        else log.warn("dropping frame; sidecar socket not open (connect() not called or disconnected)");
    }
}
