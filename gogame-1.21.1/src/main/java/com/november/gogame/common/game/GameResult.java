package com.november.gogame.common.game;

import com.november.gogame.common.rules.Scoring;
import com.november.gogame.common.rules.Stone;

/**
 * 一局围棋的终局结果（不可变值对象）。
 *
 * <p>由服务端权威产出（{@link GameRoom} 终局时），通过 {@code GameEndedPacket} 下发两端；
 * 客户端只读渲染，绝不自行判定胜负。既覆盖「双方连续虚着 → 数子分胜负」，也覆盖
 * 「中途结束 → 认输 / 掉线判负」。
 *
 * <p>只有 {@link EndReason#DOUBLE_PASS} 走完整数子，故仅它带 {@link Scoring.Result} 明细；
 * 认输与掉线判负的胜方是确定的，{@link #score()} 为 {@code null}（见 {@link #hasScore()}）。
 *
 * <p>纯值类型，无 Minecraft 依赖——编解码在 {@code common/net/GoCodecs}，规则层保持零 MC 依赖。
 */
public record GameResult(Stone winner, EndReason reason, Scoring.Result score) {

    /** 终局原因 */
    public enum EndReason {
        /** 双方连续虚着，进入数子 */
        DOUBLE_PASS,
        /** 一方认输，对手判胜 */
        RESIGN,
        /** 一方掉线超过宽限期未重连，对手判胜 */
        DISCONNECT
    }

    /** 紧凑构造器：胜方非空（平局兜底用 EMPTY，见 Scoring），原因非空 */
    public GameResult {
        java.util.Objects.requireNonNull(winner, "winner");
        java.util.Objects.requireNonNull(reason, "reason");
    }

    /** 是否带数子明细（仅 DOUBLE_PASS 为 true） */
    public boolean hasScore() {
        return score != null;
    }

    public boolean blackWins() { return winner == Stone.BLACK; }
    public boolean whiteWins() { return winner == Stone.WHITE; }

    /** 数子终局：胜方取自 {@link Scoring.Result#winner()}，带 area/margin 明细供界面显示 */
    public static GameResult byScore(Scoring.Result score) {
        java.util.Objects.requireNonNull(score, "score");
        return new GameResult(score.winner(), EndReason.DOUBLE_PASS, score);
    }

    /** 认输终局：winner 是认输方的对手，无数子明细 */
    public static GameResult byResign(Stone winner) {
        return new GameResult(winner, EndReason.RESIGN, null);
    }

    /** 掉线判负：winner 是留在局中的那方，无数子明细 */
    public static GameResult byForfeit(Stone winner) {
        return new GameResult(winner, EndReason.DISCONNECT, null);
    }
}
