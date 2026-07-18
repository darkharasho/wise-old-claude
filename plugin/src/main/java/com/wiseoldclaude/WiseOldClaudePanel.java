package com.wiseoldclaude;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.util.function.Consumer;
import javax.swing.*;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

public class WiseOldClaudePanel extends PluginPanel implements SidecarListener
{
    private final JTextArea transcript = new JTextArea();
    private final JTextField input = new JTextField();
    private final JLabel status = new JLabel("Disconnected");
    private Consumer<String> onSubmit = t -> {};
    private String streamingId = null;

    public WiseOldClaudePanel()
    {
        setLayout(new BorderLayout());
        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        status.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);

        add(status, BorderLayout.NORTH);
        add(new JScrollPane(transcript), BorderLayout.CENTER);
        add(input, BorderLayout.SOUTH);

        input.addActionListener(e -> {
            String text = input.getText().trim();
            if (text.isEmpty()) return;
            append("You: " + text + "\n");
            input.setText("");
            onSubmit.accept(text);
        });
    }

    public void setSubmitHandler(Consumer<String> onSubmit) { this.onSubmit = onSubmit; }

    private void append(String s)
    {
        SwingUtilities.invokeLater(() -> transcript.append(s));
    }

    // SidecarListener — all UI mutations bounce onto the EDT.
    @Override public void onConnected()
    {
        SwingUtilities.invokeLater(() -> {
            status.setText("Connected");
            status.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
        });
    }

    @Override public void onDisconnected()
    {
        SwingUtilities.invokeLater(() -> {
            status.setText("Disconnected");
            status.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
        });
    }

    // streamingId is read and written only on the EDT (inside these lambdas),
    // so it never crosses the socket thread → EDT boundary unsynchronized.
    @Override public void onDelta(String id, String text)
    {
        SwingUtilities.invokeLater(() -> {
            if (!id.equals(streamingId)) { transcript.append("\nClaude: "); streamingId = id; }
            transcript.append(text);
        });
    }

    @Override public void onDone(String id)
    {
        SwingUtilities.invokeLater(() -> { streamingId = null; transcript.append("\n\n"); });
    }

    @Override public void onError(String id, String message) { append("\n[error] " + message + "\n"); }

    // Not a UI concern; handled by the plugin's tool router.
    @Override public void onToolRequest(String requestId, String tool, JsonObject args) {}
}
