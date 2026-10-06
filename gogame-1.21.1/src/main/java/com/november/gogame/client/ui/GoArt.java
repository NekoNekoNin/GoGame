package com.november.gogame.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.november.gogame.GoGameMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Shared artwork with full-image UVs, independent of the PNG's resolution. */
public final class GoArt {
    public static final ResourceLocation BACKDROP = texture("ui/backdrop.png");
    public static final ResourceLocation BLACK_STONE = texture("ui/stone_black.png");
    public static final ResourceLocation WHITE_STONE = texture("ui/stone_white.png");
    public static final ResourceLocation APP_ICON = texture("app/go-v2.png");

    private GoArt() {}

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(GoGameMod.MODID, "textures/" + name);
    }

    public static void image(GuiGraphics g, ResourceLocation texture, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // Source and texture extents are both one, so the image is sampled from UV 0 to UV 1.
        g.blit(texture, x, y, w, h, 0.0F, 0.0F, 1, 1, 1, 1);
        RenderSystem.disableBlend();
    }
}
