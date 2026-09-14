package com.november.gogame.common.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GoRules} 单测：落子合法性、提子（单子/整块）、禁自杀、自杀因提子转合法、
 * 打劫完整周期（禁即提 → 别处一手 → 劫解禁）、合法着枚举。这是全局正确性的根。
 *
 * 每个局面都手工验算过气与提子，注释给出关键点。
 */
class GoRulesTest {

    private static Board empty() { return new Board(); }

    /** 便捷铺子：直接 set（绕过规则）构造待判定的局面 */
    private static void put(Board b, Stone s, int x, int y) { b.set(x, y, s); }

    // ------------------------------------------------------------------
    // 基本落子
    // ------------------------------------------------------------------

    @Test
    @DisplayName("空点落子合法，产出新盘，原盘不变")
    void placeOnEmptyIsLegal() {
        Board b = empty();
        GoRules.Outcome r = GoRules.play(b, Move.place(Stone.BLACK, 3, 3), GoRules.NO_KO);
        assertTrue(r.legal());
        assertEquals(0, r.captured());
        assertEquals(GoRules.NO_KO, r.koIndex());
        assertEquals(Stone.BLACK, r.board().at(3, 3));
        assertEquals(Stone.EMPTY, b.at(3, 3)); // 原盘未被修改（不可变友好）
    }

    @Test
    @DisplayName("已占点非法：ERR_OCCUPIED，返回原盘同一引用")
    void occupiedIsIllegal() {
        Board b = empty();
        put(b, Stone.BLACK, 5, 5);
        GoRules.Outcome r = GoRules.play(b, Move.place(Stone.WHITE, 5, 5), GoRules.NO_KO);
        assertFalse(r.legal());
        assertEquals(GoRules.ERR_OCCUPIED, r.reasonKey());
        assertSame(b, r.board()); // 非法时原盘原样返回
        assertEquals(Stone.BLACK, r.board().at(5, 5));
    }

    @Test
    @DisplayName("pass / resign 合法、不改盘、清劫")
    void passAndResignLegalAndClearKo() {
        Board b = empty();
        put(b, Stone.BLACK, 2, 2);
        int someKo = Board.index(7, 7);
        GoRules.Outcome pass = GoRules.play(b, Move.pass(Stone.WHITE), someKo);
        assertTrue(pass.legal());
        assertSame(b, pass.board());
        assertEquals(GoRules.NO_KO, pass.koIndex()); // 传入的劫被清空
        assertTrue(GoRules.play(b, Move.resign(Stone.WHITE), someKo).legal());
    }

    // ------------------------------------------------------------------
    // 提子
    // ------------------------------------------------------------------

    @Test
    @DisplayName("提角上单子：黑下 (0,1) 后白 (0,0) 无气被提")
    void captureSingleStone() {
        Board b = empty();
        put(b, Stone.WHITE, 0, 0);
        put(b, Stone.BLACK, 1, 0);
        // 白 (0,0) 现有唯一气 (0,1)，黑下之即提
        GoRules.Outcome r = GoRules.play(b, Move.place(Stone.BLACK, 0, 1), GoRules.NO_KO);
        assertTrue(r.legal());
        assertEquals(1, r.captured());
        assertEquals(Stone.EMPTY, r.board().at(0, 0)); // 白被提
        assertEquals(Stone.BLACK, r.board().at(0, 1));
        // 黑 (0,1) 提后有多气（(0,0)(1,1)(0,2)），非单子单气 → 不成劫
        assertEquals(GoRules.NO_KO, r.koIndex());
    }

    @Test
    @DisplayName("提边上整块：黑下 (1,1) 后白 (0,0)(0,1) 两子同提")
    void captureGroup() {
        Board b = empty();
        put(b, Stone.WHITE, 0, 0);
        put(b, Stone.WHITE, 0, 1);
        put(b, Stone.BLACK, 1, 0);
        put(b, Stone.BLACK, 0, 2);
        // 白块唯一剩余气是 (1,1)
        GoRules.Outcome r = GoRules.play(b, Move.place(Stone.BLACK, 1, 1), GoRules.NO_KO);
        assertTrue(r.legal());
        assertEquals(2, r.captured());
        assertEquals(Stone.EMPTY, r.board().at(0, 0));
        assertEquals(Stone.EMPTY, r.board().at(0, 1));
        assertEquals(GoRules.NO_KO, r.koIndex()); // 提两子不成劫
    }

