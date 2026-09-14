package com.november.gogame.common.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * 围棋规则引擎：把一手棋作用于棋盘，判定合法性、提子、打劫。
 *
 * <p>纯静态无状态函数——不持有任何对局进度（回合、pass 计数、劫争点由调用方保管）。
 * 服务端 PVP 权威仲裁与客户端 PVE 本地推演共用这一套，是全局正确性的根（实施计划 Phase 1）。
 *
 * <p>中国规则要点：
 * <ul>
 *   <li><b>提子</b>：落子后先把无气的对方连通块提掉，再判己方。</li>
 *   <li><b>禁自杀</b>：落子块提完对方后仍无气 → 非法（提子能带来气则合法）。</li>
 *   <li><b>打劫</b>：提对方恰好一子、且己方仅单子单气 → 被提点成劫，对方下一手禁着该点
 *       （最简「单劫禁着」，非全局同形再现）。</li>
 * </ul>
 *
 * <p>非法时返回的 {@link Outcome#reasonKey()} 是<b>翻译键</b>而非译文——规则层不碰 I18n，
 * 由渲染线程按需翻译（见 docs/PITFALLS.md：I18n 只在渲染线程）。
 */
public final class GoRules {

    private GoRules() {}

    /** 无劫争点（koIndex 的哨兵值） */
    public static final int NO_KO = -1;

    // 非法原因翻译键
    public static final String ERR_OCCUPIED = "gogame.error.occupied";
    public static final String ERR_KO = "gogame.error.ko";
    public static final String ERR_SUICIDE = "gogame.error.suicide";

    /**
     * 一手棋作用于棋盘的结果。
     *
     * @param legal     是否合法
     * @param reasonKey 非法原因的翻译键；合法时为 {@code null}
     * @param board     PLACE 合法 → <b>新盘</b>（含提子结果，与入参 {@code board} 是不同引用）；
     *                  PASS/RESIGN 合法、以及所有非法情况 → <b>原盘引用</b>（内容未被修改）。
     *                  契约：把返回值当作不可变快照直接<b>重新赋值</b>给状态，绝不要 mutate 它；
     *                  确需改动请先 {@link Board#copy()}
     * @param captured  这手提掉的对方子数
     * @param koIndex   这手产生的劫争点下标；无劫为 {@link #NO_KO}
     */
    public record Outcome(boolean legal, String reasonKey, Board board, int captured, int koIndex) {
        public boolean hasKo() { return koIndex != NO_KO; }
    }

    /**
     * 尝试把 {@code move} 作用于 {@code board}。pass / resign 恒合法、不改盘、清劫。
     *
     * @param board    当前棋盘（不会被修改，落子结果在返回的新盘上）
     * @param move     要下的一手（坐标越界不可能，{@link Move} 构造时已拒）
     * @param koIndex  当前劫争点（对方禁着），无则传 {@link #NO_KO}
     * @return 合法性 + 新盘 + 提子数 + 新劫争点
     */
    public static Outcome play(Board board, Move move, int koIndex) {
        // 虚着 / 认输：不改盘，清空劫争点（下一手不受旧劫约束）
        if (move.isPass() || move.isResign()) {
            return new Outcome(true, null, board, 0, NO_KO);
        }

        int idx = Board.index(move.x(), move.y());
        if (!board.at(idx).isEmpty()) {
            return illegal(ERR_OCCUPIED, board);
        }
        if (idx == koIndex) {
            return illegal(ERR_KO, board);
        }

        Stone color = move.color();
        Stone opp = color.opponent();
        Board next = board.copy();
        next.set(idx, color);

        // 提子：落子后无气的对方连通块全部提掉
        int captured = 0;
        int capturedSingle = NO_KO; // 恰提一子时记其下标，供劫判定
        for (int n : next.neighbors(idx)) {
            if (next.at(n) == opp) {
                Group g = next.groupAt(n);
                if (g.isDead()) {
                    captured += g.size();
                    if (g.size() == 1) {
                        capturedSingle = g.stones().iterator().next();
                    }
                    next.removeAll(g.stones());
                }
            }
        }

        // 禁自杀：提完对方后己方块仍无气 → 非法（提子若带来气，这里已非无气）
        Group own = next.groupAt(idx);
        if (own.isDead()) {
            return illegal(ERR_SUICIDE, board);
        }

        // 打劫：提对方恰好一子 + 己方单子 + 该单子仅一气 → 被提点成劫，对方下一手禁着
        int newKo = NO_KO;
        if (captured == 1 && own.size() == 1 && own.libertyCount() == 1) {
            newKo = capturedSingle;
        }
        return new Outcome(true, null, next, captured, newKo);
    }

    /** 便捷合法性判定（会走完整推演，含拷贝；Phase 4 AI 校验/重试够用） */
    public static boolean isLegal(Board board, Move move, int koIndex) {
        return play(board, move, koIndex).legal();
    }

    /**
     * 枚举 {@code color} 在 {@code board} 上所有合法落子（不含 pass）。
     * Phase 4 AI 反复非法时的「随机合法着兜底」从这里取。
     */
    public static List<Move> legalMoves(Board board, Stone color, int koIndex) {
        List<Move> moves = new ArrayList<>();
        for (int y = 0; y < Board.SIZE; y++) {
            for (int x = 0; x < Board.SIZE; x++) {
                int idx = Board.index(x, y);
                if (!board.at(idx).isEmpty() || idx == koIndex) continue;
                Move m = Move.place(color, x, y);
                if (play(board, m, koIndex).legal()) moves.add(m);
            }
        }
        return moves;
    }

    private static Outcome illegal(String reasonKey, Board board) {
        return new Outcome(false, reasonKey, board, 0, NO_KO);
    }
}
