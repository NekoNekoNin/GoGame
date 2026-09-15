package com.november.gogame.client.ai;

import com.november.gogame.common.rules.Board;
import com.november.gogame.common.rules.Move;
import com.november.gogame.common.rules.Stone;

import java.util.List;

/**
 * 拼喂给 LLM 的文本：ASCII 19×19 点阵 + SGF 着法序列双表示（锁定决策：绝不截图给多模态模型——
 * 文本编码零识别误差、不绑定视觉模型、省 token，且 SGF/ASCII 是 LLM 围棋语料主流格式）。
 *
 * <p>prompt 正文用英文写死（不跟客户端语言走）：四家预设模型对英文指令的服从度最稳，
 * 且人设/规则说明与语言文件无关——语言文件只管界面文案。
 *
 * <p>纯静态无状态、零 Minecraft 依赖，可脱机单测。
 */
public final class GoPromptBuilder {

    private GoPromptBuilder() {}

    /** system：人设 + 规则要点 + 严格输出格式 */
    public static String system(AiDifficulty diff, Stone aiColor) {
        return "You are " + diff.persona() + ". You are playing Go on a 19x19 board (Chinese rules) as "
                + colorName(aiColor) + ".\n"
                + "Rules: a stone group with no liberties is captured; suicide is illegal; "
                + "a ko point may not be recaptured immediately.\n"
                + "Reply with ONLY a JSON object: {\"move\":\"<GTP>\"} where <GTP> is a board point like Q16 "
                + "(columns A-T skipping I, rows 1-19 counted from the bottom), or {\"move\":\"pass\"} to pass. "
                + "Never resign; if you have no legal move, reply {\"move\":\"pass\"}. "
                + "No explanations, no extra text.";
    }

    /** user：局面说明 + SGF 着法序列 + ASCII 点阵 */
    public static String user(Board board, List<Move> history, Stone turn, int koIndex, int moveNumber) {
        StringBuilder sb = new StringBuilder();
        sb.append("Move ").append(moveNumber + 1).append(". To play: ").append(colorName(turn)).append(".\n");
        if (koIndex >= 0) {
            sb.append("Ko point (").append(colorName(turn)).append(" must not play there): ")
                    .append(gtpOf(koIndex)).append(".\n");
        }
        sb.append("Last move: ").append(history.isEmpty() ? "none" : describe(history.get(history.size() - 1)))
                .append(".\n");
        sb.append("SGF: ").append(sgf(history)).append("\n");
        sb.append("Board (X black, O white, . empty; top row = 19, bottom row = 1):\n");
        sb.append(ascii(board));
        return sb.toString();
    }

    /** 重试反馈：把上一次非法回复与原因附在局面后面，要求重答 */
    public static String retry(String baseUser, String badReply, String reasonKey) {
        String trimmed = badReply == null ? "" : badReply.replaceAll("\\s+", " ").strip();
        if (trimmed.length() > 80) trimmed = trimmed.substring(0, 80) + "…";
        return baseUser + "\nYour previous reply \"" + trimmed + "\" was illegal or unparseable"
                + " (reason: " + englishReason(reasonKey) + ")"
                + ". Answer again with ONLY a JSON object for a legal move.";
    }

    /**
     * 规则层回的是翻译键（{@code gogame.error.*}，不碰 I18n），但 prompt 面向英文 LLM，
     * 直接喂键名模型读不懂。这里在 prompt 层把键映射成英文原因（规则层不变）。
     * 解析失败路径传的 {@code "unparseable"} 也归到默认分支。
     */
    private static String englishReason(String key) {
        if (key == null) return "it was not a legal move";
        return switch (key) {
            case "gogame.error.occupied" -> "that point is already occupied";
            case "gogame.error.suicide" -> "that move is suicide (your group would have no liberties)";
            case "gogame.error.ko" -> "that point is the ko point and cannot be retaken this turn";
            case "gogame.error.out_of_bounds" -> "that point is off the board";
            default -> "it was not a legal move";
        };
    }

    // ------------------------------------------------------------------
    // 文本表示
    // ------------------------------------------------------------------

    /** ASCII 点阵：列头 A–T（跳 I），行号 19→1，与 GTP 行号同向 */
    private static String ascii(Board board) {
        StringBuilder sb = new StringBuilder();
        sb.append("   ");
        for (int x = 0; x < Board.SIZE; x++) {
            sb.append(gtpColumn(x));
        }
        sb.append('\n');
        for (int y = 0; y < Board.SIZE; y++) {
            int row = Board.SIZE - y;
            sb.append(row < 10 ? " " : "").append(row).append(' ');
            for (int x = 0; x < Board.SIZE; x++) {
                sb.append(switch (board.at(x, y)) {
                    case BLACK -> 'X';
                    case WHITE -> 'O';
                    default -> '.';
                });
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** SGF 着法序列：(;GM[1]SZ[19];B[pd];W[dp]…)；pass 记空坐标 */
    private static String sgf(List<Move> history) {
        StringBuilder sb = new StringBuilder("(;GM[1]SZ[19]");
        for (Move m : history) {
            sb.append(';').append(m.color() == Stone.BLACK ? 'B' : 'W');
            sb.append('[').append(m.isPlace() ? m.toSgfCoord() : "").append(']');
        }
        return sb.append(')').toString();
    }

    private static String describe(Move m) {
        return colorName(m.color()) + " " + (m.isPlace() ? m.toGtp() : "pass");
    }

    /** 一维下标 → GTP 坐标串（颜色只占位，toGtp 不用它） */
    private static String gtpOf(int index) {
        return Move.place(Stone.BLACK, Board.xOf(index), Board.yOf(index)).toGtp();
    }

    private static char gtpColumn(int x) {
        return (char) ('A' + x + (x >= 8 ? 1 : 0));
    }

    private static String colorName(Stone s) {
        return s == Stone.BLACK ? "Black" : "White";
    }
}
