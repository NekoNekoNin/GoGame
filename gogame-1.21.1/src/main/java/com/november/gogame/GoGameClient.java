package com.november.gogame;

import com.november.gogame.client.ai.LocalAiGame;
import com.november.gogame.common.net.GoClientCache;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端专用逻辑入口（dist=CLIENT，专用服务器不加载）。
 *
 * 围棋的 App / 大厅页 / 全屏棋盘 / AI 客户端都只在客户端：IPhoneApp 的签名带
 * GuiGraphics，实现类在专用服务器上加载即崩。{@code GoApp} 由 mcphone 的 SPI 在
 * 客户端构建 App 目录时才实例化，不需要在这里注册任何东西。
 *
 * 与本体 MCphoneClient 同一模式：{@code @Mod(dist=CLIENT)} 拿到 modEventBus，MOD 总线
 * 事件在构造函数里 addListener。NeoForge 21.1 起 {@code @EventBusSubscriber} 的 bus()
 * 已标记 removal，手动挂 modEventBus 才是面向未来的写法。Phase 2 的客户端网络处理、
 * Phase 3/4 的客户端 setup 会陆续加进来。
 */
@Mod(value = GoGameMod.MODID, dist = Dist.CLIENT)
public final class GoGameClient {

    public GoGameClient(IEventBus modEventBus) {
        modEventBus.addListener(GoGameClient::onClientSetup);
        // 游戏总线（非 MOD 总线）：断线/退世界时清客户端对局缓存，免得下次闪出上一局的房间/结果/邀请
        NeoForge.EVENT_BUS.addListener(GoGameClient::onLoggingOut);
        // PVE：每客户端 tick 推进 AI 回合（收 AiTurn 结果/重试/兜底），只在有对局时干活
        NeoForge.EVENT_BUS.addListener(GoGameClient::onClientTick);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        GoGameMod.LOGGER.info("[GoGame] 客户端 setup 完成");
    }

    /** {@link GoClientCache#clear()} 的既定挂钩点（见其 javadoc）：退出世界 / 换服务器即清空本地快照；PVE 对局一并终止（不跨世界保留） */
    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        LocalAiGame.leave();          // 终止 PVE 对局（leave 内部也会清缓存）
        GoClientCache.clear();        // 退出世界一律清，与是否 PVE 无关（防御性显式再清一次）
    }

    /** {@link LocalAiGame#tick()} 的驱动点：Post 阶段（一 tick 的逻辑跑完后）再收 AI 结果 */
    private static void onClientTick(ClientTickEvent.Post event) {
        LocalAiGame.tick();
    }
}
