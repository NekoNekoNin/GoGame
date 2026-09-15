package com.november.gogame.client.ai;

import com.november.gogame.common.rules.Board;
import com.november.gogame.common.rules.Move;
import com.november.gogame.common.rules.Stone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GoPromptBuilder} 单测：system/user/retry 三份 prompt 的关键成分与 ASCII/SGF 表示。
 *
 * 断言「包含什么」而非全文逐字——prompt 措辞会随调优改动，测试盯住的是协议性内容：
 * 输出格式约束、执色、劫争点、坐标换算、SGF 序列。
 */
class GoPromptBuilderTest {

    @Test
    @DisplayName("system：人设 + 执色 + 严格 JSON 输出格式")
    void systemPrompt() {
        String s = GoPromptBuilder.system(AiDifficulty.DAN5, Stone.WHITE);
        assertTrue(s.contains(AiDifficulty.DAN5.persona()));
        assertTrue(s.contains("as White"));
        assertTrue(s.contains("{\"move\":\"<GTP>\"}"));
        assertTrue(s.contains("pass"));
        // 黑方视角
        assertTrue(GoPromptBuilder.system(AiDifficulty.NOVICE, Stone.BLACK).contains("as Black"));
    }

    @Test
    @DisplayName("user：手数/轮到谁/上一手/SGF/ASCII 都在，空盘无劫争行")
    void userPromptEmptyBoard() {
        Board board = new Board();
        String u = GoPromptBuilder.user(board, List.of(), Stone.BLACK, -1, 0);
        assertTrue(u.contains("Move 1"));
        assertTrue(u.contains("To play: Black"));
        assertTrue(u.contains("Last move: none"));
        assertTrue(u.contains("(;GM[1]SZ[19])"));
        assertFalse(u.contains("Ko point"));
        // ASCII 点阵：列头 19 个字母（跳 I，故无 'I'），19 行全空点
        assertTrue(u.contains("ABCDEFGHJKLMNOPQRST"), "列头应跳过字母 I");
        assertFalse(u.contains("ABCDEFGHI"), "列头不该出现 I");
        assertTrue(u.contains("19 " + ".".repeat(19)), "顶行（19）应全空点");
        assertTrue(u.contains(" 1 " + ".".repeat(19)), "底行（1）应全空点");
    }

    @Test
    @DisplayName("user：ASCII 落点与 GTP/SGF 坐标换算一致（Q16 = x15,y3 = SGF qd）")
    void userPromptCoordinates() {
        Board board = new Board();
        Move black = Move.place(Stone.BLACK, 15, 3);   // Q16
        Move white = Move.place(Stone.WHITE, 3, 15);   // D4
        board.set(Board.index(15, 3), Stone.BLACK);
        board.set(Board.index(3, 15), Stone.WHITE);

        String u = GoPromptBuilder.user(board, List.of(black, white), Stone.BLACK, Board.index(15, 3), 2);
        assertTrue(u.contains("Move 3"));
        assertTrue(u.contains("To play: Black"));
        assertTrue(u.contains("Ko point (Black must not play there): Q16"));
        assertTrue(u.contains("Last move: White D4"));
        // SGF 原点左上：(15,3)→'p''d'=pd，(3,15)→'d''p'=dp
        assertTrue(u.contains("(;GM[1]SZ[19];B[pd];W[dp])"));
        // 顶行是 19 行：Q16 在第 19-3=16 行（从顶数第 4 行），该行 x=15 处是 X
        String[] lines = u.split("\n");
        String row16 = findRow(lines, "16 ");
        assertTrue(row16.charAt(3 + 15) == 'X', "Q16 应为黑子 X: " + row16);
        String row4 = findRow(lines, " 4 ");
        assertTrue(row4.charAt(3 + 3) == 'O', "D4 应为白子 O: " + row4);
    }

    @Test
    @DisplayName("SGF：pass 记空坐标")
    void sgfPass() {
        Board board = new Board();
        String u = GoPromptBuilder.user(board, List.of(Move.pass(Stone.BLACK)), Stone.WHITE, -1, 1);
        assertTrue(u.contains("(;GM[1]SZ[19];B[])"));
        assertTrue(u.contains("Last move: Black pass"));
    }

    @Test
    @DisplayName("retry：附上非法回复（截断到 80 字符）与原因，重申只回 JSON")
    void retryAppendsFeedback() {
        String base = GoPromptBuilder.user(new Board(), List.of(), Stone.WHITE, -1, 0);
        String bad = "x".repeat(200);
        String r = GoPromptBuilder.retry(base, bad, "gogame.error.occupied");
        assertTrue(r.startsWith(base));
        // W3：翻译键映射成英文原因（不直接把 gogame.error.* 喂给 LLM）
        assertTrue(r.contains("already occupied"), "应含英文原因");
        assertFalse(r.contains("gogame.error."), "不该把内部翻译键原样喂给模型");
        assertTrue(r.contains("…"), "超长回复应被截断");
        assertFalse(r.contains("x".repeat(100)), "截断后不该再含 100 连 x");
        assertTrue(r.contains("ONLY a JSON object"));
        // badReply 为 null 也不炸；reasonKey 为 null 归到默认英文原因
        assertTrue(GoPromptBuilder.retry(base, null, null).contains("unparseable"));
        assertTrue(GoPromptBuilder.retry(base, null, null).contains("not a legal move"));
    }

    /** 从 ASCII 点阵里找行号前缀匹配的那一行（行号右对齐两格 + 空格） */
    private static String findRow(String[] lines, String rowPrefix) {
        for (String l : lines) {
            if (l.startsWith(rowPrefix)) return l;
        }
        throw new AssertionError("没找到行 " + rowPrefix);
    }
}
