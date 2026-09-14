package com.november.gogame.common.game;

import com.november.gogame.common.rules.Board;
import com.november.gogame.common.rules.GoRules;
import com.november.gogame.common.rules.Move;
import com.november.gogame.common.rules.Scoring;
import com.november.gogame.common.rules.Stone;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/**
 * 一间对局房间的权威状态机（服务端独占，单线程访问，故无同步）。
 *
 * <p>持有整局的全部真值：房间号、两名座位（颜色 + 连接状态）、当前盘面、轮次、劫争点、
 * 连续虚着数、双方提子数、终局结果。所有落子经 {@link #applyMove} 走一遍 {@link GoRules}
 * 仲裁——<b>颜色由 {@code turn} 决定，忽略客户端传来的颜色</b>（防作弊：客户端只能说
 * 「在 (x,y) 落子 / 虚着 / 认输」，是谁的、下什么色由服务端说了算）。
 *
 * <p>纯 Java、无 Minecraft 依赖（UUID / Random 都属 java.util）——可脱离游戏单测。
 * 玩家名等需要 {@code ServerPlayer} 的信息不在此，由 {@code GameServerManager} 下发时补齐。
 *
 * <h2>生命周期</h2>
 * <ol>
 *   <li>{@link Phase#WAITING}：房主已建房，等第二名玩家。{@link #join} 确定双方颜色后转 PLAYING。</li>
 *   <li>{@link Phase#PLAYING}：轮流落子。任一方掉线 → 挂起（{@link #disconnect}），
 *       宽限 {@link #RECONNECT_GRACE_MS} 内 {@link #reconnect} 恢复；超时由 {@link #tick} 判负。</li>
 *   <li>{@link Phase#FINISHED}：数子 / 认输 / 掉线判负，{@link #result()} 非空。</li>
 * </ol>
 */
public final class GameRoom {

    /** 掉线重连宽限期（毫秒）。超时未归判负。 */
    public static final long RECONNECT_GRACE_MS = 60_000L;

    // ---- 拒绝原因翻译键（与 GoRules 的键同族，界面统一按 key 翻译；规则层不碰 I18n）----
    public static final String ERR_NOT_STARTED   = "gogame.error.not_started";
    public static final String ERR_FINISHED      = "gogame.error.finished";
    public static final String ERR_ROOM_FULL     = "gogame.error.room_full";
    public static final String ERR_SELF_JOIN     = "gogame.error.self_join";
    public static final String ERR_NOT_IN_ROOM   = "gogame.error.not_in_room";
    public static final String ERR_NOT_YOUR_TURN = "gogame.error.not_your_turn";
    public static final String ERR_SUSPENDED     = "gogame.error.suspended";
    public static final String ERR_OUT_OF_BOUNDS = "gogame.error.out_of_bounds";

    /** 对局阶段 */
    public enum Phase { WAITING, PLAYING, FINISHED }

    /** 执色分配方式（用户决策：创建房间时二选一） */
    public enum ColorMode {
        /** 房主自选执黑或执白，客人得另一色 */
        HOST_PICKS,
        /** 凑齐两人时随机分配 */
        RANDOM
    }

    /**
     * 一个座位：一名玩家 + 其颜色 + 连接状态。
     *
     * {@code color} 在 WAITING 阶段可能仍是 {@link Stone#EMPTY}（RANDOM 模式要等人齐才定）；
     * {@code disconnectDeadlineMs} 为 0 表示未掉线，否则是判负的绝对时刻（毫秒）。
     */
    public static final class Seat {
        public final UUID playerId;
        // 以下三个可变字段收敛为包级私有：只在 common/game 内被 GameRoom 自身与 GameServerManager 读写，
        // 对外一律经 GameRoom 的只读访问器暴露，杜绝任意包直接改座位状态（#10）。
        Stone color;
        boolean connected;
        long disconnectDeadlineMs;

