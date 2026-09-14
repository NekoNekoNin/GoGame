package com.november.gogame.client;

import com.november.gogame.GoGameMod;
import com.november.gogame.client.icon.GoIcon;
import com.november.gogame.client.ui.LobbyPage;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.ui.IPhonePage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 手机主屏上的「围棋」格子。
 *
 * 本类只提供 SPI 发现所需的最小实现——id、名字、图标、点开给哪一页。Phase 3 起
 * {@link LobbyPage} 已是真大厅（创建 / 凭号加入 / 在线邀请 / 响应邀请 / 离开）；对局一开始由
 * 大厅页跳全屏 {@code GoBoardScreen} 落子。AI 入口仍占位（Phase 4）。
 *
 * id 用自己的命名空间 {@code gogame}：绝不继承 mcphone 内建的 PhoneApp 基类
 * （那会把命名空间写死成 mcphone，与本体将来的同名 App 撞车，撞车后登记者被静默丢弃）。
 */
public final class GoApp implements IPhoneApp {

    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(GoGameMod.MODID, "go");

    /** 懒建 + 单实例复用：关掉手机再打开，房间号输入草稿仍在（子面板与滚动位置在 onOpen 里复位） */
    private LobbyPage page;

    /** SPI 要一个公开的无参构造 */
    public GoApp() {}

    @Override
    public ResourceLocation getId() { return ID; }

    @Override
    public Component getDisplayName() {
        return Component.translatable("gogame.app.go");
    }

    @Override
    public ResourceLocation getIconTexture() { return GoIcon.TEXTURE; }

    /**
     * Phase 0 占位图标：程序化画一个简化围棋盘（木色底 + 网格 + 一黑一白两子），
     * 不依赖 png，主屏图标即刻可见。Phase 5 会把正式贴图放到 getIconTexture 指向的
     * assets/gogame/textures/app/go.png，供商店详情页等走 getIconTexture（而非
     * renderIcon）的地方使用。
     */
    @Override
    public void renderIcon(GuiGraphics g, int x, int y, int size, float partialTick) {
        int bg = 0xFFD9A85C;    // 木色底
        int line = 0xFF6A4422;  // 深棕网格线
        g.fill(x, y, x + size, y + size, bg);

        int cells = 4;
        int step = size / cells;
        for (int i = 1; i < cells; i++) {
            g.fill(x + i * step, y + 2, x + i * step + 1, y + size - 2, line);
            g.fill(x + 2, y + i * step, x + size - 2, y + i * step + 1, line);
        }

        int r = Math.max(2, step / 2);
        // 黑子（左上交叉点）
        g.fill(x + step - r, y + step - r, x + step + r, y + step + r, 0xFF141414);
        // 白子（右下交叉点）
        g.fill(x + 3 * step - r, y + 3 * step - r, x + 3 * step + r, y + 3 * step + r, 0xFFF2F2F2);
    }

    @Override
    public IPhonePage openPage() {
        if (page == null) page = new LobbyPage();
        return page;
    }

    /**
     * 覆盖了 {@link #openPage()} 之后这个方法不会被调到，但接口要求实现。留空而非抛异常：
     * 万一跑在不认识 openPage 的旧 mcphone 上，抛异常会让玩家点一下就看见崩溃，
     * 什么都不做只是"点了没反应"。（mods.toml 里对 mcphone 的 versionRange 会先挡下旧版。）
     */
    @Override
    public void onPress() {}

    @Override
    public String getVersion() { return GoGameMod.version(); }

    @Override
    public String getAuthor() { return "november521"; }

    /**
     * 商店/详情页那段简介。先问键在不在：查不到的翻译键不会报错，只会把键名原样
     * 画上去，玩家看到一行 "gogame.app.go.desc" 只会以为坏了。
     */
    @Override
    public String getDescription() {
        String key = "gogame.app.go.desc";
        return I18n.exists(key) ? I18n.get(key) : "";
    }
}
