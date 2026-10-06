package com.november.gogame.client.ui;

import com.november.gogame.client.ai.LocalAiGame;
import com.november.gogame.client.icon.GoIcon;
import com.november.gogame.common.game.GameResult;
import com.november.gogame.common.game.GameRoom;
import com.november.gogame.common.net.GoClientCache;
import com.november.gogame.common.net.GoNetwork;
import com.november.gogame.common.net.GoPayloads;
import com.november.gogame.common.rules.Board;
import com.november.gogame.common.rules.GoRules;
import com.november.gogame.common.rules.Move;
import com.november.gogame.common.rules.Stone;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/**
 * 全屏 19 路棋盘（原版 {@link Screen}），对局一开始由大厅页 {@link LobbyPage} 经
 * {@code Minecraft.setScreen(new GoBoardScreen())} 打开。
 *
 * <p><b>为什么是全屏 Screen 而不是手机内页</b>：手机内容区仅约 120×200 逻辑像素，19 路每格
 * 只有约 5 像素，交叉点根本点不准（实施计划锁定决策）。故落子放到全屏，用原版 {@link Button}
 * 控件承载 pass/resign/返回，棋盘本体用 {@link GoUi} 自绘。
 *
 * <h2>数据来源与线程</h2>
 * 与大厅页同一套：只读 {@link GoClientCache}（服务端 S2C 快照）、只经 {@link GoNetwork#sendToServer}
 * 发 C2S 包，自己不持有任何对局真值、绝不自行判定胜负。全部回调在客户端主线程。
 *
 * <h2>渲染分层（为何手动逐个画按钮，而不走 {@code super.render}）</h2>
 * {@code Screen.render} 默认把「压暗背景」和「画控件」两步绑在一起，无法在两者之间插入自绘层：
 * 若先 {@code super.render} 再画棋盘，控件会被棋盘/面板盖住；若先画棋盘再 {@code super.render}，
 * 它会把背景再压暗一次盖到棋盘上。故这里使用自己的背景绘制，按
 * 贴图背景 → 棋盘 → 面板/模态 → 逐个 {@code button.render} → toast 的顺序手动分层，完全掌控 z 序。
 * 控件仍经 {@code addRenderableWidget} 注册，点击路由走 {@code super.mouseClicked}（读 children），
 * 只是绘制自己接管——{@link Button#render} 自带 hover 判定，手动画同样有高亮。
 *
 * <h2>最后一手标记：客户端推断（用户选定方案）</h2>
 * {@link GoPayloads.RoomState} 不含最后落点字段，且不改已审的 Phase 2 协议。故每收到新快照就与
 * 上一份 diff：手数恰 +1 时，扫出「上一份为空、这一份是刚行棋方颜色」的唯一交叉点即最后落点
 * （提子只会让对手的子由实变空，不会产生第二个空→实，故必唯一）。首次打开 / 重连（手数跳变）
 * 无从推断，标记留空——这是纯显示用途，不影响服务端权威，用户已接受这一降级。
 */
public final class GoBoardScreen extends Screen {

    // ---- 面板配色：与深色背景配套；手机内页仍走 PhoneStyle ----
    private static final int TITLE   = GoUi.IVORY;
    private static final int BODY    = 0xFFD8E0D6;
    private static final int SUBTLE  = 0xFF99AB9F;
    private static final int ACCENT  = GoUi.JADE;
    private static final int WARN    = 0xFFFF8A8F;   // 掉线 / 挂起 / 认输确认
    private static final int CARD    = 0xF0182521;
    private static final int KO_MARK = 0x99E2504A;   // 劫争禁着点（半透明红）
    private static final int GHOST_BLACK = 0x66101010;
    private static final int GHOST_WHITE = 0x66F0F0F0;
    private static final int TOAST_BG  = 0xF0392926;
    private static final int TOAST_FG  = 0xFFFF8A8F;