        Seat(UUID playerId, Stone color, boolean connected) {
            this.playerId = playerId;
            this.color = color;
            this.connected = connected;
            this.disconnectDeadlineMs = 0L;
        }
    }

    private final String roomId;
    private final UUID hostId;
    private final ColorMode colorMode;
    private final Seat hostSeat;
    private Seat guestSeat;   // null 直到有人加入

    private Phase phase;
    private Board board;
    private Stone turn;
    private int koIndex;
    private int passCount;
    private int capturedByBlack;
    private int capturedByWhite;
    private GameResult result;
    private final List<Move> history = new ArrayList<>();

    /**
     * 房主建房。
     *
     * @param roomId     6 位房间号（由管理器生成）
     * @param hostId     房主 UUID
     * @param colorMode  执色分配方式
     * @param hostColor  仅 {@link ColorMode#HOST_PICKS} 有意义：房主自选的颜色（BLACK/WHITE）；
     *                   其它情况（含 RANDOM 或传了 EMPTY）一律忽略，等人齐再定
     */
    public GameRoom(String roomId, UUID hostId, ColorMode colorMode, Stone hostColor) {
        this.roomId = Objects.requireNonNull(roomId, "roomId");
        this.hostId = Objects.requireNonNull(hostId, "hostId");
        this.colorMode = Objects.requireNonNull(colorMode, "colorMode");
        Stone initial = (colorMode == ColorMode.HOST_PICKS && hostColor != null && hostColor.isStone())
                ? hostColor : Stone.EMPTY;
        this.hostSeat = new Seat(hostId, initial, true);
        this.phase = Phase.WAITING;
        this.board = new Board();
        this.turn = Stone.BLACK;   // 黑先
        this.koIndex = GoRules.NO_KO;
    }

    // ------------------------------------------------------------------
    // 加入 / 离开
    // ------------------------------------------------------------------

    /**
     * 第二名玩家加入：确定双方颜色，转入 {@link Phase#PLAYING}。
     *
     * @return {@code null} 表示成功；否则是拒绝原因翻译键（已开始 / 已满 / 自己加自己）
     */
    public String join(UUID guestId, Random random) {
        if (phase == Phase.FINISHED) return ERR_FINISHED;                     // 已终局：如实告知
        if (phase != Phase.WAITING || guestSeat != null) return ERR_ROOM_FULL; // 对局中/已有客人：是"满员"不是"结束"（#2）
        if (hostId.equals(guestId)) return ERR_SELF_JOIN;

        Stone hostColor;
        if (colorMode == ColorMode.HOST_PICKS && hostSeat.color.isStone()) {
            hostColor = hostSeat.color;
        } else {
            hostColor = random.nextBoolean() ? Stone.BLACK : Stone.WHITE;
        }
        hostSeat.color = hostColor;
        guestSeat = new Seat(guestId, hostColor.opponent(), true);
        phase = Phase.PLAYING;
        turn = Stone.BLACK;
        return null;
    }

    // ------------------------------------------------------------------
    // 落子仲裁（服务端权威）
    // ------------------------------------------------------------------