    @Test
    @DisplayName("一手提两块独立单子：captured==2 → 合法且不成劫（capturedSingle 覆写不生效）")
    void captureTwoSeparateSinglesIsNotKo() {
        Board b = empty();
        // 两白子各被三面包围，唯一气都在 (5,5)
        put(b, Stone.WHITE, 4, 5);
        put(b, Stone.BLACK, 3, 5);
        put(b, Stone.BLACK, 4, 4);
        put(b, Stone.BLACK, 4, 6);
        put(b, Stone.WHITE, 6, 5);
        put(b, Stone.BLACK, 7, 5);
        put(b, Stone.BLACK, 6, 4);
        put(b, Stone.BLACK, 6, 6);
        // 黑下 (5,5)：同时提两白子；(5,4)(5,6) 为空 → 黑子自身有气，非自杀
        GoRules.Outcome r = GoRules.play(b, Move.place(Stone.BLACK, 5, 5), GoRules.NO_KO);
        assertTrue(r.legal());
        assertEquals(2, r.captured());
        assertEquals(Stone.EMPTY, r.board().at(4, 5));
        assertEquals(Stone.EMPTY, r.board().at(6, 5));
        assertEquals(GoRules.NO_KO, r.koIndex()); // 提两子绝不成劫（captured==2 使三条件首项即失败）
    }

    // ------------------------------------------------------------------
    // 禁自杀
    // ------------------------------------------------------------------

    @Test
    @DisplayName("禁自杀：白下 (0,0) 无气且不提子 → ERR_SUICIDE，盘不变")
    void suicideIsIllegal() {
        Board b = empty();
        put(b, Stone.BLACK, 1, 0);
        put(b, Stone.BLACK, 0, 1);
        GoRules.Outcome r = GoRules.play(b, Move.place(Stone.WHITE, 0, 0), GoRules.NO_KO);
        assertFalse(r.legal());
        assertEquals(GoRules.ERR_SUICIDE, r.reasonKey());
        assertSame(b, r.board());
        assertEquals(Stone.EMPTY, b.at(0, 0)); // 自杀子未落上
    }

    @Test
    @DisplayName("自杀因提子转合法：黑下 (0,0) 本无气，但同提两白子得气 → 合法")
    void suicideLegalWhenItCaptures() {
        Board b = empty();
        // 黑三子封住两白子的外气，白 (1,0)(0,1) 仅剩 (0,0) 一口共同气
        put(b, Stone.BLACK, 2, 0);
        put(b, Stone.BLACK, 1, 1);
        put(b, Stone.BLACK, 0, 2);
        put(b, Stone.WHITE, 1, 0);
        put(b, Stone.WHITE, 0, 1);
        // 黑下 (0,0)：落子瞬间自身无气，但提掉两白子后 (1,0)(0,1) 变空成气 → 合法
        GoRules.Outcome r = GoRules.play(b, Move.place(Stone.BLACK, 0, 0), GoRules.NO_KO);
        assertTrue(r.legal());
        assertEquals(2, r.captured());
        assertEquals(Stone.BLACK, r.board().at(0, 0));
        assertEquals(Stone.EMPTY, r.board().at(1, 0));
        assertEquals(Stone.EMPTY, r.board().at(0, 1));
    }

    // ------------------------------------------------------------------
    // 打劫
    // ------------------------------------------------------------------

    /**
     * 构造标准劫形：白 (2,1) 仅一气 (2,2)；黑下 (2,2) 提白后自身成单子单气 → 产生劫。
     * 黑：(1,1)(3,1)(2,0) + 待下 (2,2)；白：(2,1)(1,2)(3,2)(2,3)。
     */
    private static Board koBoard() {
        Board b = empty();
        put(b, Stone.BLACK, 1, 1);
        put(b, Stone.BLACK, 3, 1);
        put(b, Stone.BLACK, 2, 0);
        put(b, Stone.WHITE, 2, 1);
        put(b, Stone.WHITE, 1, 2);
        put(b, Stone.WHITE, 3, 2);
        put(b, Stone.WHITE, 2, 3);
        return b;
    }

