package com.wiseoldclaude.game;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class ScreenCaptureTest
{
    @Test
    void capturesAndDownscalesFrame()
    {
        BufferedImage frame = new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB);
        ScreenCapture sc = new ScreenCapture(listener -> listener.accept(frame), 1280);
        JsonObject out = sc.capture();
        assertFalse(out.has("error"), out.toString());
        assertEquals("image/png", out.get("mimeType").getAsString());
        assertEquals(1280, out.get("width").getAsInt());
        assertEquals(640, out.get("height").getAsInt());
        assertTrue(out.get("image").getAsString().length() > 100);
    }

    @Test
    void errorsWhenNoFrame()
    {
        ScreenCapture sc = new ScreenCapture(listener -> listener.accept(null), 1280);
        assertTrue(sc.capture().has("error"));
    }
}