    private static final int MARGIN  = 12;
    private static final int PANEL_W = 132;
    private static final int BTN_H   = 20;
    private static final int BTN_GAP = 5;
    private static final long ERROR_MS = 2600L;

    // ---- 控件（init 里新建；resize 会重跑 init，故每轮都是新实例）----
    private Button backBtn;
    private Button passBtn;
    private Button resignBtn;
    private Button confirmYesBtn;
    private Button confirmNoBtn;

    /** 认输二次确认中：面板改显「确认认输？」+ [认输][取消]，pass/resign 暂隐 */
    private boolean confirmingResign;

    /** pass 那行的 Y 坐标（init 里算一次）：确认态把「确认认输？」问句画在这一行，杜绝两处各算一套公式而漂移（审查 W1） */
    private int passRowY;

    // ---- 最后一手推断状态 ----
    private GoPayloads.RoomState prevRoom;
    private int lastMoveIndex = -1;

    // ---- 错误 toast ----
    private String errorKey;
    private long errorUntilMs;

    public GoBoardScreen() {
        super(Component.translatable("gogame.board.title"));
    }

    /** 版面：棋盘（左，正方形）+ 信息面板（右列）。resize 后 init 重算，故 render 也每帧重算保持一致 */
    private record Layout(int cell, int boardPx, int bx, int by, int panelX, int panelY, int panelW) {}

    private Layout layout() {
        int regionW = Math.max(80, this.width - PANEL_W - MARGIN * 3);
        int availH  = Math.max(80, this.height - MARGIN * 2);
        int span = Math.min(availH, regionW);
        int cell = Math.max(4, span / (Board.SIZE + 1));   // 20 格：18 间隔 + 两侧各 1 格留白；下限 4 保极小窗仍成盘
        int boardPx = Math.min(cell * (Board.SIZE + 1), span);   // 夹回 span：cell 被下限抬高时不越界
        int bx = MARGIN + (regionW - boardPx) / 2;
        int by = MARGIN + (availH - boardPx) / 2;
        int panelX = this.width - MARGIN - PANEL_W;
        return new Layout(cell, boardPx, bx, by, panelX, MARGIN, PANEL_W);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        Layout L = layout();
        int btnX = L.panelX(), btnW = L.panelW();
        int bottom  = this.height - MARGIN;
        int backY   = bottom - BTN_H;
        int resignY = backY - BTN_GAP - BTN_H;
        int passY   = resignY - BTN_GAP - BTN_H;
        this.passRowY = passY;   // 存下 pass 行 Y，供确认态画问句复用（审查 W1）

        backBtn = addRenderableWidget(new GoButton(btnX, backY, btnW, BTN_H,
                Component.translatable("gogame.board.back"), b -> this.onClose(), GoButton.Tone.SECONDARY));
        resignBtn = addRenderableWidget(new GoButton(btnX, resignY, btnW, BTN_H,
                Component.translatable("gogame.board.resign"), b -> confirmingResign = true, GoButton.Tone.DANGER));
        passBtn = addRenderableWidget(new GoButton(btnX, passY, btnW, BTN_H,
                Component.translatable("gogame.board.pass"), b -> send(Move.Kind.PASS), GoButton.Tone.PRIMARY));

        // 二次确认按钮与 resign 同一行，左右各半；平时隐藏
        int half = (btnW - BTN_GAP) / 2;
        confirmYesBtn = addRenderableWidget(new GoButton(btnX, resignY, half, BTN_H,
                Component.translatable("gogame.board.resign.yes"), b -> doResign(), GoButton.Tone.DANGER));
        confirmNoBtn = addRenderableWidget(new GoButton(btnX + half + BTN_GAP, resignY, btnW - half - BTN_GAP, BTN_H,
                Component.translatable("gogame.board.resign.no"), b -> confirmingResign = false, GoButton.Tone.SECONDARY));
        confirmYesBtn.visible = false;
        confirmNoBtn.visible = false;
    }

