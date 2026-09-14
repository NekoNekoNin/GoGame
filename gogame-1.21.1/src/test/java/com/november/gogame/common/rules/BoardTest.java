package com.november.gogame.common.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Board} 单测：坐标换算、邻接（角/边/中）、连通块与气、拷贝独立性、提子清空、星位、ASCII。
 */
class BoardTest {

    @Test
    @DisplayName("坐标换算：index=y*19+x，xOf/yOf 为其逆")
    void indexAndCoords() {
        assertEquals(0, Board.index(0, 0));
        assertEquals(Board.SIZE - 1, Board.index(Board.SIZE - 1, 0));
        assertEquals(Board.COUNT - 1, Board.index(18, 18));
        for (int y = 0; y < Board.SIZE; y++) {
            for (int x = 0; x < Board.SIZE; x++) {
                int i = Board.index(x, y);
                assertEquals(x, Board.xOf(i));
                assertEquals(y, Board.yOf(i));
            }
        }
    }

    @Test
    @DisplayName("inBounds：界内真，-1 与 19 假")
    void bounds() {
        assertTrue(Board.inBounds(0, 0));
        assertTrue(Board.inBounds(18, 18));
        assertFalse(Board.inBounds(-1, 0));
        assertFalse(Board.inBounds(0, 19));
        assertFalse(Board.inBounds(19, 19));
    }

    @Test
    @DisplayName("越界读写抛 IndexOutOfBoundsException（防静默绕回污染棋盘）")
    void outOfBoundsAccessThrows() {
        Board b = new Board();
        assertThrows(IndexOutOfBoundsException.class, () -> b.at(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> b.at(0, 19));
        assertThrows(IndexOutOfBoundsException.class, () -> b.at(19, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> b.set(-1, 1, Stone.BLACK));
        assertThrows(IndexOutOfBoundsException.class, () -> b.set(0, 19, Stone.BLACK));
        // 越界星位查询安全返回 false，不抛
        assertFalse(Board.isStarPoint(-1, -1));
        assertFalse(Board.isStarPoint(19, 19));
    }

    @Test
    @DisplayName("邻接数：角=2、边=3、中=4")
    void neighborCounts() {
        assertEquals(2, new Board().neighbors(0, 0).size());
        assertEquals(2, new Board().neighbors(18, 18).size());
        assertEquals(3, new Board().neighbors(0, 9).size());
        assertEquals(3, new Board().neighbors(9, 0).size());
        assertEquals(4, new Board().neighbors(9, 9).size());
        // 角 (0,0) 的邻居确为 (1,0) 与 (0,1)
        List<Integer> corner = new Board().neighbors(0, 0);
        assertTrue(corner.contains(Board.index(1, 0)));
        assertTrue(corner.contains(Board.index(0, 1)));
    }

    @Test
    @DisplayName("空盘：全 EMPTY、isEmpty、子数为 0")
    void emptyBoard() {
        Board b = new Board();
        assertTrue(b.isEmpty());
        assertEquals(0, b.countStones(Stone.BLACK));
        assertEquals(0, b.countStones(Stone.WHITE));
        assertEquals(Stone.EMPTY, b.at(9, 9));
    }

    @Test
    @DisplayName("单子气数：中心=4、角=2、边=3")
    void singleStoneLiberties() {
        Board b = new Board();
        b.set(9, 9, Stone.BLACK);
        assertEquals(4, b.groupAt(9, 9).libertyCount());
        assertEquals(1, b.groupAt(9, 9).size());

        b.set(0, 0, Stone.WHITE);
        assertEquals(2, b.groupAt(0, 0).libertyCount());

        b.set(0, 9, Stone.BLACK);
        assertEquals(3, b.groupAt(0, 9).libertyCount());
    }

    @Test
    @DisplayName("相连子并为一块，气去重：横二=6气、2×2=8气")
    void connectedGroupLiberties() {
        Board pair = new Board();
        pair.set(9, 9, Stone.BLACK);
        pair.set(10, 9, Stone.BLACK);
        Group g2 = pair.groupAt(9, 9);
        assertEquals(2, g2.size());
        assertEquals(6, g2.libertyCount());
        // 从块内任一点取到的是同一块
        assertEquals(g2.stones(), pair.groupAt(10, 9).stones());

        Board sq = new Board();
        sq.set(9, 9, Stone.WHITE);
        sq.set(10, 9, Stone.WHITE);
        sq.set(9, 10, Stone.WHITE);
        sq.set(10, 10, Stone.WHITE);
        Group g4 = sq.groupAt(10, 10);
        assertEquals(4, g4.size());
        assertEquals(8, g4.libertyCount());
    }

    @Test
    @DisplayName("空点 groupAt 返回 null")
    void groupAtEmptyIsNull() {
        assertNull(new Board().groupAt(5, 5));
    }

    @Test
    @DisplayName("被围死的块无气：isDead 为真")
    void deadGroupHasNoLiberties() {
        Board b = new Board();
        b.set(0, 0, Stone.WHITE);        // 角上白子
        b.set(1, 0, Stone.BLACK);
        b.set(0, 1, Stone.BLACK);
        Group white = b.groupAt(0, 0);
        assertEquals(0, white.libertyCount());
        assertTrue(white.isDead());
    }

    @Test
    @DisplayName("copy 独立：改副本不动原盘")
    void copyIsIndependent() {
        Board b = new Board();
        b.set(3, 3, Stone.BLACK);
        Board c = b.copy();
        assertNotSame(b, c);
        c.set(3, 3, Stone.EMPTY);
        c.set(4, 4, Stone.WHITE);
        assertEquals(Stone.BLACK, b.at(3, 3)); // 原盘未变
        assertEquals(Stone.EMPTY, b.at(4, 4));
    }

    @Test
    @DisplayName("removeAll 提掉整块，countStones 归零")
    void removeAllClearsGroup() {
        Board b = new Board();
        b.set(9, 9, Stone.BLACK);
        b.set(10, 9, Stone.BLACK);
        Group g = b.groupAt(9, 9);
        b.removeAll(g.stones());
        assertEquals(Stone.EMPTY, b.at(9, 9));
        assertEquals(Stone.EMPTY, b.at(10, 9));
        assertEquals(0, b.countStones(Stone.BLACK));
    }

    @Test
    @DisplayName("countStones 精确计数")
    void countStones() {
        Board b = new Board();
        b.set(0, 0, Stone.BLACK);
        b.set(1, 1, Stone.BLACK);
        b.set(2, 2, Stone.WHITE);
        assertEquals(2, b.countStones(Stone.BLACK));
        assertEquals(1, b.countStones(Stone.WHITE));
    }

    @Test
    @DisplayName("19 路九星位判定")
    void starPoints() {
        assertTrue(Board.isStarPoint(3, 3));
        assertTrue(Board.isStarPoint(9, 9));   // 天元
        assertTrue(Board.isStarPoint(15, 15));
        assertTrue(Board.isStarPoint(3, 15));
        assertFalse(Board.isStarPoint(0, 0));
        assertFalse(Board.isStarPoint(4, 4));
    }

    @Test
    @DisplayName("ASCII 渲染含坐标标号与子形符号")
    void asciiRender() {
        Board b = new Board();
        b.set(15, 3, Stone.BLACK);
        String s = b.toString();
        assertTrue(s.contains("A"));
        assertTrue(s.contains("T"));
        assertTrue(s.contains("19"));
        assertTrue(s.contains("●"), "应画出黑子");
        // 同一处用 at 复核确实是黑子
        assertSame(Stone.BLACK, b.at(15, 3));
    }
}
