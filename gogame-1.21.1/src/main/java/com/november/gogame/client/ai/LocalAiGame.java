package com.november.gogame.client.ai;

import com.november.gogame.common.game.GameResult;
import com.november.gogame.common.game.GameRoom;
import com.november.gogame.common.net.GoClientCache;
import com.november.gogame.common.net.GoPayloads;
import com.november.gogame.common.rules.Board;
import com.november.gogame.common.rules.GoRules;
import com.november.gogame.common.rules.Move;
import com.november.gogame.common.rules.Scoring;
import com.november.gogame.common.rules.Stone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * PVE 本地对局控制器：在客户端跑与服务端 PVP <b>同一套</b>规则引擎（{@link GoRules}），
 * 把每手结果<b>本地馈送</b>进 {@link GoClientCache}——大厅页 / 棋盘界面照常从缓存读，
 * 一行渲染代码都不用为 PVE 另写（用户锁定方案「本地馈送复用现有界面」）。
 *
 * <p><b>与 PVP 的互斥</b>：PVE 期间 {@code GoClientCache.getRoom() != null}（roomId="PVE"），
 * 大厅据此隐藏联机入口；反之联机在房间中时大厅不显示 AI 入口。退出 PVE 走
 * {@link #leave()} → {@link GoClientCache#clear()}，与退出世界同一清理路径。
 *
 * <p><b>线程模型</b>（实施计划 Phase 4 铁律）：本类全部方法都在客户端主线程跑
 * （UI 事件调 start/play*，{@code ClientTickEvent.Post} 调 tick）；HTTP 线程只通过
 * {@link AiTurn} 的 volatile 字段交接，绝不直接碰这里的棋盘 / 缓存 / I18n。
 *
 * <p><b>AI 着法三重防线</b>：解析（{@link MoveParser} 三层宽容）→ 规则校验
 * （{@link GoRules#play} 非法则带反馈重试，至多 {@link #MAX_RETRIES} 次）→ 随机合法着兜底
 * （{@link GoRules#legalMoves}，无合法着则 pass）。任何一层失败棋局都不会卡死；
 * 每次兜底前用 {@code gogame.ai.error.fallback} 提示玩家。
 */
public final class LocalAiGame {

    private LocalAiGame() {}

    /** AI 的固定 UUID（玩家档案栏要一个 id；nameUUIDFromBytes 保证跨启动一致） */
    private static final UUID AI_ID =
            UUID.nameUUIDFromBytes("gogame-ai".getBytes(StandardCharsets.UTF_8));

    /** PVE 房间号（缓存快照用，界面上无处显示，取个一眼可辨的） */
    private static final String PVE_ROOM_ID = "PVE";

    /** AI 非法/解析失败时带反馈重试的次数上限，超过走随机兜底（实施计划定值） */
    private static final int MAX_RETRIES = 3;

    private static final Random RANDOM = new Random();

    // ——— 对局状态（仅主线程读写）———

    private static boolean active;
    private static Board board;
    private static Stone turn;
    private static int koIndex;
    private static int passCount;
    private static int capturedByBlack;
    private static int capturedByWhite;
    private static int moveNumber;
    private static final List<Move> history = new ArrayList<>();

    /** 玩家执色（黑先）；AI 执另一色 */
    private static Stone playerColor = Stone.BLACK;
    private static AiDifficulty difficulty = AiDifficulty.AVERAGE;

    /** 进行中的 AI 请求；null = AI 没在思考 */
    private static AiTurn aiTurn;
    /** 当前这手 AI 已重试次数（落子成功即清零） */
    private static int retries;
    /** AI 空回复连续静默计数；满 {@link #SILENT_EMPTY_STREAK} 汇总弹一次兜底提示（审查 W3） */
    private static int silentEmptyStreak;
    private static final int SILENT_EMPTY_STREAK = 5;
    /** 上一次 AI 回复原文（重试反馈用） */
    private static String lastAiReply = "";

    private static boolean finished;
    private static GameResult result;

    // ------------------------------------------------------------------
    // 开局 / 退出（大厅页 AI 视图调用）
    // ------------------------------------------------------------------

    /**
     * 开一局 PVE。
     *
     * @param diff    难度档
     * @param chosen  玩家执色；{@link Stone#EMPTY} = 随机
     */
    public static void start(AiDifficulty diff, Stone chosen) {
        difficulty = diff == null ? AiDifficulty.AVERAGE : diff;
        playerColor = chosen.isStone() ? chosen
                : (RANDOM.nextBoolean() ? Stone.BLACK : Stone.WHITE);

        active = true;
        finished = false;
        result = null;
        board = new Board();
        turn = Stone.BLACK; // 黑先
        koIndex = GoRules.NO_KO;
        passCount = 0;
        capturedByBlack = 0;
        capturedByWhite = 0;
        moveNumber = 0;
        history.clear();
        aiTurn = null;
        retries = 0;
        silentEmptyStreak = 0;
        lastAiReply = "";

        feed();
        advance();
    }

    /** 退出 PVE（对局中或终局后都走这里），清缓存回大厅 */
    public static void leave() {
        active = false;
        finished = false;
        aiTurn = null;
        GoClientCache.clear();
    }

    // ------------------------------------------------------------------
    // 玩家着法（棋盘界面调用，替代 PVP 的 sendToServer）
    // ------------------------------------------------------------------

    /** 玩家在 (x,y) 落子；非法时错误键已馈送进缓存 toast，界面不用管返回值 */
    public static void playPlace(int x, int y) {
        play(Move.place(playerColor, x, y));
    }

    /** 玩家 pass / resign（棋盘界面底部按钮） */
    public static void playSpecial(Move.Kind kind) {
        play(new Move(playerColor, -1, -1, kind));
    }

    private static void play(Move move) {
        if (!active || finished) {
            GoClientCache.localError("gogame.error.finished");
            return;
        }
        if (turn != playerColor) {
            GoClientCache.localError("gogame.error.not_your_turn");
            return;
        }
        apply(move, true);
    }

    // ------------------------------------------------------------------
    // 落子推演（玩家与 AI 共用；与服务端 GameRoom.applyMove 同一套语义）
    // ------------------------------------------------------------------

    /**
     * 把一手棋作用于本地对局：校验 → 更新状态 → 终局判定 → 馈送 → 轮到 AI 则发请求。
     *
     * @param fromPlayer true = 玩家的手（非法时直接馈送错误 toast）；
     *                   false = AI 的手（非法时返回错误键，由 tick 决定重试还是兜底）
     * @return 非法原因键；合法返回 {@code null}
     */
    private static String apply(Move move, boolean fromPlayer) {
        if (move.isResign()) {
            finish(GameResult.byResign(move.color().opponent()));
            return null;
        }

        GoRules.Outcome out = GoRules.play(board, move, koIndex);
        if (!out.legal()) {
            if (fromPlayer) GoClientCache.localError(out.reasonKey());
            return out.reasonKey();
        }

        board = out.board();
        koIndex = out.koIndex();
        passCount = move.isPass() ? passCount + 1 : 0;
        capturedByBlack += move.color() == Stone.BLACK ? out.captured() : 0;
        capturedByWhite += move.color() == Stone.WHITE ? out.captured() : 0;
        history.add(move);
        moveNumber++;

        if (passCount >= 2) {              // 双方连续虚着 → 数子终局；turn 不再翻，与服务端 applyMove 一致（W2）
            finish(GameResult.byScore(Scoring.score(board)));
            return null;
        }

        turn = turn.opponent();
        feed();
        advance();
        return null;
    }

    /** 一手棋落定后的推进：先替零合法着的一方代虚着，再看是否轮到 AI（aiTurn==null 守卫防重复请求，S1） */
    private static void advance() {
        autoPassStuck();
        if (!finished && turn == aiColor() && aiTurn == null) requestAi(null);
    }

    /**
     * 轮次方零合法着（空点全是自杀点之类）时替他虚着一手（带 toast），免掉「唯一合法着是虚着
     * 却没人按」的死局；连续两次后走数子终局。与服务端 {@link GameRoom#autoPassIfStuck} 同一语义。
     *
     * <p><b>直接推进本地状态、不经 {@link #apply}</b>：若走 apply，会 apply→advance→autoPassStuck
     * 递归回环，并在回环里 requestAi，把 tick 刚发出的 AI 请求覆盖/清空成新的死局（code-review S1）。
     * 顺带的好处——AI 自己零合法着时这里直接代虚着，不再白发 LLM 请求等它兜底 pass。
     */
    private static void autoPassStuck() {
        boolean passed = false;
        while (active && !finished && !GoRules.hasLegalMove(board, turn, koIndex)) {
            GoClientCache.localError(
                    turn == Stone.BLACK ? "gogame.autopass.black" : "gogame.autopass.white");
            history.add(Move.pass(turn));
            moveNumber++;
            koIndex = GoRules.NO_KO;       // 虚着清劫，与 GoRules.play(pass) 语义一致
            passCount++;
            passed = true;
            if (passCount >= 2) {          // 双方连续虚着 → 数子终局（turn 不再翻，与 apply 一致）
                finish(GameResult.byScore(Scoring.score(board)));
                break;
            }
            turn = turn.opponent();
        }
        if (passed && !finished) feed();   // 代虚着改了轮次，馈送刷新界面（终局由 finish 自己馈送）
    }

    private static void finish(GameResult r) {
        finished = true;
        result = r;
        feed();
        GoClientCache.feedLocalResult(r);
    }

    // ------------------------------------------------------------------
    // AI 回合
    // ------------------------------------------------------------------

    /** 发一次 AI 请求。retryReason != null 时把上次非法/解析失败的反馈拼进 user prompt */
    private static void requestAi(String retryReason) {
        String user = GoPromptBuilder.user(board, history, turn, koIndex, moveNumber);
        if (retryReason != null) {
            user = GoPromptBuilder.retry(user, lastAiReply, retryReason);
        }
        aiTurn = GoAiClient.send(GoPromptBuilder.system(difficulty, aiColor()), user,
                difficulty.temperature());
    }

    /**
     * 每客户端 tick 推进 AI 回合（{@code GoGameClient} 挂 {@code ClientTickEvent.Post}）。
     * 只读 {@link AiTurn} 的 volatile 状态——HTTP 线程与主线程唯一的接触面。
     */
    public static void tick() {
        if (!active || finished) return;
        AiTurn t = aiTurn;
        if (t == null || t.isRunning()) return;

        // 请求本身失败（网络/HTTP/密钥）：提示后直接兜底，不重试——错误多半不会自愈，
        // 让玩家看到原因、棋局继续走
        if (t.state() == AiTurn.State.ERROR) {
            // 空回复不每手弹红框（用户要求）：模型侧内部状况，日志已带原文 warn；但连续静默满
            // SILENT_EMPTY_STREAK 手汇总弹一次兜底提示，免得玩家干看 AI 乱下不知因（审查 W3）
            if (GoAiClient.ERROR_KEY_EMPTY.equals(t.errorKey())) {
                if (++silentEmptyStreak >= SILENT_EMPTY_STREAK) {
                    GoClientCache.localError("gogame.ai.error.fallback");
                    silentEmptyStreak = 0;
                }
            } else {
                silentEmptyStreak = 0;
                GoClientCache.localError(t.errorKey());
            }
            fallback();
            return;
        }

        lastAiReply = t.content();
        Move move = MoveParser.parse(lastAiReply, aiColor());

        if (move == null) {
            if (retries < MAX_RETRIES) {
                retries++;
                requestAi("unparseable");
                return;
            }
            GoClientCache.localError("gogame.ai.error.fallback");
            fallback();
            return;
        }

        // 失误扰动（菜鸟/普通人档）：按难度概率无视 LLM 着法改落随机合法着
        if (RANDOM.nextDouble() < difficulty.blunderRate()) {
            fallback();
            return;
        }

        // S1：先清掉已完成的旧 AiTurn 再 apply。apply 内 advance 可能因「对方零合法着自动代虚着」
        // 把轮次转回 AI 而发出新请求；若像旧写法那样等 apply 返回后才清空，就会抹掉这个新请求 →
        // 轮次停在 AI 却无请求在跑，对局永久卡死（正是本改动要消灭的死局换了副面孔）。
        aiTurn = null;
        String reason = apply(move, false);
        if (reason != null) {
            if (retries < MAX_RETRIES) {
                retries++;
                requestAi(reason);
                return;
            }
            GoClientCache.localError("gogame.ai.error.fallback");
            fallback();
            return;
        }
        retries = 0;
        silentEmptyStreak = 0;
    }

    /** 随机合法着兜底：无处可落则 pass。棋局在任何 AI 故障下都不卡死 */
    private static void fallback() {
        List<Move> moves = GoRules.legalMoves(board, aiColor(), koIndex);
        Move m = moves.isEmpty() ? Move.pass(aiColor()) : moves.get(RANDOM.nextInt(moves.size()));
        retries = 0;
        aiTurn = null;
        apply(m, false);
    }

    // ------------------------------------------------------------------
    // 馈送：把本地状态写进 GoClientCache，界面照常渲染
    // ------------------------------------------------------------------

    /**
     * 组一份与 S2C 同构的 {@link GoPayloads.RoomState} 写进缓存。
     * 玩家恒坐 host 位、AI 恒坐 guest 位（界面按 host/guest 显示双方档案，与执色无关）；
     * AI 名字取难度档译文——只在主线程调本方法，I18n 安全（PITFALLS E5）。
     */
    private static void feed() {
        Minecraft mc = Minecraft.getInstance();
        UUID myId = mc.player != null ? mc.player.getUUID() : AI_ID;
        String myName = mc.player != null ? mc.player.getName().getString() : "Player";

        GoPayloads.PlayerView host = new GoPayloads.PlayerView(myId, myName, playerColor, true);
        GoPayloads.PlayerView guest = new GoPayloads.PlayerView(
                AI_ID, I18n.get(difficulty.langKey()), aiColor(), true);

        GoClientCache.feedLocalRoom(new GoPayloads.RoomState(
                PVE_ROOM_ID,
                finished ? GameRoom.Phase.FINISHED : GameRoom.Phase.PLAYING,
                board, turn, koIndex, passCount, capturedByBlack, capturedByWhite, moveNumber,
                GameRoom.ColorMode.HOST_PICKS, host, guest));
    }

    // ------------------------------------------------------------------
    // 读（大厅页 / 棋盘界面 / tick 监听）
    // ------------------------------------------------------------------

    /** 是否有 PVE 对局在进行（含终局展示中）——棋盘界面据此把着法发本地而非服务端 */
    public static boolean active() { return active; }

    /** AI 正在思考（界面显示提示行） */
    public static boolean thinking() {
        return active && !finished && aiTurn != null && aiTurn.isRunning();
    }

    public static Stone playerColor() { return playerColor; }

    public static Stone aiColor() { return playerColor.opponent(); }

    public static AiDifficulty difficulty() { return difficulty; }
}
