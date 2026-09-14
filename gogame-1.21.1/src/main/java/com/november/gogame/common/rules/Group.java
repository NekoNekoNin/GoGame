package com.november.gogame.common.rules;

import java.util.Set;

/**
 * 一个连通块（同色、正交相连的一串子）及其气。
 *
 * stones / liberties 都用 Board 的一维下标（y*SIZE+x）。liberties 是"相邻空点集合"
 * 去重后的结果——同一空点被块内多个子共享只算一气，故 {@link #libertyCount()} 即真实气数。
 * 由 {@link Board#groupAt(int, int)} 计算产出。
 */
public record Group(Stone color, Set<Integer> stones, Set<Integer> liberties) {

    /** 气数（去重后的相邻空点数） */
    public int libertyCount() { return liberties.size(); }

    /** 块内子数 */
    public int size() { return stones.size(); }

    /** 无气即死，可被提 */
    public boolean isDead() { return liberties.isEmpty(); }
}
