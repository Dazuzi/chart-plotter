package com.chartplotter.collision;

import net.runelite.api.CollisionDataFlag;

import java.util.Map;
import java.util.Set;

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
	public final long scene;
	private final Set<Long> pending;
	public ChartPlotterCollisionData(Map<Long, Chunk> base) {
		this(base, 0);
	}
	public ChartPlotterCollisionData(Map<Long, Chunk> base, long rev) {
		this(base, rev, 0);
	}
	public ChartPlotterCollisionData(Map<Long, Chunk> base, long rev, long scene) {
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
		this.scene = scene;
		pending = Set.of();
	}
	ChartPlotterCollisionData(ChartPlotterCollisionData base, Set<Long> pending, long rev, long scene) {
		keys = base.keys;
		chunks = base.chunks;
		mask = base.mask;
		size = base.size;
		this.pending = Set.copyOf(pending);
		this.rev = rev;
		this.scene = scene;
	}
	ChartPlotterCollisionData(ChartPlotterCollisionData base, Map<Long, Chunk> changes) {
		int size = base.size;
		for (Map.Entry<Long, Chunk> entry : changes.entrySet()) {
			long key = entry.getKey();
			if (base.chunks[base.index(key)] != null) size--;
			if (entry.getValue() != null && !entry.getValue().empty()) size++;
		}
		int capacity = base.chunks.length;
		while (capacity < size * 2) capacity <<= 1;
		mask = capacity - 1;
		keys = capacity == base.keys.length ? base.keys.clone() : new long[capacity];
		chunks = capacity == base.chunks.length ? base.chunks.clone() : new Chunk[capacity];
		if (capacity != base.chunks.length) for (int i = 0; i < base.chunks.length; i++) if (base.chunks[i] != null) put(base.keys[i], base.chunks[i]);
		for (Map.Entry<Long, Chunk> entry : changes.entrySet()) {
			if (entry.getValue() != null && !entry.getValue().empty()) continue;
			int i = index(entry.getKey());
			if (chunks[i] == null) continue;
			chunks[i] = null;
			for (i = i + 1 & mask; chunks[i] != null; i = i + 1 & mask) {
				Chunk chunk = chunks[i];
				chunks[i] = null;
				put(keys[i], chunk);
			}
		}
		for (Map.Entry<Long, Chunk> entry : changes.entrySet()) if (entry.getValue() != null && !entry.getValue().empty()) put(entry.getKey(), entry.getValue());
		this.size = size;
		rev = base.rev;
		scene = base.scene;
		pending = Set.of();
	}
	public Chunk chunk(int x, int y) {
		long key = key(x, y);
		if (!pending.isEmpty() && pending.contains(key)) return null;
		return chunks[index(key)];
	}
	public int flagAt(int x, int y) {
		Chunk c = chunk(x >> 3, y >> 3);
		return c == null ? UNKNOWN : c.flag((x & 7) + ((y & 7) << 3));
	}
	public boolean clear(double ax, double ay, double bx, double by) {
		int x = (int) Math.floor(ax);
		int y = (int) Math.floor(ay);
		if (ax == x && flagAt(x - 1, y) != OPEN || ay == y && flagAt(x, y - 1) != OPEN || ax == x && ay == y && flagAt(x - 1, y - 1) != OPEN) return false;
		int dx = Double.compare(bx, ax);
		int dy = Double.compare(by, ay);
		double stepX = dx == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(bx - ax);
		double stepY = dy == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(by - ay);
		double nextX = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? x + 1 - ax : ax - x) * stepX;
		double nextY = dy == 0 ? Double.POSITIVE_INFINITY : (dy > 0 ? y + 1 - ay : ay - y) * stepY;
		while (true) {
			if (flagAt(x, y) != OPEN) return false;
			if (dx == 0 && ax == x && flagAt(x - 1, y) != OPEN || dy == 0 && ay == y && flagAt(x, y - 1) != OPEN) return false;
			if (Math.min(nextX, nextY) > 1) return true;
			if (Math.abs(nextX - nextY) <= 1e-12) {
				if (flagAt(x + dx, y) != OPEN || flagAt(x, y + dy) != OPEN) return false;
				x += dx;
				y += dy;
				nextX += stepX;
				nextY += stepY;
			} else if (nextX < nextY) {
				x += dx;
				nextX += stepX;
			} else {
				y += dy;
				nextY += stepY;
			}
		}
	}
	public boolean uncached(int x, int y) {
		Chunk c = chunk(x, y);
		return c == null || c.empty();
	}
	public int size() {return size;}
	public boolean pending() {return !pending.isEmpty();}
	public int capacity() {return chunks.length;}
	public long keyAt(int i) {return keys[i];}
	public Chunk chunkAt(int i) {return !pending.isEmpty() && pending.contains(keys[i]) ? null : chunks[i];}
	public static long key(int x, int y) {return (long) x << 32 ^ y & 0xffffffffL;}
	private void put(long key, Chunk chunk) {
		int i = index(key);
		keys[i] = key;
		chunks[i] = chunk;
	}
	private int index(long key) {
		int i = hash(key) & mask;
		while (chunks[i] != null && keys[i] != key) i = i + 1 & mask;
		return i;
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
