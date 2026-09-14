package com.november.gogame.common.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 19×19 棋盘状态 + 气/连通块计算 + ASCII 渲染。
 *
 * <p>内部一维数组 {@code cells[y*SIZE+x]}，开局全 EMPTY。纯数据 + 纯计算，无 Minecraft 依赖。
 * 规则推演遵循「改前先 {@link #copy()} 出新盘」的不可变友好风格：服务端权威仲裁可无损回滚，
 * 客户端 PVE 本地推演也复用同一套（实施计划 Phase 1）。
 *
 * <p>坐标：x=列∈[0,18] 左→右，y=行∈[0,18] 上→下（y=0 顶行）。一维下标 {@code index=y*SIZE+x}。
 */
public final class Board {

    /** 边长：19 路 */
    public static final int SIZE = 19;
    /** 交叉点总数：361 */
    public static final int COUNT = SIZE * SIZE;

    /**
     * 19 路九星位查表（一维下标 → 是否星位）。用 boolean[] 而非 Set&lt;Integer&gt;，
     * 免去渲染 toString 时 361 次 contains 的装箱开销；ASCII 渲染与 Phase 3/4 画星位复用。
     */
    private static final boolean[] IS_STAR = new boolean[COUNT];
    static {
        int[][] stars = {{3, 3}, {3, 9}, {3, 15}, {9, 3}, {9, 9}, {9, 15}, {15, 3}, {15, 9}, {15, 15}};
        for (int[] p : stars) IS_STAR[index(p[0], p[1])] = true;
    }

    private final Stone[] cells;

    /** 空盘 */
    public Board() {
        this.cells = new Stone[COUNT];
        Arrays.fill(this.cells, Stone.EMPTY);
    }

    private Board(Stone[] cells) {
        this.cells = cells;
    }

    // ------------------------------------------------------------------
    // 坐标换算（静态，无状态）
    // ------------------------------------------------------------------

    public static boolean inBounds(int x, int y) {
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE;
    }

    public static int index(int x, int y) {
        return y * SIZE + x;
    }

    public static int xOf(int index) {
        return index % SIZE;
    }

    public static int yOf(int index) {
        return index / SIZE;
    }

    public static boolean isStarPoint(int x, int y) {
        return inBounds(x, y) && IS_STAR[index(x, y)];
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    public Stone at(int x, int y) {
        // 纵深防御：越界坐标若不拦，index() 会静默绕回错误合法下标，污染棋盘（Phase 2 从网络包
        // 解坐标、Phase 4 AI 偏移搜索都可能喂越界值）。规则层是全局正确性的根，宁可显式抛。
        if (!inBounds(x, y)) throw new IndexOutOfBoundsException("at(x,y) 越界: (" + x + "," + y + ")");
        return cells[index(x, y)];
    }

    public Stone at(int index) {
        return cells[index];
    }

    public void set(int x, int y, Stone s) {
        if (!inBounds(x, y)) throw new IndexOutOfBoundsException("set(x,y) 越界: (" + x + "," + y + ")");
        cells[index(x, y)] = Objects.requireNonNull(s, "stone");
    }

    public void set(int index, Stone s) {
        cells[index] = Objects.requireNonNull(s, "stone");
    }

    public void clear(int index) {
        cells[index] = Stone.EMPTY;
    }

    /** 提掉一整块（连通块的所有子置空） */
    public void removeAll(Set<Integer> indices) {
        for (int i : indices) cells[i] = Stone.EMPTY;
    }

    /** 深拷贝一份新盘，原盘不受影响 */
    public Board copy() {
        return new Board(cells.clone());
    }

    /** 全盘某色子数 */
    public int countStones(Stone color) {
        int n = 0;
        for (Stone s : cells) if (s == color) n++;
        return n;
    }

    public boolean isEmpty() {
        for (Stone s : cells) if (s != Stone.EMPTY) return false;
        return true;
    }

    // ------------------------------------------------------------------
    // 邻接与连通块
    // ------------------------------------------------------------------

    /** (x,y) 的四邻（正交、界内），以一维下标返回 */
    public List<Integer> neighbors(int x, int y) {
        List<Integer> out = new ArrayList<>(4);
        if (x > 0) out.add(index(x - 1, y));
        if (x < SIZE - 1) out.add(index(x + 1, y));
        if (y > 0) out.add(index(x, y - 1));
        if (y < SIZE - 1) out.add(index(x, y + 1));
        return out;
    }

    public List<Integer> neighbors(int index) {
        return neighbors(xOf(index), yOf(index));
    }

    /**
     * (x,y) 处的同色连通块及其气；空点返回 {@code null}。
     *
     * BFS 收集同色正交相连的子，沿途把相邻空点并入 liberties（{@link Set} 去重，
     * 同一空点被块内多子共享只算一气）。返回的 {@link Group#libertyCount()} 即真实气数。
     */
    public Group groupAt(int x, int y) {
        Stone color = at(x, y);
        if (color.isEmpty()) return null;
        Set<Integer> stones = new HashSet<>();
        Set<Integer> liberties = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        int start = index(x, y);
        queue.add(start);
        stones.add(start);
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            for (int n : neighbors(idx)) {
                Stone s = cells[n];
                if (s.isEmpty()) {
                    liberties.add(n);
                } else if (s == color && stones.add(n)) {
                    queue.add(n);
                }
            }
        }
        return new Group(color, stones, liberties);
    }

    public Group groupAt(int index) {
        return groupAt(xOf(index), yOf(index));
    }

    // ------------------------------------------------------------------
    // ASCII 渲染（调试日志 + Phase 4 喂给 LLM 的点阵棋盘）
    // ------------------------------------------------------------------

    /**
     * 渲染成带坐标的点阵棋盘：黑 ●、白 ○、空 ·、空星位 +。列标 A–T（跳过 I），
     * 行标 19→1（顶到底，合 GTP：A1 在左下）。Phase 4 直接把它拼进 AI 的 prompt。
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String header = columnHeader();
        sb.append("    ").append(header).append('\n');
        for (int y = 0; y < SIZE; y++) {
            int row = SIZE - y;
            sb.append(String.format("%2d  ", row));
            for (int x = 0; x < SIZE; x++) {
                sb.append(glyph(cells[index(x, y)], x, y)).append(' ');
            }
            sb.append(String.format("%2d", row)).append('\n');
        }
        sb.append("    ").append(header);
        return sb.toString();
    }

    private static char glyph(Stone s, int x, int y) {
        return switch (s) {
            case BLACK -> '●';
            case WHITE -> '○';
            case EMPTY -> isStarPoint(x, y) ? '+' : '·';
        };
    }

    private static String columnHeader() {
        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < SIZE; x++) {
            sb.append((char) ('A' + x + (x >= 8 ? 1 : 0))).append(' ');
        }
        return sb.toString();
    }
}