    /**
     * 把 {@code actor} 的一手棋作用于本房间。<b>颜色取自 {@link #turn}</b>，忽略任何客户端传色。
     *
     * @param actor 发起者 UUID（须是本房间当前轮次的座位、且双方都在线）
     * @param x     落点列（仅 PLACE 用）
     * @param y     落点行（仅 PLACE 用）
     * @param kind  落子 / 虚着 / 认输
     * @return {@code null} 表示合法且状态已推进；否则是拒绝原因翻译键（不改任何状态）
     */
    public String applyMove(UUID actor, int x, int y, Move.Kind kind) {
        if (phase == Phase.WAITING) return ERR_NOT_STARTED;
        if (phase == Phase.FINISHED) return ERR_FINISHED;
        Objects.requireNonNull(kind, "kind");

        Seat seat = seatOf(actor);
        if (seat == null) return ERR_NOT_IN_ROOM;
        if (!seat.connected) return ERR_SUSPENDED;   // 自己在宽限期，理论上发不出包，保险拦
        if (anyDisconnected()) return ERR_SUSPENDED; // 对手掉线，对局挂起，谁都不能落子
        if (seat.color != turn) return ERR_NOT_YOUR_TURN;

        // 越界坐标先拦：Move 紧凑构造器会抛 IllegalArgumentException，绝不能在服务端主线程抛
        if (kind == Move.Kind.PLACE && !Board.inBounds(x, y)) return ERR_OUT_OF_BOUNDS;

        Move move = switch (kind) {
            case PLACE  -> Move.place(turn, x, y);
            case PASS   -> Move.pass(turn);
            case RESIGN -> Move.resign(turn);
        };

        GoRules.Outcome outcome = GoRules.play(board, move, koIndex);
        if (!outcome.legal()) return outcome.reasonKey();

        // 合法：按 Outcome 契约把返回盘直接重新赋值为新状态（PASS 返回原引用，自赋无害）
        board = outcome.board();
        koIndex = outcome.koIndex();
        history.add(move);

        if (move.isResign()) {
            finish(GameResult.byResign(turn.opponent()));
            return null;
        }
        if (outcome.captured() > 0) {
            if (turn == Stone.BLACK) capturedByBlack += outcome.captured();
            else capturedByWhite += outcome.captured();
        }
        if (move.isPass()) {
            passCount++;
            if (passCount >= 2) {   // 双方连续虚着 → 数子终局
                finish(GameResult.byScore(Scoring.score(board)));
                return null;
            }
        } else {
            passCount = 0;          // 落子打断连续虚着
        }
        turn = turn.opponent();
        return null;
    }

    private void finish(GameResult r) {
        this.result = r;
        this.phase = Phase.FINISHED;
    }

    /**
     * 对局中一方<b>主动离场</b> = 认输（对手判胜）。与 {@link #applyMove} 里的 RESIGN 同果，
     * 但走的是「玩家点了离开房间」而非「在棋盘上认输」这条路，由管理器在收到 LeaveRoom 时调。
     *
     * @return 终局结果；若当前不是 {@link Phase#PLAYING} 或该玩家不在房间，返回 {@code null}（不改状态）
     */
    public GameResult resignBy(UUID playerId) {
        if (phase != Phase.PLAYING) return null;
        Seat seat = seatOf(playerId);
        if (seat == null) return null;
        GameResult r = GameResult.byResign(seat.color.opponent());
        finish(r);
        return r;
    }

    // ------------------------------------------------------------------
    // 掉线 / 重连 / 超时判负
    // ------------------------------------------------------------------

    /** 玩家掉线：挂起对局并开始宽限计时。仅 {@link Phase#PLAYING} 有意义。 */
    public void disconnect(UUID playerId, long nowMs) {
        Seat seat = seatOf(playerId);
        if (seat == null || phase != Phase.PLAYING || !seat.connected) return;
        seat.connected = false;
        seat.disconnectDeadlineMs = nowMs + RECONNECT_GRACE_MS;
    }

    /**
     * 玩家重连：<b>宽限期内</b>回来则恢复连接；已过 {@link #RECONNECT_GRACE_MS} 则拒绝，交给 {@link #tick} 判负。
     *
     * @return {@code true} 表示本房间确因该玩家重连而从挂起中恢复（管理器据此通知对手）
     */
    public boolean reconnect(UUID playerId, long nowMs) {
        Seat seat = seatOf(playerId);
        if (seat == null || phase != Phase.PLAYING || seat.connected) return false;
        // 宽限期已过（tick 还没来得及判负、或服务器卡顿导致登录事件晚于截止时刻）：不许恢复（#6 硬校验 nowMs）
        if (seat.disconnectDeadlineMs > 0 && nowMs >= seat.disconnectDeadlineMs) return false;
        seat.connected = true;
        seat.disconnectDeadlineMs = 0L;
        return true;
    }

