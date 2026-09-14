package com.november.gogame.client.ui;

import com.november.gogame.common.game.GameResult;
import com.november.gogame.common.rules.Stone;
import net.minecraft.client.resources.language.I18n;

import java.util.Locale;

/**
 * 围棋界面共享的文案格式化：终局结果描述、执色名、子差数字。
 *
 * <p>原本 {@link LobbyPage} 与 {@link GoBoardScreen} 各留一份 {@code describeResult}/{@code colorName}，
 * 一旦改文案就得同步改两处、最易漏改（审查 W4/O3）。这三者只依赖 {@link GameResult}/{@link Stone}/
 * {@link I18n}，与「两类互不依赖」无关，故下沉到此处共用一份。
 *
 * <p>结果整句的括号（中/英）交给带参翻译键 {@code gogame.result.line[_with_margin]} 由各语言自己定，
 * 不在 Java 里硬编码全角括号（此前英文环境会输出 {@code Black wins（by score …）} 的坏观感）。
 */
final class GoText {

    private GoText() {}

    /** 执色名：黑 / 白 / —（未定） */
    static String colorName(Stone c) {
        if (c == Stone.BLACK) return I18n.get("gogame.color.black");
        if (c == Stone.WHITE) return I18n.get("gogame.color.white");
        return I18n.get("gogame.color.none");
    }

    /**
     * 终局结果一句话，如「黑胜（数子 3.75子）」/ "Black wins (by score 3.75 stones)"。
     * 只有数子终局（{@link GameResult#hasScore()}）带子差。
     */
    static String describeResult(GameResult r) {
        String who = r.winner() == Stone.BLACK ? I18n.get("gogame.result.black_wins")
                : r.winner() == Stone.WHITE ? I18n.get("gogame.result.white_wins")
                : I18n.get("gogame.result.draw");
        String how = switch (r.reason()) {
            case DOUBLE_PASS -> I18n.get("gogame.result.by_score");
            case RESIGN -> I18n.get("gogame.result.by_resign");
            case DISCONNECT -> I18n.get("gogame.result.by_forfeit");
        };
        if (r.hasScore()) {
            String margin = trimNumber(Math.abs(r.score().margin())) + I18n.get("gogame.result.stones");
            return I18n.get("gogame.result.line_with_margin", who, how, margin);
        }
        return I18n.get("gogame.result.line", who, how);
    }

    /** 把 3.75 / 0.25 这类子差去掉多余的 0，最多两位小数 */
    static String trimNumber(double d) {
        String s = String.format(Locale.ROOT, "%.2f", d);
        s = s.replaceAll("0+$", "");
        s = s.replaceAll("\\.$", "");
        return s;
    }
}
