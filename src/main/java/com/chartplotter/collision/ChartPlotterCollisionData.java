package com.chartplotter.collision;

import net.runelite.api.CollisionDataFlag;

import java.util.Map;

public final class ChartPlotterCollisionData {
	public static final int UNKNOWN = -1;
	public static final int OPEN = 0;
	public static final int BLOCKED = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
	public static final int VOID = 0xffffff;
	public static final int MOVE = CollisionDataFlag.BLOCK_MOVEMENT_FULL | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST | CollisionDataFlag.BLOCK_MOVEMENT_NORTH | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST | CollisionDataFlag.BLOCK_MOVEMENT_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST | CollisionDataFlag.BLOCK_MOVEMENT_WEST | CollisionDataFlag.BLOCK_MOVEMENT_OBJECT | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR;
	private final long[] keys;
	private final Chunk[] chunks;
	private final int mask;
	private final int size;
	public final long rev;
	public ChartPlotterCollisionData(Map<Long, Chunk> base) {
		this(base, 0);
	}
	public ChartPlotterCollisionData(Map<Long, Chunk> base, long rev) {
		int capacity = 1;
		while (capacity < base.size() * 2) capacity <<= 1;
		keys = new long[capacity];
		chunks = new Chunk[capacity];
		mask = capacity - 1;
		int size = 0;
		for (Map.Entry<Long, Chunk> entry : base.entrySet()) {
			if (entry.getValue() == null || entry.getValue().empty()) continue;
			put(entry.getKey(), entry.getValue());
			size++;
		}
		this.size = size;
		this.rev = rev;
	}
	public Chunk chunk(int x, int y) {
		long key = key(x, y);
		int i = hash(key) & mask;
		while (chunks[i] != null) {
			if (keys[i] == key) return chunks[i];
			i = i + 1 & mask;
		}
		return null;
	}
	public int flagAt(int x, int y) {
		Chunk c = chunk(x >> 3, y >> 3);
		return c == null ? UNKNOWN : c.flag((x & 7) + ((y & 7) << 3));
	}
	public boolean uncached(int x, int y) {
		Chunk c = chunk(x, y);
		return c == null || c.empty();
	}
	public int size() {return size;}
	public int capacity() {return chunks.length;}
	public long keyAt(int i) {return keys[i];}
	public Chunk chunkAt(int i) {return chunks[i];}
	public static long key(int x, int y) {return (long) x << 32 ^ y & 0xffffffffL;}
	private void put(long key, Chunk chunk) {
		int i = hash(key) & mask;
		while (chunks[i] != null) i = i + 1 & mask;
		keys[i] = key;
		chunks[i] = chunk;
	}
	private static int hash(long x) {
		x += 0x9e3779b97f4a7c15L;
		x = (x ^ x >>> 30) * -4658895280553007687L;
		x = (x ^ x >>> 27) * -7723592293110705685L;
		return (int) (x ^ x >>> 31);
	}
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