    /**
     * 每 tick 检查：宽限期到点仍没回来 → 判负，对局转 FINISHED。
     *
     * @return {@code true} 表示本 tick 因此终局（管理器据此广播 {@link #result()}）
     */
    public boolean tick(long nowMs) {
        if (phase != Phase.PLAYING) return false;

        boolean hostExpired = isExpired(hostSeat, nowMs);
        boolean guestExpired = guestSeat != null && isExpired(guestSeat, nowMs);
        if (!hostExpired && !guestExpired) return false;

        // 正常至多一方掉线到期。双方同时到期的极端情形（先后登出、宽限几乎同时耗尽）：取截止时刻更早者判负；
        // 恰好相等则判房主负——规则上双方都弃赛，谁负都终结对局，取确定的一支避免"由代码顺序偶然决定胜负"（#7）。
        Seat forfeiter;
        if (hostExpired && guestExpired) {
            forfeiter = (guestSeat.disconnectDeadlineMs < hostSeat.disconnectDeadlineMs) ? guestSeat : hostSeat;
        } else if (hostExpired) {
            forfeiter = hostSeat;
        } else {
            forfeiter = guestSeat;
        }
        finish(GameResult.byForfeit(forfeiter.color.opponent()));
        return true;
    }

    /** 该座位是否已掉线且宽限期到点（{@code disconnectDeadlineMs>0} 表示确曾掉线，而非从未断过） */
    private static boolean isExpired(Seat seat, long nowMs) {
        return !seat.connected && seat.disconnectDeadlineMs > 0 && nowMs >= seat.disconnectDeadlineMs;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public String roomId() { return roomId; }
    public UUID hostId() { return hostId; }
    public UUID guestId() { return guestSeat == null ? null : guestSeat.playerId; }
    public ColorMode colorMode() { return colorMode; }
    public Phase phase() { return phase; }
    public boolean isFinished() { return phase == Phase.FINISHED; }
    public boolean isPlaying() { return phase == Phase.PLAYING; }
    public boolean isWaiting() { return phase == Phase.WAITING; }

    public Board board() { return board; }
    public Stone turn() { return turn; }
    public int koIndex() { return koIndex; }
    public int passCount() { return passCount; }
    public int capturedByBlack() { return capturedByBlack; }
    public int capturedByWhite() { return capturedByWhite; }
    public int moveNumber() { return history.size(); }
    public List<Move> history() { return List.copyOf(history); }
    public GameResult result() { return result; }

    public Seat hostSeat() { return hostSeat; }
    public Seat guestSeat() { return guestSeat; }

    /** 该玩家是否在本房间（房主或客人） */
    public boolean contains(UUID playerId) {
        return seatOf(playerId) != null;
    }

    /** 取该玩家的座位；不在本房间返回 {@code null} */
    public Seat seatOf(UUID playerId) {
        if (hostSeat.playerId.equals(playerId)) return hostSeat;
        if (guestSeat != null && guestSeat.playerId.equals(playerId)) return guestSeat;
        return null;
    }

    /** 该玩家执什么色；不在房间或颜色未定返回 {@link Stone#EMPTY} */
    public Stone colorOf(UUID playerId) {
        Seat seat = seatOf(playerId);
        return seat == null ? Stone.EMPTY : seat.color;
    }

    /** 执该色的玩家 UUID；未定或无人返回 {@code null} */
    public UUID playerOf(Stone color) {
        if (hostSeat.color == color) return hostSeat.playerId;
        if (guestSeat != null && guestSeat.color == color) return guestSeat.playerId;
        return null;
    }

    /** 是否有座位处于掉线宽限期（对局挂起） */
    public boolean anyDisconnected() {
        return !hostSeat.connected || (guestSeat != null && !guestSeat.connected);
    }
}