    /**
     * 缓存被清（离房 / 断线）就退回世界。<b>放在 tick 而非 render</b>：{@code onClose()} 会
     * {@code setScreen(null)}，在渲染调用栈里改当前屏，外层 {@code Minecraft.render} 可能再访问
     * {@code this.screen} 而 NPE（原版 {@code DeathScreen} 也是在 tick 里自行关屏）。
     */
    @Override
    public void tick() {
        super.tick();
        if (GoClientCache.getRoom() == null) this.onClose();
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        GoPayloads.RoomState room = GoClientCache.getRoom();
        // 防御：缓存被清（离房/断线）时只画背景，真正关屏交给 tick()（见下），避免在渲染栈里 setScreen 的 NPE 风险
        if (room == null) { drawBackdrop(g); return; }

        pollError();
        updateLastMove(room);
        Layout L = layout();

        drawBackdrop(g);
        drawBoard(g, room, L, mouseX, mouseY);
        drawPanel(g, room, L);
        if (isFinished(room)) drawFinishOverlay(g, room, L);

        updateWidgets(room);
        // 手动逐个画控件（不走 super.render，理由见类注释）；不可见的 Button.render 自会早退
        backBtn.render(g, mouseX, mouseY, partialTick);
        passBtn.render(g, mouseX, mouseY, partialTick);
        resignBtn.render(g, mouseX, mouseY, partialTick);
        confirmYesBtn.render(g, mouseX, mouseY, partialTick);
        confirmNoBtn.render(g, mouseX, mouseY, partialTick);

        drawToast(g);
    }

    private void drawBoard(GuiGraphics g, GoPayloads.RoomState room, Layout L, int mouseX, int mouseY) {
        int cell = L.cell(), bx = L.bx(), by = L.by(), boardPx = L.boardPx();
        Board board = room.board();

        GoUi.boardSurface(g, bx, by, boardPx);

        // 网格：19 条线，首线在 bx+cell（四周各留 1 格木边，边缘子不被裁）
        int span = (Board.SIZE - 1) * cell;
        for (int i = 0; i < Board.SIZE; i++) {
            GoUi.vLine(g, bx + (i + 1) * cell, by + cell, span, GoUi.GRID);
            GoUi.hLine(g, bx + cell, by + (i + 1) * cell, span, GoUi.GRID);
        }

        // 九星位
        int star = Math.max(2, cell / 4);
        for (int y = 0; y < Board.SIZE; y++) {
            for (int x = 0; x < Board.SIZE; x++) {
                if (Board.isStarPoint(x, y)) {
                    GoUi.circle(g, cx(bx, cell, x) - star / 2, cy(by, cell, y) - star / 2, star, GoUi.GRID);
                }
            }
        }

        // 棋子
        int d = Math.max(3, cell + 1);
        for (int y = 0; y < Board.SIZE; y++) {
            for (int x = 0; x < Board.SIZE; x++) {
                Stone s = board.at(x, y);
                if (s.isEmpty()) continue;
                int px = cx(bx, cell, x), py = cy(by, cell, y);
                GoUi.stone(g, px - d / 2, py - d / 2, d, s == Stone.BLACK, true);
            }
        }

        // 劫争禁着点（空点上的半透明红标）
        int ko = room.koIndex();
        if (ko != GoRules.NO_KO && ko >= 0 && ko < Board.COUNT) {
            int ks = Math.max(3, cell / 2);
            GoUi.circle(g, cx(bx, cell, Board.xOf(ko)) - ks / 2, cy(by, cell, Board.yOf(ko)) - ks / 2, ks, KO_MARK);
        }

        // 最后一手（落在某颗子上，红点对黑白子都醒目）
        if (lastMoveIndex >= 0 && lastMoveIndex < Board.COUNT) {
            int ms = Math.max(3, cell / 3);
            GoUi.circle(g, cx(bx, cell, Board.xOf(lastMoveIndex)) - ms / 2,
                    cy(by, cell, Board.yOf(lastMoveIndex)) - ms / 2, ms, GoUi.LAST_MOVE);
        }

        // 落点预览（仅轮到自己、非认输确认中、该点为空、且非劫争禁着点）
        if (myTurn(room) && !confirmingResign) {
            int[] xy = screenToBoard(mouseX, mouseY, L);
            if (xy != null && board.at(xy[0], xy[1]).isEmpty()
                    && Board.index(xy[0], xy[1]) != room.koIndex()) {
                int ghost = myColor(room) == Stone.BLACK ? GHOST_BLACK : GHOST_WHITE;
                int gd = Math.max(3, cell - 1);
                GoUi.circle(g, cx(bx, cell, xy[0]) - gd / 2, cy(by, cell, xy[1]) - gd / 2, gd, ghost);
            }
        }
    }

