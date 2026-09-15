package com.november.gogame.client.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiDifficulty} 单测：十一档阶梯的不变量。
 *
 * 数值本身会随体验调优，测试盯住的是「阶梯之所以是阶梯」的性质：
 * 温度单调不增、失误率只给两个弱档、每档有独立的翻译键与英文人设。
 */
class AiDifficultyTest {

    @Test
    @DisplayName("共十一档：菜鸟/普通人 + 初段到九段（用户指定的档位表）")
    void elevenLevels() {
        assertEquals(11, AiDifficulty.values().length);
    }

    @Test
    @DisplayName("温度沿档位单调不增（越强的档越确定）")
    void temperatureDecreases() {
        AiDifficulty[] all = AiDifficulty.values();
        for (int i = 1; i < all.length; i++) {
            assertTrue(all[i].temperature() <= all[i - 1].temperature(),
                    all[i] + " 温度不该高于 " + all[i - 1]);
        }
        // 两端都得在合法请求区间 (0,1] 内
        for (AiDifficulty d : all) {
            assertTrue(d.temperature() > 0f && d.temperature() <= 1f);
        }
    }

    @Test
    @DisplayName("失误扰动只给菜鸟/普通人；段位档零扰动，棋力完全交给模型")
    void blunderOnlyForWeakLevels() {
        assertTrue(AiDifficulty.NOVICE.blunderRate() > 0);
        assertTrue(AiDifficulty.AVERAGE.blunderRate() > 0);
        assertTrue(AiDifficulty.NOVICE.blunderRate() > AiDifficulty.AVERAGE.blunderRate());
        for (AiDifficulty d : AiDifficulty.values()) {
            if (d != AiDifficulty.NOVICE && d != AiDifficulty.AVERAGE) {
                assertEquals(0.0, d.blunderRate(), d + " 是段位档，不该有扰动");
            }
        }
    }

    @Test
    @DisplayName("每档翻译键唯一、人设非空且是英文 ASCII（prompt 不跟客户端语言走）")
    void keysAndPersonas() {
        for (AiDifficulty d : AiDifficulty.values()) {
            assertTrue(d.langKey().startsWith("gogame.ai.diff."));
            assertFalse(d.persona().isBlank());
            assertTrue(d.persona().chars().allMatch(ch -> ch < 0x80), d + " 人设应为纯 ASCII");
        }
        long distinct = java.util.Arrays.stream(AiDifficulty.values())
                .map(AiDifficulty::langKey).distinct().count();
        assertEquals(AiDifficulty.values().length, distinct, "翻译键不该重复");
    }
}
