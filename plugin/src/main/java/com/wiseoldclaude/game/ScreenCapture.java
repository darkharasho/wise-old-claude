package com.wiseoldclaude.game;

import com.google.gson.JsonObject;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

/**
 * Captures the rendered game frame as a base64 PNG so a vision model can see the screen.
 * Uses a one-shot next-frame listener (prod: DrawManager::requestNextFrameListener) which
 * works with the GPU renderer, unlike grabbing the AWT canvas directly.
 */
public class ScreenCapture
{
    /** Seam: register a one-shot listener that receives the next rendered frame. */
    public interface FrameSource
    {
        void requestNextFrame(Consumer<Image> listener);
    }

    private final FrameSource frameSource;
    private final int maxDim;

    public ScreenCapture(FrameSource frameSource, int maxDim)
    {
        this.frameSource = frameSource;
        this.maxDim = maxDim;
    }

    public JsonObject capture()
    {
        JsonObject o = new JsonObject();
        try
        {
            CompletableFuture<Image> future = new CompletableFuture<>();
            frameSource.requestNextFrame(future::complete);
            Image img = future.get(5, TimeUnit.SECONDS);
            if (img == null)
            {
                o.addProperty("error", "no frame captured (is the client rendering?)");
                return o;
            }
            BufferedImage buf = downscale(toBuffered(img), maxDim);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(buf, "png", baos);
            o.addProperty("image", Base64.getEncoder().encodeToString(baos.toByteArray()));
            o.addProperty("mimeType", "image/png");
            o.addProperty("width", buf.getWidth());
            o.addProperty("height", buf.getHeight());
            return o;
        }
        catch (Exception e)
        {
            o.addProperty("error", "capture failed: " + e.getMessage());
            return o;
        }
    }

    private static BufferedImage toBuffered(Image img)
    {
        if (img instanceof BufferedImage) return (BufferedImage) img;
        BufferedImage b = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return b;
    }

    private static BufferedImage downscale(BufferedImage src, int maxDim)
    {
        int w = src.getWidth(), h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxDim || longest <= 0) return src;
        double scale = (double) maxDim / longest;
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return dst;
    }
}
