package com.november.gogame.common.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 中国规则数子法终局判定。
 *
 * <p>数「子（活棋）+ 围的空」：某色 area = 该色子数 + 完全被该色包围的空点数。只被一方
 * 邻接的空区归该方；黑白都邻接的空区（单官/中立）算 {@code dame}，不计入任何一方。
 *
 * <p><b>贴子</b>：黑贴 3¾ 子（{@link #KOMI_STONES}=3.75）。折算到 area 差即「黑 area −
 * 白 area &gt; 7.5 才黑胜」——无 dame 时等价于经典的「黑 ≥185 子胜、184 子负」
 * （361 点，半盘 180.5，180.5+3.75=184.25，整数 area 故 185 胜）。
 * {@link Result#margin()} 用「子」单位报：黑185→+0.75（黑胜¾子），黑184→−0.25（白胜¼子）。
 *
 * <p><b>范围限定</b>：本引擎落子即自动提子，终局盘面默认无未提的死子；不做「死子标记/
 * 点眼确认」的高级判定（Phase 1 范围外）——实战中双方连续 pass 前须自行把死子提净，
 * 否则会被当成活子计入 area。
 */
public final class Scoring {

    private Scoring() {}

    /** 黑贴 3¾ 子 */
    public static final double KOMI_STONES = 3.75;

    /**
     * 数子结果。
     *
     * @param blackArea 黑 area = 黑子数 + 黑围空数
     * @param whiteArea 白 area = 白子数 + 白围空数
     * @param dame      中立空点（黑白都邻接，不计任何一方）
     * @param winner    胜方；¾ 贴子使 area 差不可能恰为 0，理论上无平局，EMPTY 仅作兜底
     * @param margin    黑相对白的领先「子」数（已含贴子）：&gt;0 黑胜、&lt;0 白胜，胜方赢 |margin| 子
     */
    public record Result(int blackArea, int whiteArea, int dame, Stone winner, double margin) {
        public boolean blackWins() { return winner == Stone.BLACK; }
        public boolean whiteWins() { return winner == Stone.WHITE; }
        public boolean isDraw() { return winner == Stone.EMPTY; }
    }

    /** 对终局棋盘数子。调用方须确保已是双方连续 pass 后的终局盘面。 */
    public static Result score(Board board) {
        int blackStones = board.countStones(Stone.BLACK);
        int whiteStones = board.countStones(Stone.WHITE);

        boolean[] seen = new boolean[Board.COUNT];
        int blackTerritory = 0, whiteTerritory = 0, dame = 0;

        // 逐片泛洪相连空区，按其邻接到的颜色归属
        for (int i = 0; i < Board.COUNT; i++) {
            if (board.at(i).isStone() || seen[i]) continue;

            List<Integer> region = new ArrayList<>();
            Set<Stone> borders = EnumSet.noneOf(Stone.class);
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(i);
            seen[i] = true;
            while (!queue.isEmpty()) {
                int idx = queue.poll();
                region.add(idx);
                for (int n : board.neighbors(idx)) {
                    Stone s = board.at(n);
                    if (s.isStone()) {
                        borders.add(s);
                    } else if (!seen[n]) {
                        seen[n] = true;
                        queue.add(n);
                    }
                }
            }

            int size = region.size();
            if (borders.size() == 1 && borders.contains(Stone.BLACK)) {
                blackTerritory += size;   // 只邻黑 → 黑空
            } else if (borders.size() == 1) {
                whiteTerritory += size;   // 只邻白 → 白空
            } else {
                dame += size;             // 黑白都邻（或空盘无邻）→ 中立
            }
        }

        int blackArea = blackStones + blackTerritory;
        int whiteArea = whiteStones + whiteTerritory;
        double margin = (blackArea - whiteArea) / 2.0 - KOMI_STONES;
        Stone winner = margin > 0 ? Stone.BLACK : margin < 0 ? Stone.WHITE : Stone.EMPTY;
        return new Result(blackArea, whiteArea, dame, winner, margin);
    }
}