    private void drawBackdrop(GuiGraphics g) {
        g.fill(0, 0, this.width, this.height, 0xFF111D18);
        GoArt.image(g, GoArt.BACKDROP, 0, 0, this.width, this.height);
        g.fill(0, 0, this.width, this.height, 0x3009110D);
    }

    private void drawPanel(GuiGraphics g, GoPayloads.RoomState room, Layout L) {
        Font f = this.font;
        int lh = f.lineHeight;
        boolean compact = this.height < 280;
        int px = L.panelX(), pw = L.panelW();

        // 信息卡底：撑满右列，给黑白子与浅色文字衬对比
        int cardY = L.panelY() - 5;
        int cardH = (this.height - MARGIN) - cardY;
        GoUi.roundRect(g, px - 4, cardY + 2, pw + 10, cardH, 7, 0x45000000);
        GoUi.roundRect(g, px - 5, cardY, pw + 10, cardH, 7, CARD);
        GoUi.roundOutline(g, px - 5, cardY, pw + 10, cardH, 7, 0xFF3B4D40);

        int y = L.panelY();
        GoIcon.render(g, px, y, 22);
        g.drawString(f, tr("gogame.board.title"), px + 28, y + 1, TITLE, false);
        String roomLine = compact ? room.roomId() + " · " + tr("gogame.board.move", room.moveNumber())
                : tr("gogame.board.room") + " " + room.roomId();
        g.drawString(f, GoUi.truncate(f, roomLine, pw - 28), px + 28, y + lh + 4, SUBTLE, false);
        y += 31;

        boolean mine = myTurn(room);
        String turnLine = isFinished(room) ? tr("gogame.board.finished")
                : isSuspended(room) ? tr("gogame.board.suspended")
                : room.phase() == GameRoom.Phase.WAITING ? tr("gogame.board.waiting")
                : LocalAiGame.thinking() ? tr("gogame.ai.thinking")
                : tr("gogame.board.turn") + GoText.colorName(room.turn()) + (mine ? tr("gogame.board.you") : "");
        int statusColor = isSuspended(room) ? WARN : mine || LocalAiGame.thinking() ? ACCENT : BODY;
        int statusH = compact ? 17 : 23;
        GoUi.roundRect(g, px, y, pw, statusH, 4, mine ? 0xFF29483A : 0xFF25352D);
        GoUi.circle(g, px + 5, y + (statusH - 4) / 2, 4, statusColor);
        g.drawString(f, GoUi.truncate(f, turnLine, pw - 17), px + 14,
                GoUi.controlTextY(f, y, statusH), statusColor, false);
        y += statusH + 6;
        if (!compact) {
            g.drawString(f, tr("gogame.board.move", room.moveNumber()), px, y, SUBTLE, false);
            y += lh + 7;
        }

        if (room.phase() == GameRoom.Phase.WAITING) {
            // 等待阶段颜色多半未定（viewOfColor 对两色都返回 null 会两行皆空），改按座位顺序列，drawPlayerRow 对 null 早退
            y = drawPlayerRow(g, f, room, room.host(), room.capturedByBlack(), px, y, pw, lh, compact);
            y = drawPlayerRow(g, f, room, room.guest(), room.capturedByWhite(), px, y, pw, lh, compact);
        } else {
            y = drawPlayerRow(g, f, room, viewOfColor(room, Stone.BLACK), room.capturedByBlack(), px, y, pw, lh, compact);
            y = drawPlayerRow(g, f, room, viewOfColor(room, Stone.WHITE), room.capturedByWhite(), px, y, pw, lh, compact);
        }

        if (!compact && room.phase() == GameRoom.Phase.WAITING) {
            g.drawString(f, tr("gogame.board.waiting"), px, y + 2, SUBTLE, false);
        } else if (!compact && isSuspended(room)) {
            g.drawString(f, GoUi.truncate(f, tr("gogame.board.suspended"), pw), px, y + 2, WARN, false);
        } else if (!compact && LocalAiGame.thinking()) {
            // PVE：AI 请求在飞（HTTP 线程还没回 AiTurn），面板提示一行免得玩家以为卡死
            g.drawString(f, GoUi.truncate(f, tr("gogame.ai.thinking"), pw), px, y + 2, ACCENT, false);
        }

        if (passRowY - y >= lh * 2 + 14 && !confirmingResign) {
            g.drawString(f, GoUi.truncate(f, tr("gogame.board.rules"), pw), px, passRowY - lh - 7, SUBTLE, false);
        }

        // 认输二次确认的问句画在 pass 那行位置（此刻 pass/resign 已隐藏）；Y 复用 init 存的 passRowY，不再另算一套公式（审查 W1）
        if (confirmingResign && !isFinished(room)) {
            g.drawString(f, GoUi.truncate(f, tr("gogame.board.resign.confirm"), pw), px, passRowY, WARN, false);
        }
    }

