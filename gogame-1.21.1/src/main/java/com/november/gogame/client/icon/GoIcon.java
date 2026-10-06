package com.november.gogame.client.icon;

import com.november.gogame.client.ui.GoArt;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Home tile, store entry, and in-app header share the same transparent artwork. */
public final class GoIcon {
    public static final ResourceLocation TEXTURE = GoArt.APP_ICON;

    private GoIcon() {}

    public static void render(GuiGraphics g, int x, int y, int size) {
        GoArt.image(g, TEXTURE, x, y, size, size);
    }
}
