package com.november.gogame.common.rules;

import java.util.Locale;

/**
 * 一手棋：颜色 + 落点，或 pass / resign。
 *
 * 坐标 x∈[0,18] 左→右、y∈[0,18] 上→下（y=0 顶行，合屏幕渲染）。pass/resign 无落点，
 * 坐标置 -1。规则层纯 Java，无任何 Minecraft 依赖（实施计划 Phase 1）。
 *
 * <p>两种记法互转：
 * <ul>
 *   <li><b>GTP</b>（A1–T19，列跳过字母 I，行 1 在最下）——围棋软件与 LLM 语料最通用的坐标；
 *       A1 = 左下 = (0,18)，T19 = 右上 = (18,0)。</li>
 *   <li><b>SGF</b>（a–t，原点在左上）——棋谱标准格式，Phase 4 的 SGF 文本棋盘用；
 *       SGF 的行与我们的 y 同向（0 在顶），故 {@code 'a'+x, 'a'+y} 直转。</li>
 * </ul>
 */
public record Move(Stone color, int x, int y, Kind kind) {

    /** 一手棋的种类：落子、虚着（pass）、认输（resign） */
    public enum Kind { PLACE, PASS, RESIGN }

    /** 棋盘边长，等价 {@link Board#SIZE}，就近引用便于阅读 */
    public static final int SIZE = Board.SIZE;

    /**
     * 紧凑构造器统一校验，任何构造路径（含 record 反序列化、静态工厂）都经过：
     * 颜色必须是黑或白；PLACE 必须带界内坐标；pass/resign 坐标归一为 -1。
     */
    public Move {
        if (color == null || !color.isStone()) {
            throw new IllegalArgumentException("Move 需要 BLACK 或 WHITE，收到: " + color);
        }
        if (kind == Kind.PLACE) {
            if (!Board.inBounds(x, y)) {
                throw new IllegalArgumentException("落子越界: (" + x + "," + y + ")");
            }
        } else {
            x = -1;
            y = -1;
        }
    }

    public static Move place(Stone color, int x, int y) {
        return new Move(color, x, y, Kind.PLACE);
    }

    public static Move pass(Stone color) {
        return new Move(color, -1, -1, Kind.PASS);
    }

    public static Move resign(Stone color) {
        return new Move(color, -1, -1, Kind.RESIGN);
    }

    public boolean isPlace() { return kind == Kind.PLACE; }
    public boolean isPass() { return kind == Kind.PASS; }
    public boolean isResign() { return kind == Kind.RESIGN; }

    // ------------------------------------------------------------------
    // GTP 记法（A1–T19，跳过 I）
    // ------------------------------------------------------------------

    /** 转 GTP 字符串，如 {@code "Q16"} / {@code "pass"} / {@code "resign"} */
    public String toGtp() {
        return switch (kind) {
            case PASS -> "pass";
            case RESIGN -> "resign";
            case PLACE -> "" + gtpColumn(x) + (SIZE - y);
        };
    }

    /**
     * 从 GTP 字符串解析一手棋。大小写不敏感，容忍首尾空白。
     *
     * @throws IllegalArgumentException 无法解析（缺列/行、含字母 I、越界、非数字行）
     */
    public static Move fromGtp(Stone color, String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("pass")) return pass(color);
        if (s.equals("resign")) return resign(color);
        if (s.length() < 2) {
            throw new IllegalArgumentException("无法解析的 GTP 坐标: \"" + raw + "\"");
        }
        char col = s.charAt(0);
        if (col == 'i') {
            throw new IllegalArgumentException("GTP 记法跳过字母 I: \"" + raw + "\"");
        }
        if (col < 'a' || col > 't') {
            throw new IllegalArgumentException("GTP 列字母非法（应在 a..t 且非 i）: \"" + raw + "\"");
        }
        int x = col - 'a';
        if (col >= 'j') x--; // J..T 去掉被跳过的 I，回落到 8..18
        int row;
        try {
            row = Integer.parseInt(s.substring(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("GTP 行号非数字: \"" + raw + "\"", e);
        }
        int y = SIZE - row;
        return place(color, x, y); // 越界由紧凑构造器抛出
    }

    /** x → GTP 列字母（大写，跳过 I）：0..7→A..H，8..18→J..T */
    private static char gtpColumn(int x) {
        return (char) ('A' + x + (x >= 8 ? 1 : 0));
    }

    // ------------------------------------------------------------------
    // SGF 记法（a–t，原点左上）
    // ------------------------------------------------------------------

    /**
     * 转 SGF 坐标（仅 PLACE 有意义），如 {@code "pd"}。SGF 原点在左上，与本项目 y 同向。
     *
     * @throws IllegalStateException pass / resign 无 SGF 坐标
     */
    public String toSgfCoord() {
        if (kind != Kind.PLACE) {
            throw new IllegalStateException("SGF 坐标只对落子有意义，kind=" + kind);
        }
        return "" + (char) ('a' + x) + (char) ('a' + y);
    }

    @Override
    public String toString() {
        return color.name() + " " + toGtp();
    }
}
