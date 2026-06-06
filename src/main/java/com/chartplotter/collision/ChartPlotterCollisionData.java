package com.chartplotter.collision;

import net.runelite.api.CollisionDataFlag;

import java.util.Map;

public final class ChartPlotterCollisionData {
	public static final int UNKNOWN = -1;
	public static final int OPEN = 0;
	public static final int BLOCKED = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
	public static final int VOID = 0xffffff;
	public static final int MOVE = CollisionDataFlag.BLOCK_MOVEMENT_FULL | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST | CollisionDataFlag.BLOCK_MOVEMENT_NORTH | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST | CollisionDataFlag.BLOCK_MOVEMENT_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST | CollisionDataFlag.BLOCK_MOVEMENT_WEST | CollisionDataFlag.BLOCK_MOVEMENT_OBJECT | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR;
	public final Map<Long, Chunk> base;
	public final long rev;
	public ChartPlotterCollisionData(Map<Long, Chunk> base) {
		this(base, 0);
	}
	public ChartPlotterCollisionData(Map<Long, Chunk> base, long rev) {
		this.base = base;
		this.rev = rev;
	}
	public Chunk chunk(int x, int y) {
		return base.get(key(x, y));
	}
	public int flagAt(int x, int y) {
		Chunk c = chunk(x >> 3, y >> 3);
		return c == null ? UNKNOWN : c.flag((x & 7) + ((y & 7) << 3));
	}
	public boolean uncached(int x, int y) {
		Chunk c = chunk(x, y);
		return c == null || c.empty();
	}
	public int size() {return base.size();}
	public Iterable<Map.Entry<Long, Chunk>> entries() {return base.entrySet();}
	public static long key(int x, int y) {return (long) x << 32 ^ y & 0xffffffffL;}
	public static final class Chunk {
		public final long known;
		public final long blocked;
		public Chunk(long known, long blocked) {
			this.known = known;
			this.blocked = blocked;
		}
		public int flag(int i) {
			long b = 1L << i;
			return (known & b) == 0 ? UNKNOWN : (blocked & b) == 0 ? OPEN : BLOCKED;
		}
		public boolean empty() {return known == 0;}
	}
}
