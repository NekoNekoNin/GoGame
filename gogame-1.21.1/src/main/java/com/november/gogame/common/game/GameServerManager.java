package com.november.gogame.common.game;

import com.november.gogame.GoGameMod;
import com.november.gogame.common.net.GoNetwork;
import com.november.gogame.common.net.GoPayloads;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 服务端对局管理器：一间间 {@link GameRoom} 的登记处 + 权威仲裁的入口 + 生命周期。
 *
 * <p><b>只在服务端主线程访问</b>——C2S 处理函数经 {@code ctx.enqueueWork} 落到主线程、
 * 服务端 tick、玩家登入登出事件都在主线程，故 {@link HashMap} 足够，无需任何同步。
 *
 * <p>职责：
 * <ul>
 *   <li><b>匹配</b>：建房（生成 6 位号）、凭号加入、在线玩家邀请双通道（用户决策）。</li>
 *   <li><b>仲裁</b>：落子转交 {@link GameRoom#applyMove}，颜色由服务端定，非法着回一句 {@link GoPayloads.GoError}。</li>
 *   <li><b>终局</b>：数子 / 认输 / 掉线判负后把 {@link GoPayloads.GameEnded} 广播给双方。</li>
 *   <li><b>掉线宽限</b>（用户决策）：掉线挂起对局，{@link GameRoom#RECONNECT_GRACE_MS} 内重连恢复，超时判负。</li>
 *   <li><b>清理</b>：房间无人时移除，玩家登入登出维护映射与在线名单。</li>
 * </ul>
 *
 * <p>本类在 {@code common/game} 但会引用 {@code common/net} 的 {@link GoPayloads}/{@link GoNetwork}
 * 来收发包（game↔net 的包级循环依赖，Java 编译无碍）——纯域模型 {@link GameRoom}/{@link GameResult}
 * 仍零 Minecraft 依赖，可脱机单测；碰 {@link ServerPlayer} 的胶水都收在这里。
 */
public final class GameServerManager {

    // ---- 管理器级拒绝原因翻译键（GameRoom 的键之外的补充）----
    public static final String ERR_ALREADY_IN_ROOM = "gogame.error.already_in_room";
    public static final String ERR_NO_ROOM         = "gogame.error.no_room";
    public static final String ERR_NOT_HOST        = "gogame.error.not_host";
    public static final String ERR_TARGET_OFFLINE  = "gogame.error.target_offline";
    public static final String ERR_TARGET_BUSY     = "gogame.error.target_busy";

    /** 单例：随服务端起停。没有服务端（纯客户端渲染阶段）时为 {@code null}，各静态入口自行判空。 */
    private static GameServerManager instance;

    public static GameServerManager get() { return instance; }

    // ---- 由 GoGameMod 挂到服务端生命周期 ----
    public static void onServerStarting(MinecraftServer server) { instance = new GameServerManager(server); }
    public static void onServerStopping() { instance = null; }

    /** 服务端每 tick 由 GoGameMod 调；实例不在就什么都不做 */
    public static void serverTick() {
        GameServerManager m = instance;
        if (m != null) m.tick();
    }

    public static void playerLoggedIn(ServerPlayer player) {
        GameServerManager m = instance;
        if (m != null) m.onPlayerLoggedIn(player);
    }

    public static void playerLoggedOut(UUID id) {
        GameServerManager m = instance;
        if (m != null) m.onPlayerLoggedOut(id);
    }

    // ------------------------------------------------------------------
    // 实例状态
    // ------------------------------------------------------------------

    private final MinecraftServer server;
    private final Random random = new Random();

    /** roomId → 房间 */
    private final Map<String, GameRoom> rooms = new HashMap<>();
    /** 玩家 → 其所在 roomId（掉线宽限期间仍保留，供重连找回） */
    private final Map<UUID, String> playerRoom = new HashMap<>();
    /** 被邀请者 → 邀请他的房主；响应或过期时移除 */
    private final Map<UUID, UUID> pendingInvites = new HashMap<>();
    /** 玩家 → 最近一次见到的名字；掉线后仍能显示，不用回头查已消失的 ServerPlayer */
    private final Map<UUID, String> names = new HashMap<>();

    private GameServerManager(MinecraftServer server) {
        this.server = server;
    }

    /**
     * 当前时刻（毫秒挂钟）。掉线判负是分钟级、对时钟回拨不敏感的粗粒度超时，用挂钟足够（#11 已评估）；
     * 若日后要精确到 tick，可改用 {@code server.getTickCount()} 折算，此处暂不引入。
     */
    private long nowMs() { return System.currentTimeMillis(); }

    // ==================================================================
    // C2S 处理入口（由 GoNetwork 在收到对应包时调用）
    // ==================================================================

    public void onCreateRoom(ServerPlayer host, GoPayloads.CreateRoom pkt) {
        UUID id = host.getUUID();
        if (playerRoom.containsKey(id)) { sendError(host, ERR_ALREADY_IN_ROOM); return; }

        String roomId = generateRoomId();
        GameRoom room = new GameRoom(roomId, id, pkt.colorMode(), pkt.hostColor());
        rooms.put(roomId, room);
        playerRoom.put(id, roomId);
        names.put(id, host.getName().getString());

        GoGameMod.LOGGER.info("[GoGame] {} 建房 {}（执色方式 {}）",
                host.getName().getString(), roomId, pkt.colorMode());
        syncRoom(room);   // 目前房里只有房主，告诉他「已建好，等待对手」
    }

    public void onJoinRoom(ServerPlayer guest, GoPayloads.JoinRoom pkt) {
        join(guest, pkt.roomId());
    }

    public void onRequestOnlinePlayers(ServerPlayer player, GoPayloads.RequestOnlinePlayers pkt) {
        List<GoPayloads.GoOnlinePlayer> list = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getUUID().equals(player.getUUID())) continue;   // 排除本人
            list.add(new GoPayloads.GoOnlinePlayer(p.getUUID(), p.getName().getString()));
            if (list.size() >= GoPayloads.MAX_ONLINE) break;
        }
        GoNetwork.sendToPlayer(player, new GoPayloads.OnlinePlayers(list));
    }

    public void onInvitePlayer(ServerPlayer host, GoPayloads.InvitePlayer pkt) {
        GameRoom room = roomOf(host.getUUID());
        if (room == null || !room.hostId().equals(host.getUUID())) { sendError(host, ERR_NOT_HOST); return; }
        if (!room.isWaiting()) { sendError(host, GameRoom.ERR_ROOM_FULL); return; }

        ServerPlayer target = server.getPlayerList().getPlayer(pkt.target());
        if (target == null) { sendError(host, ERR_TARGET_OFFLINE); return; }
        if (playerRoom.containsKey(target.getUUID())) { sendError(host, ERR_TARGET_BUSY); return; }

        // 同一被邀请者若已收到别的房主邀请，后到覆盖先到（last-write-wins）；被邀请者响应旧邀请时，
        // onRespondInvite 的 hostId 校验会因不匹配而静默拒绝，不会串房（#9：明确记录这一行为）。
        pendingInvites.put(target.getUUID(), host.getUUID());
        GoNetwork.sendToPlayer(target, new GoPayloads.InviteNotify(
                host.getUUID(), host.getName().getString(), room.roomId()));
    }

    public void onRespondInvite(ServerPlayer invitee, GoPayloads.RespondInvite pkt) {
        UUID expectedHost = pendingInvites.remove(invitee.getUUID());
        // 过期或不存在的邀请（含伪造 hostId）：静默丢弃，不给伪造客户端任何反馈
        if (expectedHost == null || !expectedHost.equals(pkt.hostId())) return;
        if (!pkt.accept()) return;   // 拒绝：什么都不做

        String roomId = playerRoom.get(pkt.hostId());
        if (roomId == null) { sendError(invitee, ERR_NO_ROOM); return; }
        join(invitee, roomId);
    }

    public void onPlayMove(ServerPlayer actor, GoPayloads.PlayMove pkt) {
        GameRoom room = roomOf(actor.getUUID());
        if (room == null) { sendError(actor, GameRoom.ERR_NOT_IN_ROOM); return; }

        String err = room.applyMove(actor.getUUID(), pkt.x(), pkt.y(), pkt.kind());
        if (err != null) { sendError(actor, err); return; }

        syncRoom(room);                                 // 先把最新盘面（含终局盘面）同步给双方
        if (room.isFinished()) finishAndNotify(room);   // 数子/认输终局 → 发结果 + 拆房解绑
    }

    public void onLeaveRoom(ServerPlayer player) {
        UUID id = player.getUUID();
        GameRoom room = roomOf(id);
        if (room == null) return;

        pendingInvites.remove(id);   // 无论何种情形，离场都先撤销自己收到的邀请记录

        if (room.isPlaying()) {
            GameResult r = room.resignBy(id);   // 对局中主动离开 = 认输（对手判胜）
            if (r != null) {
                finishAndNotify(room);          // 发认输结果给在线对手 + 拆房（双方解绑，可各自开新局）
                return;                         // 只发一次结果包，杜绝 #8 的重复 syncRoom
            }
        }
        // WAITING（房主弃房）/ FINISHED（防御）/ resignBy 意外返回 null：解绑并回收
        disband(room);
    }

    /** 加入的统一实现（凭号加入与接受邀请都走这里） */
    private void join(ServerPlayer guest, String roomId) {
        UUID id = guest.getUUID();
        if (playerRoom.containsKey(id)) { sendError(guest, ERR_ALREADY_IN_ROOM); return; }

        GameRoom room = rooms.get(roomId);
        if (room == null) { sendError(guest, ERR_NO_ROOM); return; }

        String err = room.join(id, random);
        if (err != null) { sendError(guest, err); return; }

        playerRoom.put(id, roomId);
        names.put(id, guest.getName().getString());
        GoGameMod.LOGGER.info("[GoGame] {} 加入房间 {}，对局开始", guest.getName().getString(), roomId);
        syncRoom(room);   // 双方都收到「对局开始」的完整快照
    }

    // ==================================================================
    // 生命周期事件（由 GoGameMod 挂到玩家登入登出）
    // ==================================================================

    private void onPlayerLoggedIn(ServerPlayer player) {
        UUID id = player.getUUID();

        GameRoom room = roomOf(id);
        if (room == null) return;               // 不在任何房间：不必缓存名字（#4 防 names 无界增长）
        names.put(id, player.getName().getString());

        // 终局房间在结束当刻即被拆除，正常不会在此遇到；防御性处理：补发结果并拆房解绑，
        // 杜绝玩家因掉线被判负后重连而永久卡在"已在房间"、无法开新局（#1）。
        if (room.isFinished()) {
            GoNetwork.sendToPlayer(player, new GoPayloads.GameEnded(room.roomId(), room.result()));
            disband(room);
            return;
        }

        room.reconnect(id, nowMs());   // 宽限期内回来即恢复；返回值此处不必区分
        syncRoom(room);                 // 双方都看到「对手已重连 / 最新盘面」
    }

    private void onPlayerLoggedOut(UUID id) {
        pendingInvites.remove(id);
        String roomId = playerRoom.get(id);
        if (roomId == null) return;

        GameRoom room = rooms.get(roomId);
        if (room == null) { playerRoom.remove(id); return; }

        if (room.isPlaying()) {
            room.disconnect(id, nowMs());   // 挂起对局，开始宽限计时（用户决策：超时由 tick 判负）
            syncRoom(room);                 // 告诉对手「等待重连」
            return;                         // 保留映射，供宽限期内重连找回本房间
        }
        // WAITING（房主弃房，房间没意义）/ FINISHED（防御，终局本应已拆）：直接解散（含双方解绑、清名字与邀请）
        disband(room);
    }

    private void tick() {
        long now = nowMs();
        // 拷贝一份再遍历：finishAndNotify 会当场 disband（改动 rooms），避免 ConcurrentModificationException
        for (GameRoom room : List.copyOf(rooms.values())) {
            if (room.tick(now)) {          // 掉线宽限到点 → 判负终局
                finishAndNotify(room);     // 发结果给在线成员 + 拆房（双方解绑、回收）
                continue;
            }
            if (room.autoPassIfStuck()) {  // 轮次方无棋可下 → 代虚着（可能连锁到连续两虚着数子终局）
                syncRoom(room);            // 双方看到 pass 计数/轮次推进；终局时先同步终局盘面
                if (room.isFinished()) finishAndNotify(room);
            }
        }
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private String generateRoomId() {
        for (int attempt = 0; attempt < 100; attempt++) {
            String id = String.format("%06d", random.nextInt(1_000_000));
            if (!rooms.containsKey(id)) return id;
        }
        // 极端情况（几乎不可能）：线性探测兜底
        for (int n = 0; n < 1_000_000; n++) {
            String id = String.format("%06d", n);
            if (!rooms.containsKey(id)) return id;
        }
        throw new IllegalStateException("房间号空间已耗尽");
    }

    private GameRoom roomOf(UUID id) {
        String roomId = playerRoom.get(id);
        return roomId == null ? null : rooms.get(roomId);
    }

    /**
     * 终局收尾：先把 {@link GoPayloads.GameEnded} 广播给在线成员，再拆房。
     * 拆房会解绑双方映射，但结果包此时已发出，不影响在线成员看到终局；离线成员重连时
     * {@link #onPlayerLoggedIn} 走"房间已不在"分支直接回大厅，绝不会卡在"已在房间"。
     */
    private void finishAndNotify(GameRoom room) {
        broadcastEnd(room);
        disband(room);
    }

    /** 拆房：移除房间、解绑双方映射（连带忘掉名字缓存）、清掉指向该房主的遗留邀请 */
    private void disband(GameRoom room) {
        rooms.remove(room.roomId(), room);
        unmapAndForget(room.hostId(), room.roomId());
        if (room.guestId() != null) unmapAndForget(room.guestId(), room.roomId());
        removeInvitesToHost(room.hostId());
    }

    /**
     * 仅当玩家当前确实映射到这个房间时才解绑（防止误删他后来加入的新房间映射）；
     * 解绑后若他已不在任何房间，连带忘掉名字缓存——{@code names} 只保留"在局玩家"，杜绝长跑内存泄漏（#4）。
     */
    private void unmapAndForget(UUID id, String roomId) {
        if (id == null) return;
        playerRoom.remove(id, roomId);
        if (!playerRoom.containsKey(id)) names.remove(id);
    }

    /** 清掉所有"指向这个房主"的待处理邀请（#3：房主弃房/终局后，避免被邀请者响应到已不存在的房间） */
    private void removeInvitesToHost(UUID hostId) {
        pendingInvites.values().removeIf(h -> h.equals(hostId));
    }

    /** 打包房间全量快照并下发给房里所有<b>在线</b>玩家 */
    private void syncRoom(GameRoom room) {
        sendToRoom(room, snapshot(room));
    }

    private void broadcastEnd(GameRoom room) {
        GameResult r = room.result();
        if (r == null) return;
        sendToRoom(room, new GoPayloads.GameEnded(room.roomId(), r));
    }

    private void sendToRoom(GameRoom room, CustomPacketPayload pkt) {
        ServerPlayer host = server.getPlayerList().getPlayer(room.hostId());
        if (host != null) GoNetwork.sendToPlayer(host, pkt);
        UUID guestId = room.guestId();
        if (guestId != null) {
            ServerPlayer guest = server.getPlayerList().getPlayer(guestId);
            if (guest != null) GoNetwork.sendToPlayer(guest, pkt);
        }
    }

    private void sendError(ServerPlayer player, String key) {
        GoNetwork.sendToPlayer(player, new GoPayloads.GoError(key));
    }

    private GoPayloads.RoomState snapshot(GameRoom room) {
        GoPayloads.PlayerView host = viewOf(room.hostId(), room.hostSeat());
        GoPayloads.PlayerView guest = room.guestSeat() == null
                ? null : viewOf(room.guestId(), room.guestSeat());
        return new GoPayloads.RoomState(
                room.roomId(), room.phase(), room.board(), room.turn(), room.koIndex(),
                room.passCount(), room.capturedByBlack(), room.capturedByWhite(), room.moveNumber(),
                room.colorMode(), host, guest);
    }

    private GoPayloads.PlayerView viewOf(UUID id, GameRoom.Seat seat) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        String name = p != null ? p.getName().getString() : names.getOrDefault(id, "");
        return new GoPayloads.PlayerView(id, name, seat.color, seat.connected);
    }
}
