package com.november.gogame.common.net;

import com.november.gogame.GoGameMod;
import com.november.gogame.common.game.GameResult;
import com.november.gogame.common.game.GameRoom;
import com.november.gogame.common.rules.Board;
import com.november.gogame.common.rules.Move;
import com.november.gogame.common.rules.Stone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * 围棋的全部网络包（C2S + S2C）集中在这一份「协议表」里。
 *
 * <p>每个包是一个嵌套 record，各自带 {@code TYPE}（注册与分发用）与 {@code STREAM_CODEC}
 * （线格式）。集中而非一文件一包：整条协议一眼可览，改字段时编解码就在同一屏，不易漏改。
 *
 * <p><b>防作弊铁律</b>：{@link PlayMove} <b>不带颜色</b>——客户端只能说「在 (x,y) 落子 /
 * 虚着 / 认输」，下什么色由服务端按 {@link GameRoom#turn()} 定（见 {@code GameRoom.applyMove}）。
 *
 * <p>编解码复用 {@link GoCodecs}；单字段包用 {@link StreamCodec#map}、2–3 字段用
 * {@link StreamCodec#composite}、空包用 {@link StreamCodec#unit}、含可空 guest 的
 * {@link RoomState} 用自定义 codec——全部对齐本体 mcphone 已编译通过的写法。
 */
public final class GoPayloads {

    private GoPayloads() {}

    /** 房间号定长 6 位数字 */
    static final int ROOM_ID_LEN = 6;
    /** 玩家名传输上限（MC 用户名 ≤16，留余量到 32） */
    static final int NAME_LEN = 32;
    /** 在线邀请列表上限，与本体 SyncOnlinePlayersPacket 同量级；管理器（common/game）截断列表时要用，故 public */
    public static final int MAX_ONLINE = 200;
    /** 错误键长度上限（翻译键都很短） */
    private static final int KEY_LEN = 64;

    // 复用的小 codec，声明在最前，供下面各嵌套 record 的静态初始化引用
    private static final StreamCodec<ByteBuf, String> ROOM_ID_CODEC = ByteBufCodecs.stringUtf8(ROOM_ID_LEN);
    private static final StreamCodec<ByteBuf, String> NAME_CODEC = ByteBufCodecs.stringUtf8(NAME_LEN);
    private static final StreamCodec<ByteBuf, String> KEY_CODEC = ByteBufCodecs.stringUtf8(KEY_LEN);

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(GoGameMod.MODID, path);
    }

    // ==================================================================
    // C2S：客户端 → 服务端
    // ==================================================================

    /**
     * 建房。执色方式由房主在创建时二选一（用户决策）：
     * {@link GameRoom.ColorMode#HOST_PICKS} 时 {@code hostColor} 是房主自选的黑/白；
     * {@link GameRoom.ColorMode#RANDOM} 时 {@code hostColor} 传 EMPTY，人齐再随机。
     */
    public record CreateRoom(GameRoom.ColorMode colorMode, Stone hostColor) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CreateRoom> TYPE = new CustomPacketPayload.Type<>(id("create_room"));
        public static final StreamCodec<ByteBuf, CreateRoom> STREAM_CODEC = StreamCodec.composite(
                GoCodecs.COLOR_MODE, CreateRoom::colorMode,
                GoCodecs.STONE, CreateRoom::hostColor,
                CreateRoom::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 凭 6 位房间号加入。 */
    public record JoinRoom(String roomId) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<JoinRoom> TYPE = new CustomPacketPayload.Type<>(id("join_room"));
        public static final StreamCodec<ByteBuf, JoinRoom> STREAM_CODEC =
                ROOM_ID_CODEC.map(JoinRoom::new, JoinRoom::roomId);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 打开「邀请在线玩家」时请求在线列表，无字段；本人由服务端从连接上下文取。 */
    public record RequestOnlinePlayers() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RequestOnlinePlayers> TYPE = new CustomPacketPayload.Type<>(id("request_online_players"));
        public static final StreamCodec<ByteBuf, RequestOnlinePlayers> STREAM_CODEC =
                StreamCodec.unit(new RequestOnlinePlayers());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 房主邀请某在线玩家来自己的房间。 */
    public record InvitePlayer(UUID target) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<InvitePlayer> TYPE = new CustomPacketPayload.Type<>(id("invite_player"));
        public static final StreamCodec<ByteBuf, InvitePlayer> STREAM_CODEC =
                UUIDUtil.STREAM_CODEC.map(InvitePlayer::new, InvitePlayer::target);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 被邀请者回应：接受则加入 hostId 的房间。 */
    public record RespondInvite(UUID hostId, boolean accept) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RespondInvite> TYPE = new CustomPacketPayload.Type<>(id("respond_invite"));
        public static final StreamCodec<ByteBuf, RespondInvite> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, RespondInvite::hostId,
                ByteBufCodecs.BOOL, RespondInvite::accept,
                RespondInvite::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * 落子 / 虚着 / 认输，三包合一。<b>不带颜色</b>（防作弊，见类注释）。
     * PLACE 用 (x,y)；PASS/RESIGN 时 (x,y) 传 -1，服务端忽略。
     */
    public record PlayMove(int x, int y, Move.Kind kind) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PlayMove> TYPE = new CustomPacketPayload.Type<>(id("play_move"));
        public static final StreamCodec<ByteBuf, PlayMove> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, PlayMove::x,
                ByteBufCodecs.VAR_INT, PlayMove::y,
                GoCodecs.MOVE_KIND, PlayMove::kind,
                PlayMove::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 主动离开房间（房主离开即解散；对局中离开视同认输由管理器裁定）。无字段。 */
    public record LeaveRoom() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<LeaveRoom> TYPE = new CustomPacketPayload.Type<>(id("leave_room"));
        public static final StreamCodec<ByteBuf, LeaveRoom> STREAM_CODEC =
                StreamCodec.unit(new LeaveRoom());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ==================================================================
    // S2C：服务端 → 客户端
    // ==================================================================

    /**
     * 一名玩家在对局里的可见信息（下发用，非服务端 Seat 本身）。
     * {@link RoomState} 的房主必带，客人在 WAITING 阶段为 null。
     */
    public record PlayerView(UUID id, String name, Stone color, boolean connected) {
        public static final StreamCodec<ByteBuf, PlayerView> STREAM_CODEC = new StreamCodec<>() {
            @Override public PlayerView decode(ByteBuf buf) {
                UUID id = UUIDUtil.STREAM_CODEC.decode(buf);
                String name = NAME_CODEC.decode(buf);
                Stone color = GoCodecs.STONE.decode(buf);
                boolean connected = ByteBufCodecs.BOOL.decode(buf);
                return new PlayerView(id, name, color, connected);
            }
            @Override public void encode(ByteBuf buf, PlayerView v) {
                UUIDUtil.STREAM_CODEC.encode(buf, v.id());
                NAME_CODEC.encode(buf, v.name());
                GoCodecs.STONE.encode(buf, v.color());
                ByteBufCodecs.BOOL.encode(buf, v.connected());
            }
        };
    }

    /**
     * 房间全量快照：加入/重连/每手棋后下发，客户端据此整份刷新（不做增量，省得漏同步）。
     * WAITING 阶段 {@code guest} 为 {@code null}；PLAYING/FINISHED 时两名都在。
     */
    public record RoomState(String roomId, GameRoom.Phase phase, Board board, Stone turn, int koIndex,
                            int passCount, int capturedByBlack, int capturedByWhite, int moveNumber,
                            GameRoom.ColorMode colorMode, PlayerView host, PlayerView guest)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<RoomState> TYPE = new CustomPacketPayload.Type<>(id("room_state"));

        public static final StreamCodec<ByteBuf, RoomState> STREAM_CODEC = new StreamCodec<>() {
            @Override public RoomState decode(ByteBuf buf) {
                String roomId = ROOM_ID_CODEC.decode(buf);
                GameRoom.Phase phase = GoCodecs.PHASE.decode(buf);
                Board board = GoCodecs.BOARD.decode(buf);
                Stone turn = GoCodecs.STONE.decode(buf);
                int koIndex = ByteBufCodecs.VAR_INT.decode(buf);
                int passCount = ByteBufCodecs.VAR_INT.decode(buf);
                int capturedByBlack = ByteBufCodecs.VAR_INT.decode(buf);
                int capturedByWhite = ByteBufCodecs.VAR_INT.decode(buf);
                int moveNumber = ByteBufCodecs.VAR_INT.decode(buf);
                GameRoom.ColorMode colorMode = GoCodecs.COLOR_MODE.decode(buf);
                PlayerView host = PlayerView.STREAM_CODEC.decode(buf);
                PlayerView guest = ByteBufCodecs.BOOL.decode(buf) ? PlayerView.STREAM_CODEC.decode(buf) : null;
                return new RoomState(roomId, phase, board, turn, koIndex, passCount,
                        capturedByBlack, capturedByWhite, moveNumber, colorMode, host, guest);
            }

            @Override public void encode(ByteBuf buf, RoomState s) {
                ROOM_ID_CODEC.encode(buf, s.roomId());
                GoCodecs.PHASE.encode(buf, s.phase());
                GoCodecs.BOARD.encode(buf, s.board());
                GoCodecs.STONE.encode(buf, s.turn());
                ByteBufCodecs.VAR_INT.encode(buf, s.koIndex());
                ByteBufCodecs.VAR_INT.encode(buf, s.passCount());
                ByteBufCodecs.VAR_INT.encode(buf, s.capturedByBlack());
                ByteBufCodecs.VAR_INT.encode(buf, s.capturedByWhite());
                ByteBufCodecs.VAR_INT.encode(buf, s.moveNumber());
                GoCodecs.COLOR_MODE.encode(buf, s.colorMode());
                PlayerView.STREAM_CODEC.encode(buf, s.host());
                boolean hasGuest = s.guest() != null;
                ByteBufCodecs.BOOL.encode(buf, hasGuest);
                if (hasGuest) PlayerView.STREAM_CODEC.encode(buf, s.guest());
            }
        };

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 终局：胜方 + 原因（+ 数子明细，若走满双方虚着）。带 roomId 供客户端核对是不是自己那局。 */
    public record GameEnded(String roomId, GameResult result) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GameEnded> TYPE = new CustomPacketPayload.Type<>(id("game_ended"));
        public static final StreamCodec<ByteBuf, GameEnded> STREAM_CODEC = StreamCodec.composite(
                ROOM_ID_CODEC, GameEnded::roomId,
                GoCodecs.GAME_RESULT, GameEnded::result,
                GameEnded::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 你被邀请：谁（hostId/hostName）邀你进哪个房间（roomId）。 */
    public record InviteNotify(UUID hostId, String hostName, String roomId) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<InviteNotify> TYPE = new CustomPacketPayload.Type<>(id("invite_notify"));
        public static final StreamCodec<ByteBuf, InviteNotify> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, InviteNotify::hostId,
                NAME_CODEC, InviteNotify::hostName,
                ROOM_ID_CODEC, InviteNotify::roomId,
                InviteNotify::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 在线玩家一行（邀请列表用）；已排除本人，由服务端过滤。 */
    public record GoOnlinePlayer(UUID id, String name) {
        public static final StreamCodec<ByteBuf, GoOnlinePlayer> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, GoOnlinePlayer::id,
                NAME_CODEC, GoOnlinePlayer::name,
                GoOnlinePlayer::new);
    }

    /** 可邀请的在线玩家列表（已排除本人，截断到 {@link #MAX_ONLINE}）。 */
    public record OnlinePlayers(List<GoOnlinePlayer> players) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<OnlinePlayers> TYPE = new CustomPacketPayload.Type<>(id("online_players"));
        public static final StreamCodec<ByteBuf, OnlinePlayers> STREAM_CODEC =
                GoOnlinePlayer.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ONLINE))
                        .map(OnlinePlayers::new, OnlinePlayers::players);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * 一句错误提示的翻译键（建房失败、非你回合、对局挂起……）。
     * 只传键不传译文：I18n 只在客户端渲染线程做（见 docs/PITFALLS.md E5）。
     */
    public record GoError(String reasonKey) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GoError> TYPE = new CustomPacketPayload.Type<>(id("go_error"));
        public static final StreamCodec<ByteBuf, GoError> STREAM_CODEC =
                KEY_CODEC.map(GoError::new, GoError::reasonKey);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
