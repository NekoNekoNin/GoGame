package com.november.gogame.common.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.november.gogame.common.game.GameResult.EndReason;
import com.november.gogame.common.game.GameRoom.ColorMode;
import com.november.gogame.common.game.GameRoom.Phase;
import com.november.gogame.common.rules.GoRules;
import com.november.gogame.common.rules.Move;
import com.november.gogame.common.rules.Stone;

import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GameRoom} 权威状态机单测（Phase 2 #12）：建房/加入定色、落子仲裁（轮次/越界/占点）、
 * 终局三路（数子/认输/掉线判负）、掉线宽限与重连（含超时硬校验 #6）、tick 判负的确定性 tie-break（#7）。
 *
 * <p>纯 Java、无 Minecraft 依赖，可脱机运行——这是"服务端权威仲裁 + 掉线判负"核心流程的回归网。
 */
class GameRoomTest {

    private static final String ROOM = "123456";
    private static final Random RND = new Random(20260914L);   // 固定种子：RANDOM 模式可复现
    private static final long T0 = 1_000_000_000L;             // 任意基准时刻（毫秒）

    private static UUID uid() { return UUID.randomUUID(); }

    /** 建一间房并把 guest 加进来，直接进入 PLAYING */
    private static GameRoom started(ColorMode mode, Stone hostColor, UUID host, UUID guest) {
        GameRoom room = new GameRoom(ROOM, host, mode, hostColor);
        assertNull(room.join(guest, RND), "加入应成功");
        assertEquals(Phase.PLAYING, room.phase());
        return room;
    }

    /** 房主执黑、客人执白的标准已开局房间（黑先，故 host 先手） */
    private static GameRoom startedHostBlack(UUID host, UUID guest) {
        return started(ColorMode.HOST_PICKS, Stone.BLACK, host, guest);
    }

    private static String place(GameRoom room, UUID who, int x, int y) {
        return room.applyMove(who, x, y, Move.Kind.PLACE);
    }
    private static String pass(GameRoom room, UUID who) {
        return room.applyMove(who, -1, -1, Move.Kind.PASS);
    }
    private static String resign(GameRoom room, UUID who) {
        return room.applyMove(who, -1, -1, Move.Kind.RESIGN);
    }

    // ------------------------------------------------------------------
    // 建房与加入定色
    // ------------------------------------------------------------------

    @Test
    @DisplayName("建房：WAITING、黑先、无客人；HOST_PICKS 房主色即刻生效")
    void newRoomIsWaitingBlackFirst() {
        UUID host = uid();
        GameRoom room = new GameRoom(ROOM, host, ColorMode.HOST_PICKS, Stone.BLACK);
        assertTrue(room.isWaiting());
        assertEquals(Stone.BLACK, room.turn());
        assertNull(room.guestId());
        assertEquals(host, room.hostId());
        assertEquals(Stone.BLACK, room.colorOf(host));
        assertEquals(0, room.moveNumber());
        assertNull(room.result());
    }

    @Test
    @DisplayName("RANDOM 建房：房主色未定（EMPTY），等人齐才随机")
    void randomModeHostColorEmptyUntilJoin() {
        UUID host = uid();
        GameRoom room = new GameRoom(ROOM, host, ColorMode.RANDOM, Stone.EMPTY);
        assertEquals(Stone.EMPTY, room.colorOf(host));   // 未定色
        assertNull(room.playerOf(Stone.BLACK));          // 尚无人执黑
    }

    @Test
    @DisplayName("HOST_PICKS 房主执黑 → 客人执白，PLAYING 黑先")
    void hostPicksBlackGuestGetsWhite() {
        UUID host = uid(), guest = uid();
        GameRoom room = started(ColorMode.HOST_PICKS, Stone.BLACK, host, guest);
        assertEquals(Stone.BLACK, room.colorOf(host));
        assertEquals(Stone.WHITE, room.colorOf(guest));
        assertEquals(Stone.BLACK, room.turn());
        assertEquals(host, room.playerOf(Stone.BLACK));
        assertEquals(guest, room.playerOf(Stone.WHITE));
    }

    @Test
    @DisplayName("HOST_PICKS 房主执白 → 客人执黑，仍黑先（此局客人先手）")
    void hostPicksWhiteGuestGetsBlack() {
        UUID host = uid(), guest = uid();
        GameRoom room = started(ColorMode.HOST_PICKS, Stone.WHITE, host, guest);
        assertEquals(Stone.WHITE, room.colorOf(host));
        assertEquals(Stone.BLACK, room.colorOf(guest));
        assertEquals(Stone.BLACK, room.turn());
    }

