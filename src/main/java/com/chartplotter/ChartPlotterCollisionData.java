package com.chartplotter;
import java.util.Map;
import net.runelite.api.CollisionDataFlag;
final class ChartPlotterCollisionData {
	static final int UNKNOWN = -1;
	static final int OPEN = 0;
	static final int BLOCKED = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
	static final int VOID = 0xffffff;
	static final int MOVE = CollisionDataFlag.BLOCK_MOVEMENT_FULL | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST | CollisionDataFlag.BLOCK_MOVEMENT_NORTH | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST | CollisionDataFlag.BLOCK_MOVEMENT_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST | CollisionDataFlag.BLOCK_MOVEMENT_WEST | CollisionDataFlag.BLOCK_MOVEMENT_OBJECT | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR;
	final Map<Long, Chunk> base;
	final long rev;
	ChartPlotterCollisionData(Map<Long, Chunk> base) {
		this(base, 0);
	}
	ChartPlotterCollisionData(Map<Long, Chunk> base, long rev) {
		this.base = base;
		this.rev = rev;
	}
	Chunk chunk(int x, int y) {
		return base.get(key(x, y));
	}
	int flagAt(int x, int y) {
		Chunk c = chunk(x >> 3, y >> 3);
		return c == null ? UNKNOWN : c.flag((x & 7) + ((y & 7) << 3));
	}
	boolean uncached(int x, int y) {
		Chunk c = chunk(x, y);
		return c == null || c.empty();
	}
	Iterable<Map.Entry<Long, Chunk>> entries() {return base.entrySet();}
	static long key(int x, int y) {return (long) x << 32 ^ y & 0xffffffffL;}
	static final class Chunk {
		final long known;
		final long blocked;
		Chunk(long known, long blocked) {
			this.known = known;
			this.blocked = blocked;
		}
		int flag(int i) {
			long b = 1L << i;
			return (known & b) == 0 ? UNKNOWN : (blocked & b) == 0 ? OPEN : BLOCKED;
		}
		boolean empty() {return known == 0;}
	}
}
