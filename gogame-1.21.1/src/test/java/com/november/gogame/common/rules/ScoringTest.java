package com.november.gogame.common.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Scoring} 单测：中国规则数子法——子 + 围空、单官(dame)归属、黑贴 3¾ 子的胜负阈值。
 *
 * komi 阈值用「填满整盘的合成局面」精确构造 area 差（score() 是纯 area 计数器，不校验死活，
 * 故实心无气子块仅用于验算贴子数学，非真实对局形态）。
 */
class ScoringTest {

    /** 填满整盘：前 blackCount 个下标置黑，其余置白，无空点（dame=0），精确控制 area 差 */
    private static Board filled(int blackCount) {
        Board b = new Board();
        for (int i = 0; i < Board.COUNT; i++) {
            b.set(i, i < blackCount ? Stone.BLACK : Stone.WHITE);
        }
        return b;
    }

    @Test
    @DisplayName("空盘：全部 361 点无归属算 dame，白靠贴子胜")
    void emptyBoardIsAllDame() {
        Scoring.Result r = Scoring.score(new Board());
        assertEquals(0, r.blackArea());
        assertEquals(0, r.whiteArea());
        assertEquals(Board.COUNT, r.dame());
        assertTrue(r.whiteWins());
        assertEquals(-Scoring.KOMI_STONES, r.margin(), 1e-9);
    }

    @Test
    @DisplayName("两道墙分地盘：黑墙左空归黑、白墙右空归白、中间双方都邻算 dame")
    void territoryAttributionAndDame() {
        Board b = new Board();
        for (int y = 0; y < Board.SIZE; y++) {
            b.set(1, y, Stone.BLACK);   // 黑墙在 x=1
            b.set(17, y, Stone.WHITE);  // 白墙在 x=17
        }
        Scoring.Result r = Scoring.score(b);
        // 黑：19 子 + x=0 列 19 空 = 38；白：19 子 + x=18 列 19 空 = 38
        assertEquals(38, r.blackArea());
        assertEquals(38, r.whiteArea());
        // 中间 x=2..16 共 15×19=285 空点，黑白都邻 → dame
        assertEquals(15 * Board.SIZE, r.dame());
        assertEquals(38 + 38 + 285, Board.COUNT); // 面积守恒
        assertTrue(r.whiteWins());                // area 相等，白靠贴子胜
        assertEquals(-Scoring.KOMI_STONES, r.margin(), 1e-9);
    }

    @Test
    @DisplayName("黑围住的眼位与外场都算黑空（盘上无白）")
    void enclosedTerritoryCounts() {
        Board b = new Board();
        // 黑环围住 (2,2)
        int[][] ring = {{1,1},{1,2},{1,3},{2,1},{2,3},{3,1},{3,2},{3,3}};
        for (int[] p : ring) b.set(p[0], p[1], Stone.BLACK);
        Scoring.Result r = Scoring.score(b);
        assertEquals(8 + (Board.COUNT - 8), r.blackArea()); // 8 子 + 其余全归黑空 = 361
        assertEquals(Board.COUNT, r.blackArea());
        assertEquals(0, r.whiteArea());
        assertEquals(0, r.dame());
        assertTrue(r.blackWins());
    }

    @Test
    @DisplayName("贴子阈值：黑 area 185（差 9）→ 黑胜¾子")
    void komiThreshold_black185Wins() {
        Scoring.Result r = Scoring.score(filled(185));
        assertEquals(185, r.blackArea());
        assertEquals(176, r.whiteArea());
        assertEquals(0, r.dame());
        assertTrue(r.blackWins());
        assertEquals(0.75, r.margin(), 1e-9); // 黑胜 3/4 子
    }

    @Test
    @DisplayName("贴子阈值：黑 area 184（差 7）→ 白胜¼子（黑差一子到 185）")
    void komiThreshold_black184Loses() {
        Scoring.Result r = Scoring.score(filled(184));
        assertEquals(184, r.blackArea());
        assertEquals(177, r.whiteArea());
        assertTrue(r.whiteWins());
        assertEquals(-0.25, r.margin(), 1e-9); // 白胜 1/4 子
    }
}
