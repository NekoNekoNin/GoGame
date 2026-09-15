package com.november.gogame.client.ai;

import com.november.gogame.common.rules.Move;
import com.november.gogame.common.rules.Stone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link MoveParser} 单测：三层宽容解析（JSON → 文本 GTP → pass 关键词）与解析失败返回 null。
 *
 * 素材取自 LLM 的真实行为模式：听话时回纯 JSON，不听话时夹带解释、markdown、大小写混用。
 */
class MoveParserTest {

    @Test
    @DisplayName("第一层：纯 JSON {\"move\":\"Q16\"} / pass，大小写与空白都容忍")
    void jsonLayer() {
        assertEquals(Move.place(Stone.BLACK, 15, 3),
                MoveParser.parse("{\"move\":\"Q16\"}", Stone.BLACK));
        assertEquals(Move.place(Stone.WHITE, 15, 3),
                MoveParser.parse("{\"move\": \"q16\"}", Stone.WHITE));
        assertEquals(Move.pass(Stone.BLACK),
                MoveParser.parse("{\"move\":\"pass\"}", Stone.BLACK));
        // JSON 前后夹带碎碎念也能取到（首 { 到末 } 片段）
        assertEquals(Move.place(Stone.BLACK, 3, 15),
                MoveParser.parse("Sure! {\"move\":\"D4\"} Hope that helps.", Stone.BLACK));
    }

    @Test
    @DisplayName("第二层：自由文本里第一个独立 GTP 坐标（只认大写，免得散文里的 at/be 误中）")
    void gtpLayer() {
        assertEquals(Move.place(Stone.WHITE, 15, 3),
                MoveParser.parse("I play Q16 because it takes the corner.", Stone.WHITE));
        assertEquals(Move.place(Stone.BLACK, 3, 15),
                MoveParser.parse("My move: D4, taking the corner.", Stone.BLACK));
        // 句首的 "I "（人称代词）与小写散文词都不构成坐标
        assertNull(MoveParser.parse("I will think about it.", Stone.BLACK));
        assertNull(MoveParser.parse("Let me play at d4 maybe.", Stone.BLACK));
    }

    @Test
    @DisplayName("第三层：文本里出现 pass 一词")
    void passLayer() {
        assertEquals(Move.pass(Stone.WHITE),
                MoveParser.parse("I pass.", Stone.WHITE));
        assertEquals(Move.pass(Stone.BLACK),
                MoveParser.parse("PASS this turn", Stone.BLACK));
    }

    @Test
    @DisplayName("W2：pass 子串带词边界——surpass/compass/passive 不误触虚着")
    void passNeedsWordBoundary() {
        assertNull(MoveParser.parse("This move will surpass your expectation.", Stone.BLACK));
        assertNull(MoveParser.parse("A compass helps on a big board.", Stone.BLACK));
        assertNull(MoveParser.parse("I am passive here.", Stone.BLACK));
        // 但独立成词的 pass 仍然命中
        assertEquals(Move.pass(Stone.BLACK), MoveParser.parse("I'll pass for now.", Stone.BLACK));
    }

    @Test
    @DisplayName("W1：AI 不允许认输——resign 当作解析失败返回 null（交重试/兜底）")
    void aiNeverResigns() {
        assertNull(MoveParser.parse("{\"move\":\"resign\"}", Stone.BLACK));
        assertNull(MoveParser.parse("resign", Stone.WHITE));
    }

    @Test
    @DisplayName("解析不出返回 null：空/纯闲聊/非法坐标/JSON 没有 move 键")
    void unparseable() {
        assertNull(MoveParser.parse(null, Stone.BLACK));
        assertNull(MoveParser.parse("   ", Stone.BLACK));
        assertNull(MoveParser.parse("Let me think about the board.", Stone.BLACK));
        assertNull(MoveParser.parse("{\"move\":\"I10\"}", Stone.BLACK));      // GTP 跳过字母 I
        assertNull(MoveParser.parse("{\"move\":\"U16\"}", Stone.BLACK));      // 列越界
        assertNull(MoveParser.parse("{broken json", Stone.BLACK));
    }

    @Test
    @DisplayName("JSON 没有 move 键时，文本层从键值里把大写坐标捡回来")
    void wrongKeyFallsThroughToText() {
        assertEquals(Move.place(Stone.BLACK, 15, 3),
                MoveParser.parse("{\"reason\":\"Q16\"}", Stone.BLACK));
    }

    @Test
    @DisplayName("JSON 层的 move 非法时落到文本层继续找")
    void jsonFallsThroughToText() {
        // move 键值非法（I10），但正文里还有一个合法的 D4 → 第二层捡回
        assertEquals(Move.place(Stone.BLACK, 3, 15),
                MoveParser.parse("{\"move\":\"I10\"} I meant D4.", Stone.BLACK));
    }
}
