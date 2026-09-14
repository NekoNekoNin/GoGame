package com.november.gogame;

import com.mojang.logging.LogUtils;
import com.november.gogame.common.game.GameServerManager;
import com.november.gogame.common.net.GoNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * 围棋 App 附属模组主类（双端）。
 *
 * 与 mcphone-deepseek（纯客户端）不同：围棋要做「服务端仲裁的双人对弈」，
 * 所以本模组是双端的——主类不加 dist 限制，客户端专用逻辑另放 {@link GoGameClient}。
 *
 * 客户端：{@code GoApp} 通过 mcphone 的 IPhoneApp SPI 被发现
 * （META-INF/services 里登记一行），主类不注册任何 App。
 * 服务端：Phase 2 的对局管理器与自建网络包挂在本类的生命周期上，做 PVP 权威仲裁。
 */
@Mod(GoGameMod.MODID)
public final class GoGameMod {

    public static final String MODID = "gogame";

    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 运行时真实版本号，App 详情页显示用。不在代码里写死字面量——写死的那个
     * 迟早和 gradle.properties 对不上，而且没人会发现。
     */
    private static String version = "0.0.0";

    public GoGameMod(IEventBus modEventBus, ModContainer container) {
        version = container.getModInfo().getVersion().toString();
        LOGGER.info("[GoGame] v{} 已加载，围棋 App 由 mcphone 的 IPhoneApp SPI 发现", version);

        // 模组总线：注册自建 payload（RegisterPayloadHandlersEvent 属 mod 生命周期事件）
        modEventBus.addListener(GoNetwork::register);

        // 游戏总线：服务端起停 + 每 tick + 玩家登入登出，全部喂给对局管理器做 PVP 权威仲裁
        NeoForge.EVENT_BUS.addListener(GoGameMod::onServerStarting);
        NeoForge.EVENT_BUS.addListener(GoGameMod::onServerStopping);
        NeoForge.EVENT_BUS.addListener(GoGameMod::onServerTick);
        NeoForge.EVENT_BUS.addListener(GoGameMod::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(GoGameMod::onPlayerLoggedOut);
    }

    // ---- 服务端生命周期（游戏总线 NeoForge.EVENT_BUS）----

    private static void onServerStarting(ServerStartingEvent event) {
        GameServerManager.onServerStarting(event.getServer());
        LOGGER.info("[GoGame] 服务端启动，对局管理器就位");
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        GameServerManager.onServerStopping();
    }

    /** 每 tick 检查掉线宽限是否到点（超时判负）+ 回收无人挂着的终局房间 */
    private static void onServerTick(ServerTickEvent.Post event) {
        GameServerManager.serverTick();
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // 服务端事件的 entity 就是 ServerPlayer；instanceof 顺带挡下任何非预期子类
        if (event.getEntity() instanceof ServerPlayer player) {
            GameServerManager.playerLoggedIn(player);
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        GameServerManager.playerLoggedOut(event.getEntity().getUUID());
    }

    /** 本模组版本号。模组构造之前调用会得到占位值 "0.0.0" */
    public static String version() {
        return version;
    }
}
