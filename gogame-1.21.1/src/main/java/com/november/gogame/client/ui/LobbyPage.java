package com.november.gogame.client.ui;

import com.november.gogame.client.ai.AiConfig;
import com.november.gogame.client.ai.AiDifficulty;
import com.november.gogame.client.ai.AiProvider;
import com.november.gogame.client.ai.GoAiClient;
import com.november.gogame.client.ai.LocalAiGame;
import com.november.gogame.client.ai.ModelListTurn;
import com.november.gogame.common.game.GameResult;
import com.november.gogame.common.game.GameRoom;
import com.november.gogame.common.net.GoClientCache;
import com.november.gogame.common.net.GoNetwork;
import com.november.gogame.common.net.GoPayloads;
import com.november.gogame.common.rules.Stone;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.api.client.ui.PhoneStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 围棋 App 的大厅页（手机内页，约 120×200 逻辑像素）。
 *
 * <p>负责"进对局之前"的一切：创建对局（执色二选一）、凭 6 位房间号加入、房主邀请在线玩家、
 * 响应别人的邀请、离开房间。<b>真正下棋不在这里</b>——对局一开始就跳全屏 {@link GoBoardScreen}
 * （{@code setScreen}），因为这块小窗每格仅约 5 像素，19 路根本点不准（实施计划锁定决策）。
 *
 * <h2>数据来源与线程</h2>
 * 本类只读 {@link GoClientCache}（服务端经 S2C 包写入的渲染快照）、只经 {@link GoNetwork#sendToServer}
 * 发 C2S 包，自己不持有任何对局真值。所有回调都在客户端主线程（mcphone 转发），故无需同步。
 *
 * <h2>即时模式 UI</h2>
 * 手机内页没有原版控件系统（{@code IPhonePage} 不是 {@code Screen}）。故用即时模式：{@link #render}
 * 每帧重建可点击目标表 {@link #targets}，{@link #mouseClicked} 遍历命中即触发。绘制零件见 {@link GoUi}，
 * 配色全部取自 {@link PhoneStyle}（不写死，跟随手机主题）。
 */
public final class LobbyPage implements IPhonePage {

    /** 大厅子面板。ONLINE 仅在"已建房等待对手"时作为邀请面板覆盖显示；AI / AI_SETTINGS / AI_MODELS 是人机对弈入口、设置与模型选择（Phase 4） */
    private enum View { MENU, CREATE, JOIN, ONLINE, AI, AI_SETTINGS, AI_MODELS }

    /** AI 设置页正在编辑哪一行；NONE = 没在编辑。模型行改为选择列表、不再打字，故无 MODEL */
    private enum Editing { NONE, API_KEY, BASE_URL }

    private static final int PAD = 5;
    private static final int BTN_H = 16;
    private static final int GAP = 4;
    private static final int ROOM_ID_LEN = 6;
    /** AI 设置页单行文本上限（Key/地址/模型名都用不着更长） */
    private static final int AI_INPUT_LEN = 64;
    private static final int ROW_H = 16;
    private static final long ERROR_MS = 2600L;

    // 错误提示的红（与范本一致：底色半透明压任何主题，前景亮红）
    private static final int ERROR_BG = 0x33E5484D;
    private static final int ERROR_FG = 0xFFFF8A8F;
    private static final int SUNKEN = 0x40000000;

    private View view = View.MENU;
    private GameRoom.ColorMode createMode = GameRoom.ColorMode.HOST_PICKS;
    private Stone createColor = Stone.BLACK;
    private final StringBuilder roomIdInput = new StringBuilder();
    private boolean roomIdFocused;
    private int onlineScroll;

    // ---- AI 视图状态（难度选择默认从配置读，选定后回写记住）----
    private AiDifficulty aiDifficulty = AiConfig.get().difficulty();
    /** 玩家在 AI 菜单里为自己选的执色（EMPTY=随机）；注意与 {@code LocalAiGame.aiColor()}（AI 的色）相反 */
    private Stone playerColorChoice = Stone.BLACK;
    private int aiScroll;
    private Editing editing = Editing.NONE;
    private final StringBuilder aiInput = new StringBuilder();
    /** 模型清单拉取状态（HTTP 线程写、主线程读）；null = 还没拉过，进选择页时触发 */
    private ModelListTurn modelFetch;
    private int modelScroll;

    private String errorKey;
    private long errorUntilMs;

    /** 本帧登记的可点击目标；mouseClicked 遍历它（即时模式） */
    private final List<ClickTarget> targets = new ArrayList<>();
    /** 上一次 render 的内容区，供 mouseClicked 判断"点在页面内还是页面外" */
    private int lastX, lastY, lastW, lastH;

    private record ClickTarget(int x, int y, int w, int h, Runnable action) {}

    @Override
    public void onOpen() {
        view = View.MENU;
        onlineScroll = 0;
        editing = Editing.NONE;
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(PhoneCanvas c) {
        targets.clear();
        pollError();

        PhoneStyle s = c.style();
        GuiGraphics g = c.graphics();
        Font f = c.font();
        int x = c.x(), y = c.y(), w = c.width(), h = c.height();
        lastX = x; lastY = y; lastW = w; lastH = h;

        g.drawString(f, tr("gogame.lobby.title"), x + PAD, y + PAD, s.titleColor(), false);
        int cy = y + PAD + f.lineHeight + GAP + 2;

        GoPayloads.RoomState room = GoClientCache.getRoom();
        if (room == null) {
            cy = renderInviteBanner(c, cy);
            switch (view) {
                case CREATE -> renderCreate(c, cy);
                case JOIN -> renderJoin(c, cy);
                case AI -> renderAi(c, cy);
                case AI_SETTINGS -> renderAiSettings(c, cy);
                case AI_MODELS -> renderAiModels(c, cy);
                default -> renderMenu(c, cy);   // MENU；无房时 ONLINE 退回菜单
            }
        } else if (room.phase() == GameRoom.Phase.WAITING) {
            if (view == View.ONLINE) renderOnline(c, cy);
            else renderWaiting(c, room, cy);
        } else {
            renderInGame(c, room, cy);
        }

        renderErrorToast(c);
    }

    private void renderMenu(PhoneCanvas c, int cy) {
        int x = c.x() + PAD, w = c.width() - 2 * PAD;
        int y = cy;
        button(c, x, y, w, BTN_H, tr("gogame.lobby.create"), true, () -> view = View.CREATE);
        y += BTN_H + GAP;
        button(c, x, y, w, BTN_H, tr("gogame.lobby.join"), true, () -> { view = View.JOIN; roomIdFocused = true; });
        y += BTN_H + GAP;
        button(c, x, y, w, BTN_H, tr("gogame.lobby.ai"), true, () -> view = View.AI);
        y += BTN_H + GAP + 2;
        // hint 可能超一行宽：按语言文件里的 \n 逐行绘制、每行 truncate 兜底（验收修复：原先整行直画，窄屏下文字漏出手机右边界）
        int lh = c.font().lineHeight + 1;
        for (String line : tr("gogame.lobby.hint").split("\n")) {
            c.graphics().drawString(c.font(), GoUi.truncate(c.font(), line, w), x, y, c.style().subtleColor(), false);
            y += lh;
        }
    }

    private void renderCreate(PhoneCanvas c, int cy) {
        PhoneStyle s = c.style();
        GuiGraphics g = c.graphics();
        Font f = c.font();
        int x = c.x() + PAD, w = c.width() - 2 * PAD;
        int y = cy;

        g.drawString(f, tr("gogame.lobby.create.title"), x, y, s.bodyColor(), false);
        y += f.lineHeight + GAP;

        int third = (w - 2 * GAP) / 3;
        boolean black = createMode == GameRoom.ColorMode.HOST_PICKS && createColor == Stone.BLACK;
        boolean white = createMode == GameRoom.ColorMode.HOST_PICKS && createColor == Stone.WHITE;
        boolean random = createMode == GameRoom.ColorMode.RANDOM;
        choice(c, x, y, third, BTN_H, tr("gogame.lobby.color.black"), black,
                () -> { createMode = GameRoom.ColorMode.HOST_PICKS; createColor = Stone.BLACK; });
        choice(c, x + third + GAP, y, third, BTN_H, tr("gogame.lobby.color.white"), white,
                () -> { createMode = GameRoom.ColorMode.HOST_PICKS; createColor = Stone.WHITE; });
        choice(c, x + 2 * (third + GAP), y, w - 2 * (third + GAP), BTN_H, tr("gogame.lobby.color.random"), random,
                () -> { createMode = GameRoom.ColorMode.RANDOM; createColor = Stone.EMPTY; });
        y += BTN_H + GAP * 2;

        button(c, x, y, w, BTN_H, tr("gogame.lobby.create.go"), true, this::createRoom);
        y += BTN_H + GAP;
        button(c, x, y, w, BTN_H, tr("gogame.lobby.back"), true, () -> view = View.MENU);
    }

    private void renderJoin(PhoneCanvas c, int cy) {
        PhoneStyle s = c.style();
        GuiGraphics g = c.graphics();
        Font f = c.font();
        int x = c.x() + PAD, w = c.width() - 2 * PAD;
        int y = cy;

        g.drawString(f, tr("gogame.lobby.join.title"), x, y, s.bodyColor(), false);
        y += f.lineHeight + GAP;

        // 房间号输入框（凹陷底 + 边框，聚焦时边框用强调色 + 闪烁光标）
        GoUi.roundRect(g, x, y, w, BTN_H, 2, SUNKEN);
        GoUi.outline(g, x, y, w, BTN_H, roomIdFocused ? s.accentColor() : s.subtleColor());
        String txt = roomIdInput.toString();
        g.drawString(f, txt, x + 3, GoUi.controlTextY(f, y, BTN_H), s.titleColor(), false);
        if (roomIdFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
            GoUi.vLine(g, x + 3 + f.width(txt) + 1, y + 3, BTN_H - 6, s.titleColor());
        }
        targets.add(new ClickTarget(x, y, w, BTN_H, () -> roomIdFocused = true));
        y += BTN_H + GAP * 2;

        button(c, x, y, w, BTN_H, tr("gogame.lobby.join.go"), true, this::submitJoin);
        y += BTN_H + GAP;
        button(c, x, y, w, BTN_H, tr("gogame.lobby.back"), true, () -> { view = View.MENU; roomIdFocused = false; });
    }

    /** AI 视图：难度滚动列表（高度吃剩余空间，至少露 3 行）+ 执色三选 + 开始/设置/返回 */
    private void renderAi(PhoneCanvas c, int cy) {
        PhoneStyle s = c.style();
        GuiGraphics g = c.graphics();
        Font f = c.font();
        int x = c.x() + PAD, w = c.width() - 2 * PAD;
        int y = cy;

        g.drawString(f, tr("gogame.ai.difficulty"), x, y, s.bodyColor(), false);
        y += f.lineHeight + GAP;

        // 底部固定块：执色 1 行 + 开始/设置并排 1 行 + 返回 1 行
        int bottomH = (BTN_H + GAP) * 3;
        int listTop = y;
        int listBottom = Math.max(listTop + (ROW_H + 2) * 3, c.y() + c.height() - PAD - bottomH);
        int viewH = listBottom - listTop;

        AiDifficulty[] all = AiDifficulty.values();
        int contentH = all.length * (ROW_H + 2);
        int maxScroll = Math.max(0, contentH - viewH);
        aiScroll = Math.clamp(aiScroll, 0, maxScroll);

        c.clipped(c.x(), listTop, c.width(), viewH, () -> {
            int ry = listTop - aiScroll;
            for (AiDifficulty d : all) {
                if (ry + ROW_H >= listTop && ry <= listBottom) {   // 仅可见行才登记点击目标
                    choice(c, x, ry, w, ROW_H, tr(d.langKey()), d == aiDifficulty, () -> aiDifficulty = d);
                }
                ry += ROW_H + 2;
            }
        });
        scrollBar(c, listTop, viewH, contentH, maxScroll, aiScroll);
        y = listBottom + GAP;

        int third = (w - 2 * GAP) / 3;
        choice(c, x, y, third, BTN_H, tr("gogame.lobby.color.black"), playerColorChoice == Stone.BLACK, () -> playerColorChoice = Stone.BLACK);
        choice(c, x + third + GAP, y, third, BTN_H, tr("gogame.lobby.color.white"), playerColorChoice == Stone.WHITE, () -> playerColorChoice = Stone.WHITE);
        choice(c, x + 2 * (third + GAP), y, w - 2 * (third + GAP), BTN_H, tr("gogame.lobby.color.random"), playerColorChoice == Stone.EMPTY, () -> playerColorChoice = Stone.EMPTY);
        y += BTN_H + GAP;

        int half = (w - GAP) / 2;
        button(c, x, y, half, BTN_H, tr("gogame.ai.start"), true, this::startAi);
        button(c, x + half + GAP, y, w - half - GAP, BTN_H, tr("gogame.ai.settings"), true, () -> view = View.AI_SETTINGS);
        y += BTN_H + GAP;
        button(c, x, y, w, BTN_H, tr("gogame.lobby.back"), true, () -> view = View.MENU);
    }

    /** AI 设置页：服务方循环切 + Key/地址/模型三行点击编辑（手机内当场改当场生效） */
    private void renderAiSettings(PhoneCanvas c, int cy) {
        PhoneStyle s = c.style();
        GuiGraphics g = c.graphics();
        Font f = c.font();
        int x = c.x() + PAD, w = c.width() - 2 * PAD;
        int y = cy;
        AiConfig cfg = AiConfig.get();

        g.drawString(f, tr("gogame.ai.settings.title"), x, y, s.titleColor(), false);
        y += f.lineHeight + GAP;

        button(c, x, y, w, BTN_H, tr(cfg.provider().langKey()), true, this::cycleProvider);
        y += BTN_H + GAP;

        y = aiSettingsRow(c, x, y, w, "gogame.ai.apikey",
                editing == Editing.API_KEY ? aiInput.toString()
                        : cfg.hasApiKey() ? cfg.maskedApiKey() : tr("gogame.ai.apikey.empty"),
                Editing.API_KEY);
        y = aiSettingsRow(c, x, y, w, "gogame.ai.baseurl",
                editing == Editing.BASE_URL ? aiInput.toString() : cfg.baseUrl(), Editing.BASE_URL);
        // 模型行：点击进选择列表（从 /models 拉取），不再手打模型名
        y = aiPickerRow(c, x, y, w, "gogame.ai.model", cfg.model(), this::openModels);
        y += GAP;

        button(c, x, y, w, BTN_H, tr("gogame.lobby.back"), true, () -> { view = View.AI; editing = Editing.NONE; });
    }

    /** 模型选择页：刷新 / 返回 + 从 /models 拉到的模型滚动列表（加载/错误/空清单各有提示） */
    private void renderAiModels(PhoneCanvas c, int cy) {
        PhoneStyle s = c.style();
        GuiGraphics g = c.graphics();
        Font f = c.font();
        int x = c.x() + PAD, w = c.width() - 2 * PAD;
        int y = cy;

        g.drawString(f, tr("gogame.ai.model.select"), x, y, s.titleColor(), false);
        y += f.lineHeight + GAP;

        int half = (w - GAP) / 2;
        button(c, x, y, half, BTN_H, tr("gogame.ai.model.refresh"), true, this::refreshModels);
        button(c, x + half + GAP, y, w - half - GAP, BTN_H, tr("gogame.lobby.back"), true, () -> view = View.AI_SETTINGS);
        y += BTN_H + GAP;

        int listTop = y;
        int listBottom = c.y() + c.height() - PAD;
        int viewH = Math.max(0, listBottom - listTop);

        ModelListTurn fetch = modelFetch;
        if (fetch == null || fetch.isRunning()) {
            g.drawString(f, GoUi.truncate(f, tr("gogame.ai.model.loading"), w), x, listTop + 2, s.subtleColor(), false);
            return;
        }
        if (fetch.state() == ModelListTurn.State.ERROR) {
            g.drawString(f, GoUi.truncate(f, tr(fetch.errorKey()), w), x, listTop + 2, ERROR_FG, false);
            return;
        }
        List<String> models = fetch.models();
        if (models.isEmpty()) {
            g.drawString(f, GoUi.truncate(f, tr("gogame.ai.model.empty"), w), x, listTop + 2, s.subtleColor(), false);
            return;
        }

        int contentH = models.size() * (ROW_H + 2);
        int maxScroll = Math.max(0, contentH - viewH);
        modelScroll = Math.clamp(modelScroll, 0, maxScroll);

        String current = AiConfig.get().model();
        c.clipped(c.x(), listTop, c.width(), viewH, () -> {
            int ry = listTop - modelScroll;
            for (String m : models) {
                if (ry + ROW_H >= listTop && ry <= listBottom) {   // 仅可见行才登记点击目标
                    choice(c, x, ry, w, ROW_H, GoUi.truncate(f, m, w - 8), m.equals(current),
                            () -> { AiConfig.get().setModel(m); view = View.AI_SETTINGS; });
                }
                ry += ROW_H + 2;
            }
        });
        scrollBar(c, listTop, viewH, contentH, maxScroll, modelScroll);
    }

    /** 设置页一行：标签 + 凹陷值框（与 JOIN 输入框同构）；点击进编辑态。返回下一行 y */
    private int aiSettingsRow(PhoneCanvas c, int x, int y, int w, String labelKey, String value, Editing field) {
        PhoneStyle s = c.style();
        GuiGraphics g = c.graphics();
        Font f = c.font();

        g.drawString(f, tr(labelKey), x, y, s.subtleColor(), false);
        y += f.lineHeight + 1;
        GoUi.roundRect(g, x, y, w, BTN_H, 2, SUNKEN);
        GoUi.outline(g, x, y, w, BTN_H, editing == field ? s.accentColor() : s.subtleColor());
        g.drawString(f, GoUi.truncate(f, value, w - 6), x + 3, GoUi.controlTextY(f, y, BTN_H), s.titleColor(), false);
        if (editing == field && (System.currentTimeMillis() / 500) % 2 == 0) {
            GoUi.vLine(g, x + 3 + f.width(GoUi.truncate(f, value, w - 6)) + 1, y + 3, BTN_H - 6, s.titleColor());
        }
        targets.add(new ClickTarget(x, y, w, BTN_H, () -> beginEdit(field)));
        return y + BTN_H + GAP;
    }

    /** 选择行：标签 + 凹陷值框（外观同输入行），但点击进选择列表而非打字；无光标/编辑态 */
    private int aiPickerRow(PhoneCanvas c, int x, int y, int w, String labelKey, String value, Runnable onClick) {
        PhoneStyle s = c.style();
        GuiGraphics g = c.graphics();
        Font f = c.font();

        g.drawString(f, tr(labelKey), x, y, s.subtleColor(), false);
        y += f.lineHeight + 1;
        GoUi.roundRect(g, x, y, w, BTN_H, 2, SUNKEN);
        GoUi.outline(g, x, y, w, BTN_H, s.subtleColor());
        g.drawString(f, GoUi.truncate(f, value, w - 6), x + 3, GoUi.controlTextY(f, y, BTN_H), s.titleColor(), false);
        targets.add(new ClickTarget(x, y, w, BTN_H, onClick));
        return y + BTN_H + GAP;
    }

    private void renderWaiting(PhoneCanvas c, GoPayloads.RoomState room, int cy) {
        PhoneStyle s = c.style();
        GuiGraphics g = c.graphics();
        Font f = c.font();
        int x = c.x() + PAD, w = c.width() - 2 * PAD;
        int y = cy;

        g.drawString(f, tr("gogame.lobby.roomcode"), x, y, s.subtleColor(), false);
        y += f.lineHeight;
        g.drawString(f, room.roomId(), x, y, s.accentColor(), false);
        y += f.lineHeight + GAP;
        g.drawString(f, GoUi.truncate(f, tr("gogame.lobby.waiting"), w), x, y, s.bodyColor(), false);
        y += f.lineHeight + GAP * 2;

        button(c, x, y, w, BTN_H, tr("gogame.lobby.invite.friend"), true, () -> {
            GoNetwork.sendToServer(new GoPayloads.RequestOnlinePlayers());
            view = View.ONLINE;
            onlineScroll = 0;
        });
        y += BTN_H + GAP;
        button(c, x, y, w, BTN_H, tr("gogame.lobby.leave"), true, this::leaveRoom);
    }

    private void renderOnline(PhoneCanvas c, int cy) {
        PhoneStyle s = c.style();
        GuiGraphics g = c.graphics();
        Font f = c.font();
        int x = c.x() + PAD, w = c.width() - 2 * PAD;
        int y = cy;

        g.drawString(f, tr("gogame.lobby.online.title"), x, y, s.titleColor(), false);
        y += f.lineHeight + GAP;

        // 顶部两枚小按钮：刷新 / 返回
        int half = (w - GAP) / 2;
        button(c, x, y, half, BTN_H, tr("gogame.lobby.online.refresh"), true,
                () -> GoNetwork.sendToServer(new GoPayloads.RequestOnlinePlayers()));
        button(c, x + half + GAP, y, w - half - GAP, BTN_H, tr("gogame.lobby.back"), true, () -> view = View.MENU);
        y += BTN_H + GAP;

        List<GoPayloads.GoOnlinePlayer> players = GoClientCache.getOnlinePlayers();
        int listTop = y;
        int listBottom = c.y() + c.height() - PAD;
        int viewH = Math.max(0, listBottom - listTop);

        if (players.isEmpty()) {
            g.drawString(f, GoUi.truncate(f, tr("gogame.lobby.online.empty"), w), x, listTop + 2, s.subtleColor(), false);
            return;
        }

        int contentH = players.size() * (ROW_H + 2);
        int maxScroll = Math.max(0, contentH - viewH);
        onlineScroll = Math.clamp(onlineScroll, 0, maxScroll);

        c.clipped(c.x(), listTop, c.width(), viewH, () -> {
            int ry = listTop - onlineScroll;
            for (GoPayloads.GoOnlinePlayer p : players) {
                if (ry + ROW_H >= listTop && ry <= listBottom) {   // 仅可见行才登记点击目标
                    int nameW = w - 40;
                    g.drawString(f, GoUi.truncate(f, p.name(), nameW), x, GoUi.textY(f, ry, ROW_H), s.bodyColor(), false);
                    button(c, x + w - 38, ry, 38, ROW_H, tr("gogame.lobby.online.invite"), true,
                            () -> GoNetwork.sendToServer(new GoPayloads.InvitePlayer(p.id())));
                }
                ry += ROW_H + 2;
            }
        });
        scrollBar(c, listTop, viewH, contentH, maxScroll, onlineScroll);
    }

    /** 右侧 1px 滚动提示：细轨道 + 强调色拇指；仅当内容溢出视口才画，短列表不留多余线 */
    private void scrollBar(PhoneCanvas c, int listTop, int viewH, int contentH, int maxScroll, int scroll) {
        if (maxScroll <= 0 || viewH <= 0) return;
        PhoneStyle s = c.style();
        GuiGraphics g = c.graphics();
        int tx = c.x() + c.width() - 2;
        GoUi.vLine(g, tx, listTop, viewH, s.subtleColor());
        int thumbH = Math.min(viewH, Math.max(6, viewH * viewH / contentH));
        int thumbY = listTop + (viewH - thumbH) * scroll / maxScroll;
        GoUi.vLine(g, tx, thumbY, thumbH, s.accentColor());
    }

    private void renderInGame(PhoneCanvas c, GoPayloads.RoomState room, int cy) {
        PhoneStyle s = c.style();
        GuiGraphics g = c.graphics();
        Font f = c.font();
        int x = c.x() + PAD, w = c.width() - 2 * PAD;
        int y = cy;

        GameResult result = GoClientCache.getResult();
        if (result != null || room.phase() == GameRoom.Phase.FINISHED) {
            g.drawString(f, tr("gogame.lobby.finished"), x, y, s.titleColor(), false);
            y += f.lineHeight + GAP;
            if (result != null) {
                g.drawString(f, GoUi.truncate(f, GoText.describeResult(result), w), x, y, s.bodyColor(), false);
                y += f.lineHeight + GAP * 2;
            }
            button(c, x, y, w, BTN_H, tr("gogame.lobby.back"), true, this::leaveRoom);
            return;
        }

        g.drawString(f, tr("gogame.lobby.started"), x, y, s.bodyColor(), false);
        y += f.lineHeight + GAP;
        Stone mine = myColor(room);
        g.drawString(f, tr("gogame.lobby.yourcolor") + GoText.colorName(mine), x, y, s.accentColor(), false);
        y += f.lineHeight + GAP * 2;

        button(c, x, y, w, BTN_H, tr("gogame.lobby.enter"), true,
                () -> Minecraft.getInstance().setScreen(new GoBoardScreen()));
        y += BTN_H + GAP;
        button(c, x, y, w, BTN_H, tr("gogame.lobby.resignleave"), true, this::leaveRoom);
    }

    /** 顶部邀请横幅（仅无房时）：谁邀你、进哪个房，接受/拒绝。返回横幅之后的下一个 y */
    private int renderInviteBanner(PhoneCanvas c, int cy) {
        GoPayloads.InviteNotify inv = GoClientCache.getPendingInvite();
        if (inv == null) return cy;

        PhoneStyle s = c.style();
        GuiGraphics g = c.graphics();
        Font f = c.font();
        int x = c.x() + PAD, w = c.width() - 2 * PAD;
        int y = cy;

        int boxH = f.lineHeight * 2 + BTN_H + GAP * 2 + 4;
        GoUi.roundRect(g, x, y, w, boxH, 3, SUNKEN);
        GoUi.outline(g, x, y, w, boxH, s.accentColor());

        String line = GoUi.truncate(f, tr("gogame.lobby.invite.from", inv.hostName()), w - 8);
        g.drawString(f, line, x + 4, y + 4, s.titleColor(), false);
        g.drawString(f, tr("gogame.lobby.roomcode") + " " + inv.roomId(), x + 4, y + 4 + f.lineHeight, s.subtleColor(), false);

        int by = y + 4 + f.lineHeight * 2 + GAP;
        int half = (w - 8 - GAP) / 2;
        button(c, x + 4, by, half, BTN_H, tr("gogame.lobby.accept"), true, () -> {
            GoNetwork.sendToServer(new GoPayloads.RespondInvite(inv.hostId(), true));
            GoClientCache.clearInvite();
            view = View.MENU;
        });
        button(c, x + 4 + half + GAP, by, w - 8 - half - GAP, BTN_H, tr("gogame.lobby.decline"), true, () -> {
            GoNetwork.sendToServer(new GoPayloads.RespondInvite(inv.hostId(), false));
            GoClientCache.clearInvite();
        });
        return y + boxH + GAP;
    }

    private void renderErrorToast(PhoneCanvas c) {
        if (errorKey == null) return;
        if (System.currentTimeMillis() >= errorUntilMs) { errorKey = null; return; }

        PhoneStyle s = c.style();
        GuiGraphics g = c.graphics();
        Font f = c.font();
        int th = f.lineHeight + 6;
        int ty = c.y() + c.height() - PAD - th;
        int x = c.x() + PAD, w = c.width() - 2 * PAD;
        GoUi.roundRect(g, x, ty, w, th, 2, ERROR_BG);
        GoUi.centered(g, f, GoUi.truncate(f, tr(errorKey), w - 8), x, ty + 3, w, ERROR_FG);
    }

    // ------------------------------------------------------------------
    // 绘制零件
    // ------------------------------------------------------------------

    private void button(PhoneCanvas c, int x, int y, int w, int h, String label, boolean enabled, Runnable action) {
        PhoneStyle s = c.style();
        boolean hov = c.hovered(x, y, w, h);
        GoUi.button(c.graphics(), c.font(), x, y, w, h, label, enabled, hov,
                s.buttonColor(), s.buttonHoverColor(), s.buttonDisabledColor(),
                s.titleColor(), s.buttonDisabledTextColor());
        if (enabled) targets.add(new ClickTarget(x, y, w, h, action));
    }

    /** 单选按钮：选中时用强调色底 */
    private void choice(PhoneCanvas c, int x, int y, int w, int h, String label, boolean selected, Runnable action) {
        PhoneStyle s = c.style();
        boolean hov = c.hovered(x, y, w, h);
        int bg = selected ? s.accentColor() : s.buttonColor();
        GoUi.button(c.graphics(), c.font(), x, y, w, h, label, true, hov && !selected,
                bg, s.buttonHoverColor(), s.buttonDisabledColor(), s.titleColor(), s.buttonDisabledTextColor());
        targets.add(new ClickTarget(x, y, w, h, action));
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    private void createRoom() {
        GoClientCache.clearResult();
        GoNetwork.sendToServer(new GoPayloads.CreateRoom(createMode, createColor));
        view = View.MENU;   // 成功则 room!=null 自动切等待面板；失败则回菜单 + 错误 toast
    }

    private void submitJoin() {
        String code = roomIdInput.toString();
        if (code.length() != ROOM_ID_LEN) { showError("gogame.lobby.join.invalid"); return; }
        GoClientCache.clearResult();
        GoNetwork.sendToServer(new GoPayloads.JoinRoom(code));
        view = View.MENU;
        roomIdFocused = false;
    }

    private void leaveRoom() {
        // PVE 没有服务端可通知，LocalAiGame.leave 自带清缓存（roomId="PVE" 的快照也是它馈送的）
        if (LocalAiGame.active()) {
            LocalAiGame.leave();
            view = View.MENU;
            return;
        }
        GoPayloads.RoomState room = GoClientCache.getRoom();
        // 未终局才通知服务端（WAITING→解散 / PLAYING→认输）；已终局服务端早已解绑，只需本地清缓存
        if (room != null && room.phase() != GameRoom.Phase.FINISHED) {
            GoNetwork.sendToServer(new GoPayloads.LeaveRoom());
        }
        GoClientCache.clear();
        view = View.MENU;
        roomIdInput.setLength(0);
        roomIdFocused = false;
    }

    /** 开 PVE：没填 Key 直接拦下并跳设置页（发请求也会立刻回 no_key，不如当场说清楚） */
    private void startAi() {
        if (!AiConfig.get().hasApiKey()) {
            showError("gogame.ai.error.no_key");
            view = View.AI_SETTINGS;
            return;
        }
        AiConfig.get().setDifficulty(aiDifficulty);   // 记住选择，下次默认同一档
        GoClientCache.clearResult();
        LocalAiGame.start(aiDifficulty, playerColorChoice);
        view = View.MENU;   // start 已馈送房间快照，下一帧 render 自动切进局中面板
    }

    /** 服务方按枚举顺序循环切；switchProvider 整组切到该服务方各自记住的 Key/地址/模型 */
    private void cycleProvider() {
        AiConfig cfg = AiConfig.get();
        AiProvider[] all = AiProvider.values();
        cfg.switchProvider(all[(cfg.provider().ordinal() + 1) % all.length]);
        modelFetch = null;   // 换了服务方：旧清单是别家的、作废，下次进选择页按新服务方重拉
    }

    /** 进模型选择页：首次（或配置变动清空后）触发一次 /models 拉取 */
    private void openModels() {
        view = View.AI_MODELS;
        modelScroll = 0;
        editing = Editing.NONE;
        if (modelFetch == null) modelFetch = GoAiClient.fetchModels();
    }

    /** 手动重拉模型清单（换了 Key/地址、或上次失败后重试） */
    private void refreshModels() {
        modelScroll = 0;
        modelFetch = GoAiClient.fetchModels();
    }

    /** 进编辑态：缓冲区预填当前值（Key 除外——遮蔽值没法编辑，从空开始重填） */
    private void beginEdit(Editing field) {
        editing = field;
        aiInput.setLength(0);
        AiConfig cfg = AiConfig.get();
        String pre = switch (field) {
            case API_KEY -> "";
            case BASE_URL -> cfg.baseUrl();
            case NONE -> "";
        };
        aiInput.append(pre, 0, Math.min(pre.length(), AI_INPUT_LEN));
    }

    /** 回车提交编辑：写回配置（setter 即时落盘）并退出编辑态 */
    private void commitEdit() {
        AiConfig cfg = AiConfig.get();
        switch (editing) {
            case API_KEY -> cfg.setApiKey(aiInput.toString());
            case BASE_URL -> cfg.setBaseUrl(aiInput.toString());
            case NONE -> { }
        }
        editing = Editing.NONE;
        modelFetch = null;   // 改了 Key/地址：旧模型清单作废，下次进选择页重拉
    }

    // ------------------------------------------------------------------
    // 输入事件
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        boolean inside = GoUi.hit(mx, my, lastX, lastY, lastW, lastH);
        if (button == 0) {
            for (ClickTarget t : targets) {
                if (GoUi.hit(mx, my, t.x, t.y, t.w, t.h)) { t.action.run(); return true; }
            }
            if (inside) { roomIdFocused = false; editing = Editing.NONE; return true; }   // 页面内空点击：输入框失焦并消费
        }
        // 页面内消费（避免"点手机外=关机"误触）；页面外返回 false 交回 mcphone（导航栏/关机）
        return inside;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (view == View.ONLINE) { onlineScroll = Math.max(0, onlineScroll - (int) (amount * (ROW_H + 2))); return true; }
        if (view == View.AI) { aiScroll = Math.max(0, aiScroll - (int) (amount * (ROW_H + 2))); return true; }
        if (view == View.AI_MODELS) { modelScroll = Math.max(0, modelScroll - (int) (amount * (ROW_H + 2))); return true; }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (view == View.JOIN && roomIdFocused) {
            if (isPaste(keyCode, modifiers)) { pasteDigits(roomIdInput, ROOM_ID_LEN); return true; }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (roomIdInput.length() > 0) roomIdInput.deleteCharAt(roomIdInput.length() - 1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) { submitJoin(); return true; }
        }
        if (aiEditing()) {
            if (isPaste(keyCode, modifiers)) { pasteAscii(aiInput, AI_INPUT_LEN); return true; }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (aiInput.length() > 0) aiInput.deleteCharAt(aiInput.length() - 1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) { commitEdit(); return true; }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) { editing = Editing.NONE; return true; }   // ESC 取消编辑，不退视图
        }
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (view == View.JOIN && roomIdFocused) {
            if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0) return true;   // Ctrl 组合（含 Ctrl+V）交 keyPressed，字符回调不再插入
            if (Character.isDigit(codePoint) && roomIdInput.length() < ROOM_ID_LEN) roomIdInput.append(codePoint);
            return true;   // 聚焦时吞掉所有字符输入（含非数字，避免落到别处）
        }
        if (aiEditing()) {
            if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0) return true;   // 同上：粘贴走 keyPressed，防止多插一个 'v'
            // Key/地址/模型名都是 ASCII 可见字符；控制符与全角输入法产物一律不收
            if (codePoint >= 0x20 && codePoint < 0x7F && aiInput.length() < AI_INPUT_LEN) aiInput.append(codePoint);
            return true;
        }
        return false;
    }

    private boolean aiEditing() { return view == View.AI_SETTINGS && editing != Editing.NONE; }

    /**
     * 手机内页不是原版 {@code Screen}，拿不到 {@code EditBox} 自带的 Ctrl+V，得自己识别组合键并读系统剪贴板。
     * 只认 Ctrl+V（Windows/Linux；本机目标平台）。
     */
    private static boolean isPaste(int keyCode, int modifiers) {
        return keyCode == GLFW.GLFW_KEY_V && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
    }

    /** 把剪贴板里的可见 ASCII 追加进 buf（剥掉控制符/换行/全角，API Key 尾部常带换行），不超过 maxLen */
    private static void pasteAscii(StringBuilder buf, int maxLen) {
        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (clip == null) return;
        for (int i = 0; i < clip.length() && buf.length() < maxLen; i++) {
            char ch = clip.charAt(i);
            if (ch >= 0x20 && ch < 0x7F) buf.append(ch);
        }
    }

    /** 房间号框专用：只粘数字 */
    private static void pasteDigits(StringBuilder buf, int maxLen) {
        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (clip == null) return;
        for (int i = 0; i < clip.length() && buf.length() < maxLen; i++) {
            char ch = clip.charAt(i);
            if (Character.isDigit(ch)) buf.append(ch);
        }
    }

    /** JOIN 的房间号框与 AI 设置页的编辑框都要求捕获键盘，否则打拼音按到 e 会误关手机（见 PITFALLS E2） */
    @Override
    public boolean capturesKeyboard() { return view == View.JOIN || aiEditing(); }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private void pollError() {
        String e = GoClientCache.consumeError();
        if (e != null) showError(e);
    }

    private void showError(String key) {
        errorKey = key;
        errorUntilMs = System.currentTimeMillis() + ERROR_MS;
    }

    private static Stone myColor(GoPayloads.RoomState room) {
        Minecraft mc = Minecraft.getInstance();
        UUID me = mc.player != null ? mc.player.getUUID() : null;
        if (me == null) return Stone.EMPTY;
        if (me.equals(room.host().id())) return room.host().color();
        if (room.guest() != null && me.equals(room.guest().id())) return room.guest().color();
        return Stone.EMPTY;
    }

    // 结果 / 执色文案已下沉到共享的 GoText（两类不再各留一份，审查 W4/O3）

    private static String tr(String key) { return I18n.get(key); }

    private static String tr(String key, Object... args) { return I18n.get(key, args); }
}
