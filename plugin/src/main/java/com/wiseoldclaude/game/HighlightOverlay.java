package com.wiseoldclaude.game;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Draws Claude-requested highlights on the game scene: outlines of named NPCs and
 * markers on world tiles. Highlights auto-expire after a TTL so the screen never
 * stays cluttered. Mutating methods are called from the tool-handling thread; render()
 * runs on the client thread.
 */
public class HighlightOverlay extends Overlay
{
    private static final Color HIGHLIGHT = new Color(0xE0, 0xA0, 0x32);
    private static final Color FILL = new Color(0xE0, 0xA0, 0x32, 40);

    private final Client client;
    private final LongSupplier clock;
    private final Map<String, Long> npcNames = new ConcurrentHashMap<>();
    private final List<TileMark> tiles = new CopyOnWriteArrayList<>();

    private static final class TileMark
    {
        final WorldPoint wp;
        final String label;
        final long expiry;
        TileMark(WorldPoint wp, String label, long expiry) { this.wp = wp; this.label = label; this.expiry = expiry; }
    }

    public HighlightOverlay(Client client, LongSupplier clock)
    {
        this.client = client;
        this.clock = clock;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void highlightNpc(String name, long ttlMs)
    {
        npcNames.put(name.toLowerCase(Locale.ROOT), clock.getAsLong() + ttlMs);
    }

    public void highlightTile(int x, int y, int plane, String label, long ttlMs)
    {
        tiles.add(new TileMark(new WorldPoint(x, y, plane), label, clock.getAsLong() + ttlMs));
    }

    public void clear()
    {
        npcNames.clear();
        tiles.clear();
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        long now = clock.getAsLong();
        npcNames.values().removeIf(expiry -> expiry < now);
        tiles.removeIf(t -> t.expiry < now);

        if (!npcNames.isEmpty())
        {
            for (NPC npc : client.getNpcs())
            {
                if (npc == null || npc.getName() == null) continue;
                if (!npcNames.containsKey(npc.getName().toLowerCase(Locale.ROOT))) continue;
                Shape hull = npc.getConvexHull();
                if (hull != null) OverlayUtil.renderPolygon(g, hull, HIGHLIGHT, FILL, g.getStroke());
            }
        }

        for (TileMark t : tiles)
        {
            if (t.wp.getPlane() != client.getPlane()) continue;
            LocalPoint lp = LocalPoint.fromWorld(client, t.wp);
            if (lp == null) continue;
            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly != null) OverlayUtil.renderPolygon(g, poly, HIGHLIGHT, FILL, g.getStroke());
            if (t.label != null && !t.label.isEmpty())
            {
                Point txt = Perspective.getCanvasTextLocation(client, g, lp, t.label, 0);
                if (txt != null) OverlayUtil.renderTextLocation(g, txt, t.label, HIGHLIGHT);
            }
        }
        return null;
    }
}
