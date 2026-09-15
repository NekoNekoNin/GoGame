package com.november.gogame.client.icon;

import com.november.gogame.GoGameMod;
import com.november.gogame.client.ui.GoUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 围棋 App 图标：贴图位置 + 主屏图标的程序化绘制。
 *
 * <p>主屏图标由 {@link #render} 画：圆角方外廓（比例对齐 mcphone 原生 App 图标，摆在一起
 * 不突兀）+ 内缩棋盘与两枚圆子。角弧用圆方程算，不走 {@code GoUi.roundRect} 的 45° 切角——
 * 控件圆角才 2~3px 切角看不出，图标圆角是边长的四分之一，切角会切成八边形。
 *
 * <p>{@link #TEXTURE} 供商店详情页等走 getIconTexture（而非 renderIcon）的地方使用；
 * 贴图为 20×20 的 go.png，是 {@link #render}(size=20) 的逐像素复刻（Phase 5 脚本生成），两处同款。
 */
public final class GoIcon {

    private GoIcon() {}

    public static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(GoGameMod.MODID, "textures/app/go.png");

    // ---- 图标配色（独立于 GoUi 的全屏棋盘配色：图标木色更亮，在主屏深底上才跳得出来）----
    private static final int WOOD  = 0xFFE2B269;
    private static final int GRID  = 0xFF6B4A26;
    private static final int BLACK = 0xFF141414;
    private static final int WHITE = 0xFFF2F2F2;

    /**
     * 画主屏图标：圆角方木底 + 居中正方形棋盘 + 对角一黑一白两子。
     * 棋盘墨迹边长 S = size - 2*gap，四边留白**精确相等**（用户要求「与四个角的距离一致」）；
     * 内线与两子全部按镜像构造（b = S-1-a、白子外框 = 黑子墨迹的镜像）。
     * 栅格化物理限制：S 为偶数时中线只能偏半像素（不影响外框与四角距离的严格相等）。
     */
    public static void render(GuiGraphics g, int x, int y, int size) {
        if (size <= 0) return;
        fillRound(g, x, y, size, size, Math.max(3, size / 4), WOOD);

        // gap 四边同值 → 左==右、上==下留白恒等；S 随 size 奇偶，保证 size-2*gap 正好放下
        int gap = Math.max(2, size / 5);
        int S = size - 2 * gap;
        if (S < 9) return;              // 小到摆不下棋盘、或两子外框重叠发糊（S=5/7），只留木底
        // 内线镜像构造：a/b 关于中心互镜像、c 取中线；不用进位除法逐个算（旧写法在部分
        // 尺寸下留白差 1px、内线不镜像，整块棋盘看着歪向一角）
        int a = (S + 1) / 4;
        int b = S - 1 - a;
        int c = (S - 1) / 2;
        for (int p : new int[] { 0, a, c, b, S - 1 }) {
            GoUi.vLine(g, x + gap + p, y + gap, S, GRID);
            GoUi.hLine(g, x + gap, y + gap + p, S, GRID);
        }

        int d = Math.max(3, (S + 2) / 4 - 1);  // 子径比一格略小，相邻子不粘连
        int s0 = a - d / 2;                    // 黑子外接框：落 (1,1) 交叉点
        int s1 = S - d - s0;                   // 白子外接框：黑子墨迹关于棋盘中心的精确镜像
        GoUi.circle(g, x + gap + s0, y + gap + s0, d, BLACK);
        GoUi.circle(g, x + gap + s1, y + gap + s1, d, WHITE);
    }

    /** 真圆角矩形：中间两带 + 四个四分之一圆角，角弧由圆方程算（不用切角的理由见类注释） */
    private static void fillRound(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        r = Math.clamp(r, 0, Math.min(w, h) / 2);
        g.fill(x + r, y, x + w - r, y + h, color);
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int dy = 0; dy < r; dy++) {
            double yy = r - dy - 0.5;
            int dx = (int) Math.ceil(r - Math.sqrt(Math.max(0, r * r - yy * yy)));
            g.fill(x + dx, y + dy, x + r, y + dy + 1, color);
            g.fill(x + w - r, y + dy, x + w - dx, y + dy + 1, color);
            g.fill(x + dx, y + h - 1 - dy, x + r, y + h - dy, color);
            g.fill(x + w - r, y + h - 1 - dy, x + w - dx, y + h - dy, color);
        }
    }
}
