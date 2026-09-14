package com.november.gogame.common.net;

import com.november.gogame.common.game.GameServerManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 围棋网络层的门口——注册所有包、收发所有包，都从这里走。
 *
 * <h2>处理函数不拿 IPayloadContext</h2>
 * 与本体 {@code MCphoneNetwork} 同一套收编：{@code ctx.enqueueWork(...)} 包一层、
 * {@code ctx.player() instanceof ServerPlayer} 取一次玩家，这两件与「收到之后做什么」无关的
 * 样板抽进 {@link #toServer}/{@link #toClient}。于是 <b>C2S 处理函数拿到的是已确认非空、
 * 已在主线程上的 {@link ServerPlayer}；S2C 处理函数只拿到包本身</b>。
 *
 * <h2>dist 隔离</h2>
 * {@code register} 两端都会跑（{@code RegisterPayloadHandlersEvent} 双端触发），所以本类与它
 * 引用到的一切都必须在专用服务器上可加载：
 * <ul>
 *   <li>C2S 处理函数委托 {@link GameServerManager}——它只碰 {@code ServerPlayer}/{@code MinecraftServer}
 *       这些<b>服务端但两端 jar 都在</b>的类，不是 {@code net.minecraft.client.*}。</li>
 *   <li>S2C 处理函数只写 {@link GoClientCache}——纯数据持有者，零客户端 import。</li>
 * </ul>
 * 本类全程不出现任何 {@code net.minecraft.client.*}，故专用服务器加载即安全。真正碰渲染的代码
 * 住在路径带 {@code /client/} 的类里（Phase 3 的界面），由 {@link GoClientCache} 中转数据。
 *
 * <h2>两个发包方法方向不对称，这是硬的</h2>
 * {@link #sendToServer} 只能客户端调、{@link #sendToPlayer} 只能服务端调；
 * {@code PacketDistributor.sendToServer} 第一句就 {@code checkState(dist.isClient())}，方向搞反会抛。
 * 全仓只有本文件该出现 {@code PacketDistributor} 这个名字。
 */
public final class GoNetwork {

    private GoNetwork() {}

    /** 由 {@code GoGameMod} 构造函数 {@code modEventBus.addListener(GoNetwork::register)} 挂载 */
    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        // ---- C2S：客户端 → 服务端，委托给服务端对局管理器 ----
        toServer(registrar, GoPayloads.CreateRoom.TYPE, GoPayloads.CreateRoom.STREAM_CODEC,
                (pkt, p) -> withManager(m -> m.onCreateRoom(p, pkt)));
        toServer(registrar, GoPayloads.JoinRoom.TYPE, GoPayloads.JoinRoom.STREAM_CODEC,
                (pkt, p) -> withManager(m -> m.onJoinRoom(p, pkt)));
        toServer(registrar, GoPayloads.RequestOnlinePlayers.TYPE, GoPayloads.RequestOnlinePlayers.STREAM_CODEC,
                (pkt, p) -> withManager(m -> m.onRequestOnlinePlayers(p, pkt)));
        toServer(registrar, GoPayloads.InvitePlayer.TYPE, GoPayloads.InvitePlayer.STREAM_CODEC,
                (pkt, p) -> withManager(m -> m.onInvitePlayer(p, pkt)));
        toServer(registrar, GoPayloads.RespondInvite.TYPE, GoPayloads.RespondInvite.STREAM_CODEC,
                (pkt, p) -> withManager(m -> m.onRespondInvite(p, pkt)));
        toServer(registrar, GoPayloads.PlayMove.TYPE, GoPayloads.PlayMove.STREAM_CODEC,
                (pkt, p) -> withManager(m -> m.onPlayMove(p, pkt)));
        toServer(registrar, GoPayloads.LeaveRoom.TYPE, GoPayloads.LeaveRoom.STREAM_CODEC,
                (pkt, p) -> withManager(m -> m.onLeaveRoom(p)));

        // ---- S2C：服务端 → 客户端，只写纯数据缓存，界面每帧从那里读 ----
        toClient(registrar, GoPayloads.RoomState.TYPE, GoPayloads.RoomState.STREAM_CODEC,
                GoClientCache::onRoomState);
        toClient(registrar, GoPayloads.GameEnded.TYPE, GoPayloads.GameEnded.STREAM_CODEC,
                GoClientCache::onGameEnded);
        toClient(registrar, GoPayloads.InviteNotify.TYPE, GoPayloads.InviteNotify.STREAM_CODEC,
                GoClientCache::onInvite);
        toClient(registrar, GoPayloads.OnlinePlayers.TYPE, GoPayloads.OnlinePlayers.STREAM_CODEC,
                GoClientCache::onOnlinePlayers);
        toClient(registrar, GoPayloads.GoError.TYPE, GoPayloads.GoError.STREAM_CODEC,
                GoClientCache::onGoError);
    }

    // ------------------------------------------------------------------
    // 注册样板收编
    // ------------------------------------------------------------------

    /**
     * 注册一个 C2S 包。处理函数拿到的 {@link ServerPlayer} 已非空、已在主线程。
     * 玩家为空只可能是「包排队期间连接断了」，方向已由 playToServer 限死，静默丢弃即可。
     */
    private static <T extends CustomPacketPayload> void toServer(
            PayloadRegistrar registrar,
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler) {

        registrar.playToServer(type, codec, (packet, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                handler.accept(packet, player);
            }
        }));
    }

    /** 注册一个 S2C 包。处理函数只拿到包本身；<b>函数体与它直接调到的东西都不能碰客户端类型</b>。 */
    private static <T extends CustomPacketPayload> void toClient(
            PayloadRegistrar registrar,
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Consumer<T> handler) {

        registrar.playToClient(type, codec, (packet, ctx) -> ctx.enqueueWork(() -> handler.accept(packet)));
    }

    /** 服务端管理器可能尚未就位（无服务端的渲染阶段）；判空后再动，绝不 NPE */
    private static void withManager(Consumer<GameServerManager> action) {
        GameServerManager m = GameServerManager.get();
        if (m != null) action.accept(m);
    }

    // ------------------------------------------------------------------
    // 收发门面
    // ------------------------------------------------------------------

    /** 客户端调用：把包发给服务端 */
    public static void sendToServer(CustomPacketPayload packet) {
        PacketDistributor.sendToServer(packet);
    }

    /** 服务端调用：把包发给某一个玩家 */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }
}
