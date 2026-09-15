package com.november.gogame.common.net;

import com.november.gogame.common.game.GameResult;

import java.util.List;

/**
 * 客户端本地的对局数据缓存：界面每帧从这里读，真值全在服务端，这只是渲染快照。
 *
 * <p><b>为什么放在 {@code common/net} 且一个客户端类型都不许出现</b>：同包的 {@code GoNetwork}
 * 在专用服务器上也会加载（{@code RegisterPayloadHandlersEvent} 两端都触发），S2C 处理函数体
 * 只是「把包写进这里」。这里多一句 {@code import net.minecraft.client.*}，专用服务器在校验这个类
 * 的那一刻就崩——不需要执行到那一行。判据只有一条：本类只是几个静态字段的持有者，
 * 碰 Minecraft / Screen / 渲染的代码必须住在路径带 {@code /client/} 的类里（Phase 3 的界面）。
 * 与本体 {@code ChatClientCache} 同一模式。
 *
 * <p><b>线程</b>：S2C 处理函数经 {@code ctx.enqueueWork} 落到客户端主线程后才写这里，界面在主线程
 * 渲染时读——读写同线程，故不加 volatile（同 {@code ChatClientCache}）。
 */
public final class GoClientCache {

    private GoClientCache() {}

    /** 最新房间快照；{@code null} = 不在任何房间（在大厅） */
    private static GoPayloads.RoomState room;

    /** 待处理的邀请；{@code null} = 无。玩家响应或忽略后由界面 {@link #clearInvite()} */
    private static GoPayloads.InviteNotify pendingInvite;

    /** 最近一条错误翻译键；界面 {@link #consumeError()} 取走即清空，避免反复弹同一条 */
    private static String lastError;

    /** 终局结果；{@code null} = 未终局。界面展示后 {@link #clearResult()} */
    private static GameResult result;

    /** 可邀请的在线玩家列表（已排除本人） */
    private static List<GoPayloads.GoOnlinePlayer> onlinePlayers = List.of();

    // ------------------------------------------------------------------
    // 写：仅由 GoNetwork 的 S2C 处理函数调用（同包，包级可见）
    // ------------------------------------------------------------------

    static void onRoomState(GoPayloads.RoomState state) {
        room = state;
    }

    static void onGameEnded(GoPayloads.GameEnded ended) {
        result = ended.result();
    }

    static void onInvite(GoPayloads.InviteNotify invite) {
        pendingInvite = invite;
    }

    static void onOnlinePlayers(GoPayloads.OnlinePlayers players) {
        onlinePlayers = List.copyOf(players.players());
    }

    static void onGoError(GoPayloads.GoError error) {
        lastError = error.reasonKey();
    }

    // ------------------------------------------------------------------
    // 写（PVE 本地馈送）：仅由 client.ai.LocalAiGame 在客户端主线程调用
    // ------------------------------------------------------------------
    // 与上面的 S2C 通道互斥：PVE 期间没有服务器房间（联机在房中时大厅不显示 AI 入口，
    // 反之亦然），两个写入方永远不会同时活跃。同为「主线程写、主线程读」，线程契约不变。

    /** PVE：把本地推演的房间快照写进缓存（与 S2C 的 RoomState 同构，界面照常渲染） */
    public static void feedLocalRoom(GoPayloads.RoomState state) {
        room = state;
    }

    /** PVE：本地终局结果 */
    public static void feedLocalResult(GameResult r) {
        result = r;
    }

    /** PVE：本地错误键（玩家非法着 / AI 故障提示）。键一律无参数——toast 通道只传键 */
    public static void localError(String reasonKey) {
        lastError = reasonKey;
    }

    // ------------------------------------------------------------------
    // 读：客户端界面（Phase 3 的大厅页 / 全屏棋盘）
    // ------------------------------------------------------------------

    public static GoPayloads.RoomState getRoom() { return room; }

    public static boolean inRoom() { return room != null; }

    public static GoPayloads.InviteNotify getPendingInvite() { return pendingInvite; }

    public static GameResult getResult() { return result; }

    public static List<GoPayloads.GoOnlinePlayer> getOnlinePlayers() { return onlinePlayers; }

    /** 取走并清空最近错误键；无错误返回 {@code null} */
    public static String consumeError() {
        String e = lastError;
        lastError = null;
        return e;
    }

    // ------------------------------------------------------------------
    // 状态清理
    // ------------------------------------------------------------------

    public static void clearInvite() { pendingInvite = null; }

    public static void clearResult() { result = null; }

    /**
     * 退出世界 / 换服务器时清空，免得闪出上一局的数据。
     * 由客户端在断开连接时调用（Phase 3 挂 {@code ClientPlayerNetworkEvent.LoggingOut}）。
     */
    public static void clear() {
        room = null;
        pendingInvite = null;
        lastError = null;
        result = null;
        onlinePlayers = List.of();
    }
}