    private int drawPlayerRow(GuiGraphics g, Font f, GoPayloads.RoomState room,
                              GoPayloads.PlayerView view, int captured, int px, int y, int pw, int lh, boolean compact) {
        if (view == null) return y;
        int dot = compact ? 12 : 16;
        Stone c = view.color();
        int rowH = compact ? 15 : lh * 2 + 9;
        boolean current = c.isStone() && c == room.turn() && !isFinished(room);
        GoUi.roundRect(g, px, y, pw, rowH, 4, current ? 0xFF2C3F32 : 0xFF202F28);
        if (c.isStone()) GoUi.stone(g, px + 2, y + 1, dot, c == Stone.BLACK, false);
        else GoUi.circle(g, px + 3, y + 3, 8, SUBTLE);

        int tx = px + dot + 6;
        int textW = pw - (dot + 10);
        boolean me = isMe(view.id());
        String count = tr("gogame.board.captured.short", captured);
        int countW = compact ? Math.min(40, f.width(count)) : 0;
        String name = GoUi.truncate(f, view.name() + (me ? tr("gogame.board.you") : ""), textW - countW - (compact ? 4 : 0));
        g.drawString(f, name, tx, y + 3, view.connected() ? BODY : WARN, false);
        if (compact) {
            g.drawString(f, GoUi.truncate(f, count, countW), px + pw - countW - 4, y + 3, SUBTLE, false);
            return y + rowH + 1;
        }
        y += lh;

        String line2 = tr("gogame.board.captured", captured) + (view.connected() ? "" : "  " + tr("gogame.board.offline"));
        g.drawString(f, GoUi.truncate(f, line2, textW), tx, y + 5, SUBTLE, false);
        return y + lh + 12;
    }

    /** 终局覆盖层：居中盖在棋盘上（不含任何控件，返回键在右侧面板，二者不重叠） */
    private void drawFinishOverlay(GuiGraphics g, GoPayloads.RoomState room, Layout L) {
        Font f = this.font;
        int lh = f.lineHeight;
        int ow = Math.min(L.boardPx() - 16, 240), oh = lh * 2 + 32;
        int ox = L.bx() + (L.boardPx() - ow) / 2;
        int oy = L.by() + (L.boardPx() - oh) / 2;
        GoUi.roundRect(g, ox + 2, oy + 3, ow, oh, 7, 0x66000000);
        GoUi.roundRect(g, ox, oy, ow, oh, 7, 0xF51B2B23);
        GoUi.roundOutline(g, ox, oy, ow, oh, 7, GoUi.GOLD);
        GoUi.hLine(g, ox + ow / 2 - 15, oy + 8, 30, GoUi.GOLD);
        GoUi.centered(g, f, tr("gogame.board.finished"), ox, oy + 15, ow, TITLE);
        GameResult r = GoClientCache.getResult();
        if (r != null) {
            GoUi.centered(g, f, GoUi.truncate(f, GoText.describeResult(r), ow - 16), ox, oy + 15 + lh + 4, ow, BODY);
        }
    }