    @Test
    @DisplayName("RANDOM 加入：双方随机但必为一黑一白")
    void randomModeAssignsOppositeValidColors() {
        UUID host = uid(), guest = uid();
        GameRoom room = started(ColorMode.RANDOM, Stone.EMPTY, host, guest);
        Stone hc = room.colorOf(host), gc = room.colorOf(guest);
        assertTrue(hc.isStone() && gc.isStone(), "双方都已定色");
        assertEquals(hc.opponent(), gc, "颜色相反");
        assertEquals(Stone.BLACK, room.turn());
    }

    @Test
    @DisplayName("HOST_PICKS 但传 EMPTY：回退随机定色（仍一黑一白）")
    void hostPicksEmptyFallsBackToRandom() {
        UUID host = uid(), guest = uid();
        GameRoom room = started(ColorMode.HOST_PICKS, Stone.EMPTY, host, guest);
        Stone hc = room.colorOf(host), gc = room.colorOf(guest);
        assertTrue(hc.isStone() && gc.isStone());
        assertEquals(hc.opponent(), gc);
    }

    // ------------------------------------------------------------------
    // 加入的拒绝路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#2 加入已满房间（PLAYING）→ ERR_ROOM_FULL，不是 ERR_FINISHED")
    void joinFullRoomReturnsRoomFull() {
        UUID host = uid(), g1 = uid(), g2 = uid();
        GameRoom room = started(ColorMode.HOST_PICKS, Stone.BLACK, host, g1);
        assertEquals(GameRoom.ERR_ROOM_FULL, room.join(g2, RND));
    }

    @Test
    @DisplayName("#2 加入已终局房间 → ERR_FINISHED")
    void joinFinishedRoomReturnsFinished() {
        UUID host = uid(), g1 = uid(), g2 = uid();
        GameRoom room = startedHostBlack(host, g1);
        assertNotNull(room.resignBy(g1));                // 终局
        assertEquals(Phase.FINISHED, room.phase());
        assertEquals(GameRoom.ERR_FINISHED, room.join(g2, RND));
    }

    @Test
    @DisplayName("房主自加 → ERR_SELF_JOIN，房间仍 WAITING")
    void selfJoinReturnsSelfJoin() {
        UUID host = uid();
        GameRoom room = new GameRoom(ROOM, host, ColorMode.HOST_PICKS, Stone.BLACK);
        assertEquals(GameRoom.ERR_SELF_JOIN, room.join(host, RND));
        assertTrue(room.isWaiting());
        assertNull(room.guestId());
    }

    // ------------------------------------------------------------------
    // 落子仲裁
    // ------------------------------------------------------------------

    @Test
    @DisplayName("WAITING 时落子 → ERR_NOT_STARTED")
    void applyMoveBeforeStartReturnsNotStarted() {
        UUID host = uid();
        GameRoom room = new GameRoom(ROOM, host, ColorMode.HOST_PICKS, Stone.BLACK);
        assertEquals(GameRoom.ERR_NOT_STARTED, place(room, host, 3, 3));
    }

