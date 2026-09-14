package com.november.gogame.client.icon;

import com.november.gogame.GoGameMod;
import net.minecraft.resources.ResourceLocation;

/**
 * 围棋 App 的图标贴图位置。
 *
 * Phase 0 由 {@code GoApp.renderIcon} 程序化绘制主屏图标，这张 png 尚未放置；
 * Phase 5 会把正式贴图放到 assets/gogame/textures/app/go.png，供商店详情页等
 * 走 getIconTexture（而非 renderIcon）的地方使用。
 */
public final class GoIcon {

    private GoIcon() {}

    public static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(GoGameMod.MODID, "textures/app/go.png");
}
