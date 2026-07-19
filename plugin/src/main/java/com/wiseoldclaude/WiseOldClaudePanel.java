package com.wiseoldclaude;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

public class WiseOldClaudePanel extends PluginPanel implements SidecarListener
{
    // Markdown -> HTML. GFM tables extension renders the |Item|Qty| tables the model emits.
    private static final List<Extension> MD_EXT = Arrays.asList(TablesExtension.create());
    private static final Parser MD = Parser.builder().extensions(MD_EXT).build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder().extensions(MD_EXT).build();

    // One chat turn. Its markdown accumulates across streamed deltas; the whole
    // transcript is re-rendered to HTML on each change (small side-panel log, cheap).
    private static final class Msg
    {
        final String role;
        final String cssClass;
        final StringBuilder md = new StringBuilder();
        Msg(String role, String cssClass) { this.role = role; this.cssClass = cssClass; }
    }

    private final JEditorPane transcript = new JEditorPane();
    private final JTextArea input = new JTextArea(4, 10);
    private final JLabel status = new JLabel("Disconnected");
    private final List<Msg> messages = new ArrayList<>();
    private Consumer<String> onSubmit = t -> {};
    private String streamingId = null;

    public WiseOldClaudePanel()
    {
        setLayout(new BorderLayout(0, 6));

        status.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
        status.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);

        // Rich transcript: a read-only HTML pane styled to match the RuneLite dark theme.
        transcript.setEditable(false);
        transcript.setContentType("text/html");
        HTMLEditorKit kit = new HTMLEditorKit();
        transcript.setEditorKit(kit);
        StyleSheet ss = kit.getStyleSheet();
        ss.addRule("body { font-family: sans-serif; font-size: 11px; color: #dcdcdc; margin: 4px; }");
        ss.addRule("p { margin: 3px 0; }");
        ss.addRule(".role-you { color: #6b9bd6; font-weight: bold; }");
        ss.addRule(".role-claude { color: #e0a032; font-weight: bold; }");
        ss.addRule(".role-error { color: #d64b4b; font-weight: bold; }");
        ss.addRule("table { border: 1px solid #4a4a4a; }");
        ss.addRule("th { border: 1px solid #4a4a4a; padding: 2px 6px; background: #2c2c2c; }");
        ss.addRule("td { border: 1px solid #4a4a4a; padding: 2px 6px; }");
        ss.addRule("code { background: #333333; color: #e6c07b; }");
        ss.addRule("ul, ol { margin: 3px 0; }");
        transcript.setBackground(ColorScheme.DARK_GRAY_COLOR);
        rebuild();

        JScrollPane transcriptScroll = new JScrollPane(transcript);
        transcriptScroll.setBorder(BorderFactory.createEmptyBorder());

        add(status, BorderLayout.NORTH);
        add(transcriptScroll, BorderLayout.CENTER);
        add(buildInput(), BorderLayout.SOUTH);
    }

    // Always-visible, multi-line composer pinned to the bottom.
    private JPanel buildInput()
    {
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        input.setForeground(ColorScheme.TEXT_COLOR);
        input.setCaretColor(ColorScheme.TEXT_COLOR);
        input.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // Enter sends; Shift+Enter inserts a newline.
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "woc-send");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break");
        input.getActionMap().put("woc-send", new AbstractAction()
        {
            @Override public void actionPerformed(ActionEvent e) { submit(); }
        });

        JScrollPane inputScroll = new JScrollPane(input);
        inputScroll.setPreferredSize(new Dimension(0, 84));

        JButton send = new JButton("Send");
        send.setFocusable(false);
        send.addActionListener(e -> submit());

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        buttons.add(send, BorderLayout.EAST);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(inputScroll, BorderLayout.CENTER);
        wrap.add(buttons, BorderLayout.SOUTH);
        return wrap;
    }

    public void setSubmitHandler(Consumer<String> onSubmit) { this.onSubmit = onSubmit; }

    // On the EDT (invoked from the input action).
    private void submit()
    {
        String text = input.getText().trim();
        if (text.isEmpty()) return;
        Msg m = new Msg("You", "role-you");
        m.md.append(text);
        messages.add(m);
        input.setText("");
        rebuild();
        onSubmit.accept(text);
    }

    // Re-render the whole transcript to HTML. EDT-only.
    private void rebuild()
    {
        StringBuilder html = new StringBuilder("<html><body>");
        for (Msg m : messages)
        {
            html.append("<div><span class='").append(m.cssClass).append("'>")
                .append(escape(m.role)).append("</span>");
            html.append(HTML.render(MD.parse(m.md.toString())));
            html.append("</div><br>");
        }
        html.append("</body></html>");
        transcript.setText(html.toString());
        transcript.setCaretPosition(transcript.getDocument().getLength());
    }

    private static String escape(String s)
    {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
    // so it never crosses the socket thread -> EDT boundary unsynchronized.
    @Override public void onDelta(String id, String text)
    {
        SwingUtilities.invokeLater(() -> {
            if (!id.equals(streamingId))
            {
                messages.add(new Msg("Wise Old Claude", "role-claude"));
                streamingId = id;
            }
            messages.get(messages.size() - 1).md.append(text);
            rebuild();
        });
    }

    @Override public void onDone(String id)
    {
        SwingUtilities.invokeLater(() -> streamingId = null);
    }

    @Override public void onError(String id, String message)
    {
        SwingUtilities.invokeLater(() -> {
            Msg m = new Msg("Error", "role-error");
            m.md.append(message);
            messages.add(m);
            streamingId = null;
            rebuild();
        });
    }

    // Not a UI concern; handled by the plugin's tool router.
    @Override public void onToolRequest(String requestId, String tool, JsonObject args) {}
}