    @Test
    @DisplayName("合法落子推进轮次：黑→白→黑，盘面落子正确")
    void applyMoveAlternatesTurn() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);
        assertEquals(Stone.BLACK, room.turn());
        assertNull(place(room, host, 3, 3));             // 黑
        assertEquals(Stone.WHITE, room.turn());
        assertEquals(Stone.BLACK, room.board().at(3, 3));
        assertNull(place(room, guest, 15, 15));          // 白
        assertEquals(Stone.BLACK, room.turn());
        assertEquals(Stone.WHITE, room.board().at(15, 15));
        assertEquals(2, room.moveNumber());
    }

    @Test
    @DisplayName("非己方轮次落子 → ERR_NOT_YOUR_TURN，状态不变")
    void applyMoveWrongTurnReturnsNotYourTurn() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);   // 黑先，host=黑
        assertEquals(GameRoom.ERR_NOT_YOUR_TURN, place(room, guest, 3, 3));  // 白抢下
        assertEquals(Stone.BLACK, room.turn());
        assertEquals(Stone.EMPTY, room.board().at(3, 3));
        assertEquals(0, room.moveNumber());
    }

    @Test
    @DisplayName("非本房玩家落子 → ERR_NOT_IN_ROOM")
    void applyMoveNotInRoom() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);
        assertEquals(GameRoom.ERR_NOT_IN_ROOM, place(room, uid(), 3, 3));
    }

    @Test
    @DisplayName("越界落子 → ERR_OUT_OF_BOUNDS（不抛异常、不消耗轮次）")
    void applyMoveOutOfBounds() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);
        assertEquals(GameRoom.ERR_OUT_OF_BOUNDS, place(room, host, 19, 0));
        assertEquals(GameRoom.ERR_OUT_OF_BOUNDS, place(room, host, -1, 5));
        assertEquals(GameRoom.ERR_OUT_OF_BOUNDS, place(room, host, 0, 99));
        assertEquals(Stone.BLACK, room.turn());
        assertEquals(0, room.moveNumber());
    }

    @Test
    @DisplayName("占点落子 → GoRules 的 ERR_OCCUPIED")
    void applyMoveOccupied() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);
        assertNull(place(room, host, 3, 3));             // 黑占 (3,3)
        assertEquals(GoRules.ERR_OCCUPIED, place(room, guest, 3, 3));  // 白再占同点
    }

    @Test
    @DisplayName("提子计数：黑提白角上一子 → capturedByBlack=1，白子离盘")
    void captureIncrementsCapturedByBlack() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);   // host=黑
        // 黑(5,5) 白(0,0) 黑(1,0) 白(6,6) 黑(0,1) → 白 (0,0) 两气被堵死提走
        assertNull(place(room, host, 5, 5));
        assertNull(place(room, guest, 0, 0));
        assertNull(place(room, host, 1, 0));
        assertNull(place(room, guest, 6, 6));
        assertNull(place(room, host, 0, 1));
        assertEquals(1, room.capturedByBlack());
        assertEquals(0, room.capturedByWhite());
        assertEquals(Stone.EMPTY, room.board().at(0, 0));
    }

    // ------------------------------------------------------------------
    // 终局三路
    // ------------------------------------------------------------------

    @Test
    @DisplayName("棋盘认输：RESIGN → FINISHED，reason=RESIGN，对手胜、无数子明细")
    void resignMoveFinishesOpponentWins() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);   // host=黑
        assertNull(resign(room, host));                  // 黑认输
        assertTrue(room.isFinished());
        GameResult r = room.result();
        assertNotNull(r);
        assertEquals(EndReason.RESIGN, r.reason());
        assertTrue(r.whiteWins());                       // 白（guest）胜
        assertFalse(r.hasScore());
    }

    @Test
    @DisplayName("离场认输 resignBy：FINISHED，对手胜")
    void resignByFinishesOpponentWins() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);   // host=黑, guest=白
        GameResult r = room.resignBy(guest);             // 白离场认输
        assertNotNull(r);
        assertEquals(EndReason.RESIGN, r.reason());
        assertTrue(r.blackWins());                       // 黑胜
        assertTrue(room.isFinished());
    }

    @Test
    @DisplayName("WAITING 时 resignBy → null，不改状态")
    void resignByNonPlayingReturnsNull() {
        UUID host = uid();
        GameRoom room = new GameRoom(ROOM, host, ColorMode.HOST_PICKS, Stone.BLACK);
        assertNull(room.resignBy(host));
        assertTrue(room.isWaiting());
    }

    @Test
    @DisplayName("双方连续虚着 → 数子终局：DOUBLE_PASS、带明细；空盘贴子白胜")
    void doublePassFinishesByScore() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);
        assertNull(pass(room, host));                    // 黑 pass，passCount=1
        assertEquals(1, room.passCount());
        assertFalse(room.isFinished());
        assertNull(pass(room, guest));                   // 白 pass，passCount=2 → 数子
        assertTrue(room.isFinished());
        GameResult r = room.result();
        assertNotNull(r);
        assertEquals(EndReason.DOUBLE_PASS, r.reason());
        assertTrue(r.hasScore());
        // 空盘数子：黑 area 0、白 area 0、dame 361，margin = -3.75 → 白胜（贴子）
        assertTrue(r.whiteWins());
        assertEquals(361, r.score().dame());
    }

    @Test
    @DisplayName("虚着被落子打断：passCount 归零，不终局")
    void singlePassThenPlaceResetsPassCount() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);
        assertNull(pass(room, host));                    // 黑 pass → 1
        assertEquals(1, room.passCount());
        assertNull(place(room, guest, 3, 3));            // 白落子 → 打断
        assertEquals(0, room.passCount());
        assertFalse(room.isFinished());
        assertEquals(Stone.BLACK, room.turn());
    }

    @Test
    @DisplayName("终局后落子 → ERR_FINISHED")
    void finishedRoomRejectsMoves() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);
        assertNotNull(room.resignBy(guest));             // 终局
        assertEquals(GameRoom.ERR_FINISHED, place(room, host, 3, 3));
    }

    // ------------------------------------------------------------------
    // 掉线 / 重连 / 超时判负
    // ------------------------------------------------------------------

    @Test
    @DisplayName("掉线挂起：anyDisconnected=true，双方都不能落子（ERR_SUSPENDED）")
    void disconnectSuspendsAndBlocksMoves() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);
        room.disconnect(host, T0);
        assertTrue(room.anyDisconnected());
        assertEquals(GameRoom.ERR_SUSPENDED, place(room, host, 3, 3));   // 掉线者自己
        assertEquals(GameRoom.ERR_SUSPENDED, place(room, guest, 3, 3));  // 对手也被挂起
        assertTrue(room.isPlaying());
    }

    @Test
    @DisplayName("宽限期内重连：恢复连接，可继续落子")
    void reconnectWithinGraceRestores() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);
        room.disconnect(host, T0);
        assertTrue(room.reconnect(host, T0 + 30_000L));   // 30s < 60s 宽限
        assertFalse(room.anyDisconnected());
        assertNull(place(room, host, 3, 3));              // 恢复后黑可落子
    }

    @Test
    @DisplayName("#6 宽限期已过重连 → false，仍挂起（等 tick 判负）")
    void reconnectAfterGraceFails() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);
        room.disconnect(host, T0);
        assertFalse(room.reconnect(host, T0 + GameRoom.RECONNECT_GRACE_MS));       // 恰好到点即视为过期
        assertTrue(room.anyDisconnected());
        assertFalse(room.reconnect(host, T0 + GameRoom.RECONNECT_GRACE_MS + 1));
    }

    @Test
    @DisplayName("tick 在宽限期内 → false，对局继续")
    void tickBeforeDeadlineNoForfeit() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);
        room.disconnect(host, T0);
        assertFalse(room.tick(T0 + 30_000L));
        assertTrue(room.isPlaying());
        assertNull(room.result());
    }

    @Test
    @DisplayName("房主掉线超时 → tick 判负：DISCONNECT，客人胜")
    void tickAfterDeadlineForfeitsHost() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);   // host=黑
        room.disconnect(host, T0);
        assertTrue(room.tick(T0 + GameRoom.RECONNECT_GRACE_MS));   // 到点即判
        assertTrue(room.isFinished());
        GameResult r = room.result();
        assertEquals(EndReason.DISCONNECT, r.reason());
        assertTrue(r.whiteWins());                                  // 白（guest）胜
        assertFalse(r.hasScore());
    }

    @Test
    @DisplayName("客人掉线超时 → tick 判负：房主胜")
    void tickAfterDeadlineForfeitsGuest() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);   // guest=白
        room.disconnect(guest, T0);
        assertTrue(room.tick(T0 + GameRoom.RECONNECT_GRACE_MS));
        assertTrue(room.isFinished());
        assertTrue(room.result().blackWins());            // 黑（host）胜
    }

    @Test
    @DisplayName("#7 双方同时到期（截止相等）→ 确定性判房主负")
    void tickTieBreakEqualDeadlineForfeitsHost() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);
        room.disconnect(host, T0);
        room.disconnect(guest, T0);                       // 同一截止时刻
        assertTrue(room.tick(T0 + GameRoom.RECONNECT_GRACE_MS));
        assertTrue(room.isFinished());
        assertTrue(room.result().whiteWins());            // 判房主（黑）负 → 白胜
    }

    @Test
    @DisplayName("#7 双方都到期但客人截止更早 → 判客人负")
    void tickTieBreakEarlierDeadlineForfeitsGuest() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);
        room.disconnect(guest, T0);                       // 客人截止 = T0+GRACE
        room.disconnect(host, T0 + 10_000L);              // 房主截止 = T0+10s+GRACE（更晚）
        assertTrue(room.tick(T0 + GameRoom.RECONNECT_GRACE_MS + 20_000L));  // 两者都过期
        assertTrue(room.isFinished());
        assertTrue(room.result().blackWins());            // 客人（白）截止更早被判负 → 黑胜
    }

    // ------------------------------------------------------------------
    // 查询与历史
    // ------------------------------------------------------------------

    @Test
    @DisplayName("history 随落子增长，moveNumber 一致")
    void historyGrowsWithMoves() {
        UUID host = uid(), guest = uid();
        GameRoom room = startedHostBlack(host, guest);
        assertEquals(0, room.moveNumber());
        assertTrue(room.history().isEmpty());
        assertNull(place(room, host, 3, 3));
        assertNull(place(room, guest, 4, 4));
        assertEquals(2, room.moveNumber());
        assertEquals(2, room.history().size());
        assertEquals(Move.Kind.PLACE, room.history().get(0).kind());
    }

    @Test
    @DisplayName("contains/seatOf/colorOf 对非本房玩家的安全返回")
    void membershipQueries() {
        UUID host = uid(), guest = uid(), stranger = uid();
        GameRoom room = startedHostBlack(host, guest);
        assertTrue(room.contains(host));
        assertTrue(room.contains(guest));
        assertFalse(room.contains(stranger));
        assertNull(room.seatOf(stranger));
        assertEquals(Stone.EMPTY, room.colorOf(stranger));
        assertNotNull(room.seatOf(host));
    }
}
