package com.wiseoldclaude;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import javax.swing.event.HyperlinkEvent;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

public class WiseOldClaudePanel extends PluginPanel implements SidecarListener
{
    // Markdown -> HTML. GFM tables extension renders the |Item|Qty| tables the model emits.
    private static final List<Extension> MD_EXT =
        Arrays.asList(TablesExtension.create(), AutolinkExtension.create());
    private static final Parser MD = Parser.builder().extensions(MD_EXT).build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder().extensions(MD_EXT).build();

    // Word tokens for item-name matching (letters/digits/apostrophes).
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}']+");

    // One chat turn. Its markdown accumulates across streamed deltas; the whole
    // transcript is re-rendered to HTML on each change (small side-panel log, cheap).
    private static final class Msg
    {
        final String role;
        final String cssClass;
        final StringBuilder md = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        boolean reasoningExpanded = true;
        String id;
        Msg(String role, String cssClass) { this.role = role; this.cssClass = cssClass; }
    }

    private static final String LOGO_URL = "http://127.0.0.1/woc-logo";

    private final JEditorPane transcript = new JEditorPane();
    private final HTMLEditorKit kit = new HTMLEditorKit();
    private final JTextArea input = new JTextArea(4, 10);
    private final JLabel status = new JLabel("Disconnected");
    private final JLabel thinkingLabel = new JLabel();
    private final List<Msg> messages = new ArrayList<>();
    private Consumer<String> onSubmit = t -> {};
    private String streamingId = null;

    // Animated "thinking" indicator shown between sending a message and the first token.
    private final Image logo = ImageUtil.loadImageResource(WiseOldClaudePanel.class, "/com/wiseoldclaude/logo.png");
    private final Timer thinkingTimer = new Timer(350, null);
    private int thinkingDots = 0;
    private boolean logoRegistered = false;

    // Item icons: a name -> itemId catalog (built by the plugin off the game thread),
    // a sprite supplier, and a shared image cache the HTMLDocument reads from so the
    // <img> tags resolve to real in-game sprites with no network fetch.
    private volatile Map<String, Integer> itemIndex = Collections.emptyMap();
    private IntFunction<Image> itemImage = id -> null;
    private final java.util.Hashtable<URL, Image> imageCache = new java.util.Hashtable<>();
    private final Set<Integer> registered = new HashSet<>();
    private volatile int maxNameWords = 1;

    public WiseOldClaudePanel()
    {
        // wrap=false: take over layout so the transcript fills and the composer
        // pins to the bottom (the default PluginPanel wraps content in a top-
        // aligned scroll pane, which lets the input float instead of pinning).
        super(false);
        setLayout(new BorderLayout(0, 6));
        // Left/right (and light top/bottom) padding so nothing sits flush to the panel edges.
        setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        status.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
        status.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);

        // Rich transcript: a read-only HTML pane styled to match the RuneLite dark theme.
        transcript.setEditable(false);
        transcript.setContentType("text/html");
        transcript.setEditorKit(kit);
        StyleSheet ss = kit.getStyleSheet();
        ss.addRule("body { font-family: sans-serif; font-size: 11px; color: #dcdcdc; margin: 2px 4px; }");
        ss.addRule("p { margin: 6px 2px; }");
        ss.addRule(".role-you { color: #6b9bd6; font-weight: bold; }");
        ss.addRule(".role-claude { color: #e0a032; font-weight: bold; }");
        ss.addRule(".role-error { color: #d64b4b; font-weight: bold; }");
        ss.addRule(".msg { margin: 4px 2px 10px 2px; }");
        ss.addRule("table { border: 1px solid #4a4a4a; }");
        ss.addRule("th { border: 1px solid #4a4a4a; padding: 3px 8px; background: #2c2c2c; }");
        ss.addRule("td { border: 1px solid #4a4a4a; padding: 3px 8px; }");
        ss.addRule("code { background: #333333; color: #e6c07b; }");
        ss.addRule("a { color: #6baadd; }");
        // Wide left indent so two-digit ordered-list markers (e.g. "10.") aren't clipped.
        ss.addRule("ul, ol { margin: 6px 4px 6px 26px; }");
        ss.addRule("li { margin: 3px 0; }");
        transcript.setBackground(ColorScheme.DARK_GRAY_COLOR);
        // Open real links in the browser; custom woc-toggle: links flip reasoning visibility.
        transcript.addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
            String href = e.getDescription();
            if (href == null) return;
            if (href.startsWith("woc-toggle:")) { toggleReasoning(href.substring("woc-toggle:".length())); return; }
            LinkBrowser.browse(href);
        });
        rebuild();

        JScrollPane transcriptScroll = new JScrollPane(transcript);
        transcriptScroll.setBorder(BorderFactory.createEmptyBorder());
        // Wrap long content instead of showing a horizontal scrollbar.
        transcriptScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        thinkingLabel.setForeground(new Color(0xE0A032));
        thinkingLabel.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
        thinkingLabel.setVisible(false);
        thinkingTimer.addActionListener(e -> {
            thinkingDots = (thinkingDots + 1) % 4;
            StringBuilder dots = new StringBuilder();
            for (int i = 0; i < thinkingDots; i++) dots.append('.');
            thinkingLabel.setText("Wise Old Claude is thinking" + dots);
        });

        JPanel south = new JPanel(new BorderLayout());
        south.add(thinkingLabel, BorderLayout.NORTH);
        south.add(buildInput(), BorderLayout.CENTER);

        add(status, BorderLayout.NORTH);
        add(transcriptScroll, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    private void startThinking()
    {
        thinkingDots = 0;
        thinkingLabel.setText("Wise Old Claude is thinking");
        thinkingLabel.setVisible(true);
        thinkingTimer.start();
        revalidate();
    }

    private void stopThinking()
    {
        thinkingTimer.stop();
        if (thinkingLabel.isVisible())
        {
            thinkingLabel.setVisible(false);
            revalidate();
        }
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

    public void setItemImageProvider(IntFunction<Image> imageForId)
    {
        this.itemImage = imageForId;
    }

    // Learn item name->id pairs lazily from tool responses (items the player actually
    // interacts with) instead of force-loading the entire ~30k item catalog at startup.
    // Only multi-word names are stored, since the matcher only ever matches those.
    public synchronized void addItems(Map<String, Integer> items)
    {
        Map<String, Integer> merged = new HashMap<>(itemIndex);
        int max = maxNameWords;
        boolean changed = false;
        for (Map.Entry<String, Integer> e : items.entrySet())
        {
            String key = e.getKey();
            if (key.indexOf(' ') < 0) continue;
            if (merged.putIfAbsent(key, e.getValue()) == null)
            {
                changed = true;
                int words = 1;
                for (int i = 0; i < key.length(); i++) if (key.charAt(i) == ' ') words++;
                if (words > max) max = words;
            }
        }
        if (!changed) return;
        maxNameWords = Math.min(max, 6);
        itemIndex = merged;
        SwingUtilities.invokeLater(this::rebuild);
    }

    // On the EDT (invoked from the input action).
    private void submit()
    {
        String text = input.getText().trim();
        if (text.isEmpty()) return;
        Msg m = new Msg("You", "role-you");
        m.md.append(text);
        messages.add(m);
        input.setText("");
        startThinking();
        rebuild();
        onSubmit.accept(text);
    }

    // Re-render the whole transcript. EDT-only. We build the HTMLDocument ourselves and
    // attach the image cache BEFORE the views are created, so item <img> tags resolve
    // from the cache instead of triggering a network fetch.
    private void rebuild()
    {
        String body = buildBodyHtml();
        try
        {
            HTMLDocument doc = (HTMLDocument) kit.createDefaultDocument();
            doc.putProperty("imageCache", imageCache);
            transcript.setDocument(doc);
            kit.read(new StringReader(body), doc, 0);
            transcript.setCaretPosition(doc.getLength());
        }
        catch (Exception e)
        {
            transcript.setText(body);
        }
    }

    private String buildBodyHtml()
    {
        if (messages.isEmpty())
        {
            registerLogo();
            return "<html><body><center><br><br><br>"
                + "<img src='" + LOGO_URL + "' width='72' height='72'><br><br>"
                + "<span style='color:#8a8a8a;'>Ask Wise Old Claude about your gear,<br>"
                + "quests, drops, or what to do next.</span></center></body></html>";
        }
        StringBuilder html = new StringBuilder("<html><body>");
        for (int i = 0; i < messages.size(); i++)
        {
            Msg m = messages.get(i);
            boolean streaming = streamingId != null && i == messages.size() - 1;
            String rendered = HTML.render(MD.parse(m.md.toString()));
            // Only decorate finished assistant messages, so icons don't flicker mid-stream.
            if (!streaming && "role-claude".equals(m.cssClass)) rendered = injectItemIcons(rendered);
            html.append("<div class='msg'><span class='").append(m.cssClass).append("'>")
                .append(escape(m.role)).append("</span>").append(reasoningBlock(m, i)).append(rendered).append("</div>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    // Post-process the rendered HTML: inject item sprites in normal text, but inside <a> links
    // skip icons and shorten a bare-URL label (keeps clicks working, avoids horizontal overflow).
    private String injectItemIcons(String html)
    {
        StringBuilder out = new StringBuilder(html.length() + 64);
        int i = 0, n = html.length();
        boolean inAnchor = false;
        while (i < n)
        {
            if (html.charAt(i) == '<')
            {
                int gt = html.indexOf('>', i);
                if (gt < 0) { out.append(html, i, n); break; }
                String tag = html.substring(i, gt + 1);
                String lower = tag.toLowerCase(Locale.ROOT);
                if (lower.startsWith("<a ") || lower.equals("<a>")) inAnchor = true;
                else if (lower.startsWith("</a")) inAnchor = false;
                out.append(tag);
                i = gt + 1;
            }
            else
            {
                int lt = html.indexOf('<', i);
                if (lt < 0) lt = n;
                String text = html.substring(i, lt);
                if (inAnchor) out.append(shortenLinkText(text));
                else if (!itemIndex.isEmpty()) out.append(replaceItemsInText(text));
                else out.append(text);
                i = lt;
            }
        }
        return out.toString();
    }

    // Bare URLs used as their own link text get shortened to the wiki page name (or host).
    private static String shortenLinkText(String text)
    {
        String t = text.trim();
        if (!t.startsWith("http")) return text;
        try
        {
            int w = t.indexOf("/w/");
            if (w >= 0)
            {
                String seg = t.substring(w + 3);
                int cut = seg.length();
                for (String d : new String[]{ "/", "#", "?" }) { int k = seg.indexOf(d); if (k >= 0) cut = Math.min(cut, k); }
                seg = URLDecoder.decode(seg.substring(0, cut), "UTF-8").replace('_', ' ');
                if (!seg.isEmpty()) return truncate(seg, 46);
            }
            int p = t.indexOf("://");
            String rest = p >= 0 ? t.substring(p + 3) : t;
            int slash = rest.indexOf('/');
            return truncate(slash >= 0 ? rest.substring(0, slash) : rest, 46);
        }
        catch (Exception e) { return truncate(t, 46); }
    }

    private static String truncate(String s, int max)
    {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private String replaceItemsInText(String text)
    {
        Matcher m = WORD.matcher(text);
        List<int[]> spans = new ArrayList<>();
        List<String> words = new ArrayList<>();
        while (m.find()) { spans.add(new int[]{ m.start(), m.end() }); words.add(m.group()); }
        if (words.isEmpty()) return text;

        StringBuilder out = new StringBuilder();
        int lastEnd = 0;
        int p = 0;
        while (p < words.size())
        {
            int matchedK = 0, matchedId = -1;
            int maxK = Math.min(maxNameWords, words.size() - p);
            // Only icon multi-word item names (k >= 2): single common words like "hammer"
            // or "list" are also item names and cause noisy false-positive icons.
            for (int k = maxK; k >= 2; k--)
            {
                StringBuilder cand = new StringBuilder();
                for (int j = 0; j < k; j++) { if (j > 0) cand.append(' '); cand.append(words.get(p + j)); }
                Integer id = itemIndex.get(cand.toString().toLowerCase(Locale.ROOT));
                if (id != null) { matchedK = k; matchedId = id; break; }
            }
            if (matchedK > 0)
            {
                int spanStart = spans.get(p)[0];
                int spanEnd = spans.get(p + matchedK - 1)[1];
                out.append(text, lastEnd, spanStart);
                out.append(iconTag(matchedId)).append(text, spanStart, spanEnd);
                registerImage(matchedId);
                lastEnd = spanEnd;
                p += matchedK;
            }
            else
            {
                p += 1;
            }
        }
        out.append(text, lastEnd, text.length());
        return out.toString();
    }

    private static String iconTag(int id)
    {
        return "<img src='http://127.0.0.1/woc-item/" + id + "' width='18' height='16'>&nbsp;";
    }

    private static URL itemUrl(int id)
    {
        try { return new URL("http://127.0.0.1/woc-item/" + id); }
        catch (MalformedURLException e) { throw new IllegalStateException(e); }
    }

    // Put the sprite in the document image cache (once per id). Sprites load asynchronously;
    // repaint when each finishes so a blank icon fills in.
    private void registerImage(int id)
    {
        if (!registered.add(id)) return;
        try
        {
            Image img = itemImage.apply(id);
            if (img == null) return;
            imageCache.put(itemUrl(id), img);
            if (img instanceof AsyncBufferedImage)
            {
                ((AsyncBufferedImage) img).onLoaded(() -> SwingUtilities.invokeLater(transcript::repaint));
            }
        }
        catch (Exception ignored)
        {
        }
    }

    // A collapsible "reasoning" section: a toggle link, plus the reasoning text when expanded.
    private String reasoningBlock(Msg m, int index)
    {
        if (m.reasoning.length() == 0) return "";
        StringBuilder b = new StringBuilder();
        b.append("<div style='margin:1px 0 3px 0;'><a href='woc-toggle:").append(index).append("' style='color:#888;'>")
            .append(m.reasoningExpanded ? "&#9662; hide reasoning" : "&#9656; show reasoning")
            .append("</a></div>");
        if (m.reasoningExpanded)
        {
            b.append("<div style='color:#8a8a8a; font-style:italic; margin:0 0 4px 6px;'>")
                .append(escape(m.reasoning.toString()).replace("\n", "<br>"))
                .append("</div>");
        }
        return b.toString();
    }

    private void registerLogo()
    {
        if (logoRegistered || logo == null) return;
        try { imageCache.put(new URL(LOGO_URL), logo); logoRegistered = true; }
        catch (MalformedURLException ignored) {}
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
        SwingUtilities.invokeLater(() -> { ensureAssistant(id).md.append(text); rebuild(); });
    }

    @Override public void onThinking(String id, String text)
    {
        SwingUtilities.invokeLater(() -> {
            Msg m = ensureAssistant(id);
            m.reasoningExpanded = true;
            m.reasoning.append(text);
            rebuild();
        });
    }

    // Return the assistant message for this stream id, creating it on the first delta/thinking.
    private Msg ensureAssistant(String id)
    {
        if (!id.equals(streamingId))
        {
            stopThinking();
            Msg m = new Msg("Wise Old Claude", "role-claude");
            m.id = id;
            messages.add(m);
            streamingId = id;
            return m;
        }
        return messages.get(messages.size() - 1);
    }

    private void toggleReasoning(String indexStr)
    {
        try
        {
            int idx = Integer.parseInt(indexStr);
            if (idx >= 0 && idx < messages.size())
            {
                Msg m = messages.get(idx);
                m.reasoningExpanded = !m.reasoningExpanded;
                rebuild();
            }
        }
        catch (NumberFormatException ignored) {}
    }

    @Override public void onDone(String id)
    {
        SwingUtilities.invokeLater(() -> {
            stopThinking();
            for (Msg m : messages) if (id.equals(m.id)) m.reasoningExpanded = false;
            streamingId = null;
            rebuild();
        });
    }

    @Override public void onError(String id, String message)
    {
        SwingUtilities.invokeLater(() -> {
            stopThinking();
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