    @Test
    @DisplayName("打劫①：黑下 (2,2) 提一子且成单子单气 → 劫争点=(2,1)")
    void koIsCreated() {
        GoRules.Outcome r = GoRules.play(koBoard(), Move.place(Stone.BLACK, 2, 2), GoRules.NO_KO);
        assertTrue(r.legal());
        assertEquals(1, r.captured());
        assertEquals(Board.index(2, 1), r.koIndex()); // 被提点成劫
        assertTrue(r.hasKo());
    }

    @Test
    @DisplayName("打劫②：劫争点上白即提被禁 → ERR_KO")
    void koBlocksImmediateRecapture() {
        GoRules.Outcome first = GoRules.play(koBoard(), Move.place(Stone.BLACK, 2, 2), GoRules.NO_KO);
        int ko = first.koIndex();
        GoRules.Outcome recapture = GoRules.play(first.board(), Move.place(Stone.WHITE, 2, 1), ko);
        assertFalse(recapture.legal());
        assertEquals(GoRules.ERR_KO, recapture.reasonKey());
        assertSame(first.board(), recapture.board());
    }

    @Test
    @DisplayName("打劫③：别处各下一手后劫解禁，白可回提 (2,1)")
    void koExpiresAfterInterveningMoves() {
        GoRules.Outcome first = GoRules.play(koBoard(), Move.place(Stone.BLACK, 2, 2), GoRules.NO_KO);
        Board b1 = first.board();
        // 白在远处下一手（非劫点）→ 合法，且该手不提子故清空劫
        GoRules.Outcome w = GoRules.play(b1, Move.place(Stone.WHITE, 10, 10), first.koIndex());
        assertTrue(w.legal());
        assertEquals(GoRules.NO_KO, w.koIndex());
        // 黑也在远处应一手
        GoRules.Outcome bk = GoRules.play(w.board(), Move.place(Stone.BLACK, 11, 11), w.koIndex());
        assertTrue(bk.legal());
        // 此时劫已解禁，白回提 (2,1) 合法（并反过来给黑制造劫）
        GoRules.Outcome recapture = GoRules.play(bk.board(), Move.place(Stone.WHITE, 2, 1), bk.koIndex());
        assertTrue(recapture.legal());
        assertEquals(1, recapture.captured()); // 提回黑 (2,2)
        assertEquals(Stone.EMPTY, recapture.board().at(2, 2));
        assertEquals(Board.index(2, 2), recapture.koIndex());
    }

    // ------------------------------------------------------------------
    // 合法着枚举
    // ------------------------------------------------------------------

    @Test
    @DisplayName("空盘合法着 = 361（不含 pass）")
    void legalMovesEmptyBoard() {
        List<Move> moves = GoRules.legalMoves(empty(), Stone.BLACK, GoRules.NO_KO);
        assertEquals(Board.COUNT, moves.size());
    }

    @Test
    @DisplayName("合法着排除自杀点与已占点，且每个都确为合法")
    void legalMovesExcludeIllegal() {
        Board b = empty();
        put(b, Stone.BLACK, 1, 0);
        put(b, Stone.BLACK, 0, 1); // (0,0) 对白是自杀点
        List<Move> moves = GoRules.legalMoves(b, Stone.WHITE, GoRules.NO_KO);
        assertFalse(moves.contains(Move.place(Stone.WHITE, 0, 0))); // 自杀排除
        assertFalse(moves.contains(Move.place(Stone.WHITE, 1, 0))); // 已占排除
        assertTrue(moves.contains(Move.place(Stone.WHITE, 9, 9)));   // 普通点在内
        for (Move m : moves) {
            assertTrue(GoRules.isLegal(b, m, GoRules.NO_KO), "枚举出的着必须合法: " + m);
        }
    }

    @Test
    @DisplayName("合法着尊重劫禁着点")
    void legalMovesRespectKo() {
        GoRules.Outcome first = GoRules.play(koBoard(), Move.place(Stone.BLACK, 2, 2), GoRules.NO_KO);
        List<Move> moves = GoRules.legalMoves(first.board(), Stone.WHITE, first.koIndex());
        assertFalse(moves.contains(Move.place(Stone.WHITE, 2, 1))); // 劫点被排除
    }
}
