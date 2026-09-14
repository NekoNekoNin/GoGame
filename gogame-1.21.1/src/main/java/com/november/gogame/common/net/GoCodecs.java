package com.november.gogame.common.net;

import com.november.gogame.common.game.GameResult;
import com.november.gogame.common.game.GameRoom;
import com.november.gogame.common.rules.Board;
import com.november.gogame.common.rules.Move;
import com.november.gogame.common.rules.Scoring;
import com.november.gogame.common.rules.Stone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 围棋全部线格式的编解码器集中处。
 *
 * <p>规则层（{@code common/rules}）与对局域模型（{@code common/game}）刻意保持零 Minecraft
 * 依赖，所以它们的 {@code StreamCodec} 不住在自己类里，全放这里——网络层是纯类型与
 * Netty/Minecraft 编解码之间唯一的桥。
 *
 * <p>两条例外防御，与本体 {@code Relation.STREAM_CODEC} 同源：
 * <ul>
 *   <li><b>枚举</b>按 ordinal 传输，解码越界一律退回安全兜底值，宁可显示错也不抛异常断线。</li>
 *   <li><b>棋盘</b>每点 1 字节（0 空 / 1 黑 / 2 白），认不出的字节当空点，绝不 {@code ArrayIndexOutOfBounds}。</li>
 * </ul>
 */
public final class GoCodecs {

    private GoCodecs() {}

    // 棋盘每点的字节编码
    private static final byte BYTE_EMPTY = 0;
    private static final byte BYTE_BLACK = 1;
    private static final byte BYTE_WHITE = 2;

    /**
     * 枚举 codec 工厂：VAR_INT 存 ordinal，越界退回 {@code fallback}。
     * 泛型复用一套逻辑，避免每个枚举各写一遍匿名类（本体 Relation 是单枚举手写版）。
     */
    private static <E extends Enum<E>> StreamCodec<ByteBuf, E> enumCodec(E[] values, E fallback) {
        return new StreamCodec<>() {
            @Override
            public E decode(ByteBuf buf) {
                int ordinal = ByteBufCodecs.VAR_INT.decode(buf);
                return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : fallback;
            }

            @Override
            public void encode(ByteBuf buf, E value) {
                ByteBufCodecs.VAR_INT.encode(buf, value.ordinal());
            }
        };
    }

    public static final StreamCodec<ByteBuf, Stone> STONE =
            enumCodec(Stone.values(), Stone.EMPTY);

    public static final StreamCodec<ByteBuf, Move.Kind> MOVE_KIND =
            enumCodec(Move.Kind.values(), Move.Kind.PLACE);

    public static final StreamCodec<ByteBuf, GameResult.EndReason> END_REASON =
            enumCodec(GameResult.EndReason.values(), GameResult.EndReason.DOUBLE_PASS);

    public static final StreamCodec<ByteBuf, GameRoom.Phase> PHASE =
            enumCodec(GameRoom.Phase.values(), GameRoom.Phase.WAITING);

    public static final StreamCodec<ByteBuf, GameRoom.ColorMode> COLOR_MODE =
            enumCodec(GameRoom.ColorMode.values(), GameRoom.ColorMode.RANDOM);

    /**
     * 整盘：{@link Board#COUNT} 个字节，逐点写 {@code at(index)}。定长 361，不用先写长度。
     * 解码认不出的字节当空点——畸形包最多让盘面显示错，不会崩服务端或断线。
     */
    public static final StreamCodec<ByteBuf, Board> BOARD = new StreamCodec<>() {
        @Override
        public Board decode(ByteBuf buf) {
            Board board = new Board();
            for (int i = 0; i < Board.COUNT; i++) {
                board.set(i, stoneFromByte(buf.readByte()));
            }
            return board;
        }

        @Override
        public void encode(ByteBuf buf, Board board) {
            for (int i = 0; i < Board.COUNT; i++) {
                buf.writeByte(byteFromStone(board.at(i)));
            }
        }
    };

    private static Stone stoneFromByte(byte b) {
        return switch (b) {
            case BYTE_BLACK -> Stone.BLACK;
            case BYTE_WHITE -> Stone.WHITE;
            default -> Stone.EMPTY;   // 含 BYTE_EMPTY 与任何畸形字节
        };
    }

    private static byte byteFromStone(Stone s) {
        return switch (s) {
            case BLACK -> BYTE_BLACK;
            case WHITE -> BYTE_WHITE;
            case EMPTY -> BYTE_EMPTY;
        };
    }

    /** 数子明细：三个 area/dame 计数 + 胜方 + margin（子，含贴子）。直读直写，不经 composite。 */
    public static final StreamCodec<ByteBuf, Scoring.Result> SCORING_RESULT = new StreamCodec<>() {
        @Override
        public Scoring.Result decode(ByteBuf buf) {
            int blackArea = ByteBufCodecs.VAR_INT.decode(buf);
            int whiteArea = ByteBufCodecs.VAR_INT.decode(buf);
            int dame = ByteBufCodecs.VAR_INT.decode(buf);
            Stone winner = STONE.decode(buf);
            double margin = buf.readDouble();
            return new Scoring.Result(blackArea, whiteArea, dame, winner, margin);
        }

        @Override
        public void encode(ByteBuf buf, Scoring.Result r) {
            ByteBufCodecs.VAR_INT.encode(buf, r.blackArea());
            ByteBufCodecs.VAR_INT.encode(buf, r.whiteArea());
            ByteBufCodecs.VAR_INT.encode(buf, r.dame());
            STONE.encode(buf, r.winner());
            buf.writeDouble(r.margin());
        }
    };

    /** 终局结果：胜方 + 原因 + 可选数子明细（认输/掉线判负无明细，用一个 bool 标记）。 */
    public static final StreamCodec<ByteBuf, GameResult> GAME_RESULT = new StreamCodec<>() {
        @Override
        public GameResult decode(ByteBuf buf) {
            Stone winner = STONE.decode(buf);
            GameResult.EndReason reason = END_REASON.decode(buf);
            Scoring.Result score = ByteBufCodecs.BOOL.decode(buf) ? SCORING_RESULT.decode(buf) : null;
            return new GameResult(winner, reason, score);
        }

        @Override
        public void encode(ByteBuf buf, GameResult r) {
            STONE.encode(buf, r.winner());
            END_REASON.encode(buf, r.reason());
            boolean hasScore = r.hasScore();
            ByteBufCodecs.BOOL.encode(buf, hasScore);
            if (hasScore) SCORING_RESULT.encode(buf, r.score());
        }
    };
}