    private void drawToast(GuiGraphics g) {
        if (errorKey == null) return;
        if (System.currentTimeMillis() >= errorUntilMs) { errorKey = null; return; }
        Font f = this.font;
        String s = tr(errorKey);
        int tw = Math.min(this.width - 20, f.width(s) + 16);
        int tx = (this.width - tw) / 2, th = f.lineHeight + 8, ty = this.height - th - 6;
        GoUi.roundRect(g, tx, ty, tw, th, 3, TOAST_BG);
        GoUi.roundOutline(g, tx, ty, tw, th, 3, 0xFFAD6B58);
        GoUi.centered(g, f, GoUi.truncate(f, s, tw - 8), tx, ty + 4, tw, TOAST_FG);
    }

    private void updateWidgets(GoPayloads.RoomState room) {
        boolean finished = isFinished(room);
        if (finished) confirmingResign = false;   // 终局即退出确认态：否则残留的 confirmingResign 会让 keyPressed 把玩家想关屏的第一次 ESC 当成“取消确认”吞掉（审查 Wa1）
        boolean playing = room.phase() == GameRoom.Phase.PLAYING;
        boolean conf = confirmingResign && !finished;
        boolean canAct = myTurn(room);

        backBtn.visible = true;
        backBtn.active = true;

        // active 一律绑 visible：隐藏的按钮永不 active，杜绝不同小版本下隐藏控件仍抢点击（审查 W2）
        boolean showPlay = playing && !finished && !conf;
        passBtn.visible = showPlay;
        passBtn.active = showPlay && canAct;
        resignBtn.visible = showPlay;
        resignBtn.active = showPlay && canAct;

        confirmYesBtn.visible = conf;
        confirmYesBtn.active = conf;
        confirmNoBtn.visible = conf;
        confirmNoBtn.active = conf;
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;   // 先给控件（pass/resign/返回/确认）
        if (button == 0) {
            GoPayloads.RoomState room = GoClientCache.getRoom();
            if (room != null && !confirmingResign && myTurn(room)) {
                int[] xy = screenToBoard(mouseX, mouseY, layout());
                if (xy != null && room.board().at(xy[0], xy[1]).isEmpty()) {
                    // PVE 走本地推演（LocalAiGame 自会校验非法着并馈送错误 toast），PVP 发服务端仲裁
                    if (LocalAiGame.active()) LocalAiGame.playPlace(xy[0], xy[1]);
                    else GoNetwork.sendToServer(new GoPayloads.PlayMove(xy[0], xy[1], Move.Kind.PLACE));
                    return true;
                }
            }
        }
        return false;   // 空白处点击不消费；Screen 不会因点击关闭（只有 ESC）
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 二次确认时 ESC 取消确认，而非直接关屏
        if (confirmingResign && keyCode == GLFW.GLFW_KEY_ESCAPE) { confirmingResign = false; return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    private void send(Move.Kind kind) {
        // pass/resign 与落子同一分支逻辑：PVE 本地，PVP 发服务端
        if (LocalAiGame.active()) LocalAiGame.playSpecial(kind);
        else GoNetwork.sendToServer(new GoPayloads.PlayMove(-1, -1, kind));
    }

    private void doResign() {
        confirmingResign = false;
        send(Move.Kind.RESIGN);
    }

    // ------------------------------------------------------------------
    // 最后一手推断（客户端 diff，见类注释）
    // ------------------------------------------------------------------

    private void updateLastMove(GoPayloads.RoomState room) {
        GoPayloads.RoomState prev = this.prevRoom;
        if (prev == room) return;                 // 缓存里还是同一份，无新包，保留原标记
        this.prevRoom = room;
        if (prev == null || !prev.roomId().equals(room.roomId())) { lastMoveIndex = -1; return; }   // 首份快照 / 换了房间，无从 diff
        if (room.moveNumber() == prev.moveNumber() + 1) {
            Stone mover = room.turn().opponent();  // 落子后 turn 已翻到对手，故行棋方是其对手
            lastMoveIndex = findAddedStone(prev.board(), room.board(), mover);
        } else {
            lastMoveIndex = -1;                    // 手数跳变（重连/多包合并），放弃标记
        }
    }

    /** 扫出「before 为空、after 是 mover 色」的唯一交叉点；虚着或找不到返回 -1 */
    private static int findAddedStone(Board before, Board after, Stone mover) {
        if (mover == null || !mover.isStone()) return -1;
        for (int i = 0; i < Board.COUNT; i++) {
            if (before.at(i).isEmpty() && after.at(i) == mover) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 查询小工具
    // ------------------------------------------------------------------

    private static int cx(int bx, int cell, int x) { return bx + (x + 1) * cell; }
    private static int cy(int by, int cell, int y) { return by + (y + 1) * cell; }

    private int[] screenToBoard(double mx, double my, Layout L) {
        double gx = (mx - (L.bx() + L.cell())) / (double) L.cell();
        double gy = (my - (L.by() + L.cell())) / (double) L.cell();
        int x = (int) Math.round(gx), y = (int) Math.round(gy);
        return Board.inBounds(x, y) ? new int[]{x, y} : null;
    }

    private boolean myTurn(GoPayloads.RoomState room) {
        if (room.phase() != GameRoom.Phase.PLAYING) return false;
        if (GoClientCache.getResult() != null) return false;
        if (!bothConnected(room)) return false;
        Stone mine = myColor(room);
        return mine.isStone() && mine == room.turn();
    }

    private static boolean bothConnected(GoPayloads.RoomState room) {
        return room.host() != null && room.host().connected()
                && room.guest() != null && room.guest().connected();
    }

    private static boolean isSuspended(GoPayloads.RoomState room) {
        return room.phase() == GameRoom.Phase.PLAYING && !bothConnected(room);
    }

    private static boolean isFinished(GoPayloads.RoomState room) {
        return room.phase() == GameRoom.Phase.FINISHED || GoClientCache.getResult() != null;
    }

    private static GoPayloads.PlayerView viewOfColor(GoPayloads.RoomState room, Stone c) {
        if (room.host() != null && room.host().color() == c) return room.host();
        if (room.guest() != null && room.guest().color() == c) return room.guest();
        return null;
    }

    private Stone myColor(GoPayloads.RoomState room) {
        UUID me = me();
        if (me == null) return Stone.EMPTY;
        if (room.host() != null && me.equals(room.host().id())) return room.host().color();
        if (room.guest() != null && me.equals(room.guest().id())) return room.guest().color();
        return Stone.EMPTY;
    }

    private boolean isMe(UUID id) {
        UUID me = me();
        return me != null && me.equals(id);
    }

    private UUID me() {
        return this.minecraft != null && this.minecraft.player != null ? this.minecraft.player.getUUID() : null;
    }

    // ------------------------------------------------------------------
    // 错误 toast（结果 / 执色文案已下沉到共享的 GoText，两类不再各留一份）
    // ------------------------------------------------------------------

    private void pollError() {
        String e = GoClientCache.consumeError();
        if (e != null) { errorKey = e; errorUntilMs = System.currentTimeMillis() + ERROR_MS; }
    }

    private static String tr(String key) { return I18n.get(key); }
    private static String tr(String key, Object... args) { return I18n.get(key, args); }
}
