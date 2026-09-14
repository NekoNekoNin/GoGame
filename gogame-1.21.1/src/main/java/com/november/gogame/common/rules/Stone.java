package com.november.gogame.common.rules;

/**
 * 交叉点状态：黑子、白子、空。
 *
 * 规则层最底层的值类型，无任何 Minecraft 依赖——服务端 PVP 权威仲裁与客户端 PVE
 * 本地推演共用同一套（实施计划 Phase 1）。
 */
public enum Stone {
    BLACK, WHITE, EMPTY;

    /** 对手颜色；EMPTY 的对手仍是 EMPTY，省去提子/边界处的特判 */
    public Stone opponent() {
        return switch (this) {
            case BLACK -> WHITE;
            case WHITE -> BLACK;
            case EMPTY -> EMPTY;
        };
    }

    public boolean isStone() { return this != EMPTY; }

    public boolean isEmpty() { return this == EMPTY; }
}
