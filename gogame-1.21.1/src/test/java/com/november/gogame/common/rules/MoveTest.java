package com.november.gogame.common.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Move} 单测：GTP / SGF 记法互转、pass/resign、构造校验、非法输入。
 *
 * 坐标约定复核：x∈[0,18] 左→右，y∈[0,18] 上→下（y=0 顶行）；GTP A1=左下=(0,18)，T19=右上=(18,0)。
 */
class MoveTest {

    @Test
    @DisplayName("GTP 角点互转：A1=(0,18) 左下，T19=(18,0) 右上")
    void gtpCorners() {
        assertEquals("A1", Move.place(Stone.BLACK, 0, 18).toGtp());
        assertEquals("T19", Move.place(Stone.WHITE, 18, 0).toGtp());
        assertEquals("A19", Move.place(Stone.BLACK, 0, 0).toGtp());
        assertEquals("T1", Move.place(Stone.BLACK, 18, 18).toGtp());

        assertEquals(Move.place(Stone.BLACK, 0, 18), Move.fromGtp(Stone.BLACK, "A1"));
        assertEquals(Move.place(Stone.WHITE, 18, 0), Move.fromGtp(Stone.WHITE, "T19"));
    }

    @Test
    @DisplayName("GTP 列跳过字母 I：H→J 连续，绝不产出 I")
    void gtpSkipsLetterI() {
        // x=7→H, x=8→J（跳过 I）
        assertEquals("H10", Move.place(Stone.BLACK, 7, 9).toGtp());
        assertEquals("J10", Move.place(Stone.BLACK, 8, 9).toGtp());
        // 星位 Q16：x=15→Q, y=3→行16
        assertEquals("Q16", Move.place(Stone.BLACK, 15, 3).toGtp());
        assertEquals(Move.place(Stone.BLACK, 15, 3), Move.fromGtp(Stone.BLACK, "Q16"));
        // 含 I 的坐标非法
        assertThrows(IllegalArgumentException.class, () -> Move.fromGtp(Stone.BLACK, "I10"));
    }

    @Test
    @DisplayName("GTP 全部列字母映射：0..7→A..H，8..18→J..T")
    void gtpColumnMapping() {
        char[] expected = {'A','B','C','D','E','F','G','H','J','K','L','M','N','O','P','Q','R','S','T'};
        for (int x = 0; x < Board.SIZE; x++) {
            String gtp = Move.place(Stone.BLACK, x, 18).toGtp(); // y=18 → 行1，单字符行号好断言
            assertEquals(expected[x] + "1", gtp, "x=" + x);
            assertEquals(x, Move.fromGtp(Stone.BLACK, gtp).x(), "回读 x=" + x);
        }
    }

    @Test
    @DisplayName("GTP 解析大小写不敏感、容忍首尾空白")
    void gtpCaseInsensitiveAndTrim() {
        assertEquals(Move.place(Stone.BLACK, 15, 3), Move.fromGtp(Stone.BLACK, " q16 "));
        assertEquals(Move.place(Stone.BLACK, 15, 3), Move.fromGtp(Stone.BLACK, "Q16"));
    }

    @Test
    @DisplayName("pass / resign：坐标归 -1，GTP 文本正确，可回读")
    void passAndResign() {
        Move pass = Move.pass(Stone.BLACK);
        assertTrue(pass.isPass());
        assertEquals(-1, pass.x());
        assertEquals(-1, pass.y());
        assertEquals("pass", pass.toGtp());
        assertEquals(pass, Move.fromGtp(Stone.BLACK, "PASS"));

        Move resign = Move.resign(Stone.WHITE);
        assertTrue(resign.isResign());
        assertEquals("resign", resign.toGtp());
        assertEquals(resign, Move.fromGtp(Stone.WHITE, "Resign"));
    }

    @Test
    @DisplayName("SGF 坐标原点左上：(0,0)→aa，Q16=(15,3)→pd，(18,18)→ss")
    void sgfCoord() {
        assertEquals("aa", Move.place(Stone.BLACK, 0, 0).toSgfCoord());
        assertEquals("pd", Move.place(Stone.BLACK, 15, 3).toSgfCoord());
        assertEquals("ss", Move.place(Stone.BLACK, 18, 18).toSgfCoord()); // a=0..s=18（tt 是 SGF 的 pass 约定，非角点）
    }

    @Test
    @DisplayName("SGF 坐标对 pass/resign 抛异常（无落点）")
    void sgfThrowsForNonPlace() {
        assertThrows(IllegalStateException.class, () -> Move.pass(Stone.BLACK).toSgfCoord());
        assertThrows(IllegalStateException.class, () -> Move.resign(Stone.BLACK).toSgfCoord());
    }

    @Test
    @DisplayName("非法 GTP 输入一律抛 IllegalArgumentException")
    void invalidGtpInputs() {
        assertThrows(IllegalArgumentException.class, () -> Move.fromGtp(Stone.BLACK, ""));
        assertThrows(IllegalArgumentException.class, () -> Move.fromGtp(Stone.BLACK, null));
        assertThrows(IllegalArgumentException.class, () -> Move.fromGtp(Stone.BLACK, "A"));   // 缺行
        assertThrows(IllegalArgumentException.class, () -> Move.fromGtp(Stone.BLACK, "A0"));   // 行0→y19 越界
        assertThrows(IllegalArgumentException.class, () -> Move.fromGtp(Stone.BLACK, "A20"));  // 行20→y-1 越界
        assertThrows(IllegalArgumentException.class, () -> Move.fromGtp(Stone.BLACK, "Z1"));   // 列越界
        assertThrows(IllegalArgumentException.class, () -> Move.fromGtp(Stone.BLACK, "Ax"));   // 行非数字
    }

    @Test
    @DisplayName("构造校验：拒绝空色、拒绝越界落点")
    void constructionValidation() {
        assertThrows(IllegalArgumentException.class, () -> Move.place(Stone.EMPTY, 3, 3));
        assertThrows(IllegalArgumentException.class, () -> new Move(Stone.BLACK, 19, 0, Move.Kind.PLACE));
        assertThrows(IllegalArgumentException.class, () -> new Move(Stone.BLACK, -1, 0, Move.Kind.PLACE));
    }
}
