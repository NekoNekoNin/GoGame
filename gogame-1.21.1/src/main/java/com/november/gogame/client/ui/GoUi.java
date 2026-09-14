package com.november.gogame.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 围棋界面自己的绘制小零件：圆角矩形、圆（棋子）、线、截字、命中判定、按钮。
 *
 * <p><b>为什么自己写一份</b>：mcphone 内部有 {@code core.client.GuiUtil}，但这些方法它基本都有——
 * 然而 {@code core} 包不在 API 兼容承诺内（只有 {@code api} 包算数），import 它等于把稳定性押在
 * 本体不重构上。所以这一份是刻意的重复：附属能碰的只有 {@code PhoneCanvas} 给的那支
 * {@link GuiGraphics} 与 {@link Font}，剩下自己搭。附属范本 mcphone-deepseek 的 {@code Ui} 同理。
 *
 * <p>方法都是静态、只吃 {@link GuiGraphics}/{@link Font} + 裸坐标，故手机内页（{@link LobbyPage}
 * 走 {@code PhoneCanvas.graphics()}）与全屏棋盘（{@link GoBoardScreen} 走 {@code Screen} 的
 * {@code GuiGraphics}）共用同一套。坐标语义与各调用方一致（都是屏幕绝对坐标）。
 */
public final class GoUi {

    private GoUi() {}

    // ---- 棋盘配色（全屏棋盘用；手机内页一律走 PhoneStyle，不用这几个）----
    /** 棋盘木色底 */
    public static final int WOOD       = 0xFFDCB35C;
    /** 棋盘网格线（深棕） */
    public static final int GRID       = 0xFF4A3418;
    /** 黑子 */
    public static final int STONE_BLACK = 0xFF151515;
    /** 白子 */
    public static final int STONE_WHITE = 0xFFF4F4F4;
    /** 棋子描边/阴影，让白子在木色上不至于糊成一片 */
    public static final int STONE_EDGE  = 0x66000000;
    /** 最后一手标记 */
    public static final int LAST_MOVE   = 0xFFE2504A;

    // ------------------------------------------------------------------
    // 形状
    // ------------------------------------------------------------------

    /** 圆角矩形。角是 45° 切出来的——这块屏上半径最多几像素，斜边与圆弧看不出区别，且不用开方 */
    public static void roundRect(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) return;
        int r = Math.clamp(radius, 0, Math.min(w, h) / 2);
        if (r == 0) { g.fill(x, y, x + w, y + h, color); return; }
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            int inset = r - i - 1;
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    /**
     * 实心圆（棋子）。以浮点圆心左右各自四舍五入，对称是算出来的不是凑的——
     * 用 {@code (int) r} 截断会让每行宽度必为偶数、圆心偏半像素（范本里踩过的坑）。
     *
     * @param x 外接框左上角 x
     * @param y 外接框左上角 y
     * @param size 直径（像素）
     */
    public static void circle(GuiGraphics g, int x, int y, int size, int color) {
        if (size <= 0) return;
        double r = size / 2.0;
        for (int dy = 0; dy < size; dy++) {
            double yy = dy + 0.5 - r;
            double half = Math.sqrt(Math.max(0, r * r - yy * yy));
            int left = (int) Math.round(r - half);
            int right = (int) Math.round(r + half);
            if (right > left) g.fill(x + left, y + dy, x + right, y + dy + 1, color);
        }
    }

    /** 1 像素横线 */
    public static void hLine(GuiGraphics g, int x, int y, int w, int color) {
        if (w > 0) g.fill(x, y, x + w, y + 1, color);
    }

    /** 1 像素竖线 */
    public static void vLine(GuiGraphics g, int x, int y, int h, int color) {
        if (h > 0) g.fill(x, y, x + 1, y + h, color);
    }

    /** 空心矩形边框（四条 1px 线），输入框/棋盘边框用 */
    public static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        hLine(g, x, y, w, color);
        hLine(g, x, y + h - 1, w, color);
        vLine(g, x, y, h, color);
        vLine(g, x + w - 1, y, h, color);
    }

    // ------------------------------------------------------------------
    // 文字
    // ------------------------------------------------------------------

    /** 一行字在 h 高容器里顶端该放哪（垂直居中） */
    public static int textY(Font font, int containerY, int containerH) {
        return containerY + (containerH - font.lineHeight) / 2;
    }

    /** 填色控件（按钮/输入框）里的文字，比 {@link #textY} 再低 1px：原版字形只占满行框上 8/9，居中会显偏上 */
    public static int controlTextY(Font font, int containerY, int containerH) {
        return textY(font, containerY, containerH) + 1;
    }

    /** 放不下就截断加省略号；省略号宽度按字体真实量 */
    public static String truncate(Font font, String s, int maxWidth) {
        if (s == null || s.isEmpty() || maxWidth <= 0) return "";
        if (font.width(s) <= maxWidth) return s;
        String ellipsis = "…";
        int room = maxWidth - font.width(ellipsis);
        return room <= 0 ? "" : font.plainSubstrByWidth(s, room) + ellipsis;
    }

    /** 在 [x, x+w] 内居中画一行 */
    public static void centered(GuiGraphics g, Font font, String s, int x, int y, int w, int color) {
        g.drawString(font, s, x + (w - font.width(s)) / 2, y, color, false);
    }

    // ------------------------------------------------------------------
    // 命中判定
    // ------------------------------------------------------------------

    public static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------
    // 按钮（手机内页用：画 + 命中一体，全屏棋盘改用原版 Button 控件）
    // ------------------------------------------------------------------

    /**
     * 画一枚手机风格按钮。{@code hovered} 由调用方（{@code PhoneCanvas.hovered}）算好传入，本方法只管绘制；
     * 是否注册点击目标由调用方按 {@code enabled} 决定，与 hover 无关，故不返回值。
     *
     * @param enabled false 时画灰底灰字（不可点）；配色全部来自 {@code PhoneStyle}，由调用方传入
     */
    public static void button(GuiGraphics g, Font font, int x, int y, int w, int h,
                              String label, boolean enabled, boolean hovered,
                              int bgColor, int hoverColor, int disabledColor,
                              int textColor, int disabledTextColor) {
        int fill = !enabled ? disabledColor : (hovered ? hoverColor : bgColor);
        roundRect(g, x, y, w, h, 3, fill);
        int fg = enabled ? textColor : disabledTextColor;
        centered(g, font, truncate(font, label, w - 6), x, controlTextY(font, y, h), w, fg);
    }
}
