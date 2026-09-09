package com.chartplotter.route;

import com.chartplotter.collision.ChartPlotterCollisionData;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

final class ChartPlotterRouteTerrain {
	static final int MAX_AREA = 8 << 20;
	static final int MAX_COST = Integer.MAX_VALUE / 4;
	boolean limited;
	final ChartPlotterRouteMotion motion;
	final int minX;
	final int minY;
	final int width;
	final int height;
	final byte[] clearance;
	final long[] open;
	final int[] delta = new int[16];
	private ChartPlotterRouteTerrain(ChartPlotterCollisionData data, ChartPlotterRouteMotion motion, int minX, int minY, int width, int height, BooleanSupplier cancel) {
		this.motion = motion;
		this.minX = minX;
		this.minY = minY;
		this.width = width;
		this.height = height;
		clearance = new byte[width * height];
		open = new long[(clearance.length + 63) / 64 + 1];
		for (int d = 0; d < 16; d++) {
			delta[d] = motion.x[d] + motion.y[d] * width;
		}
		for (int i = 0; i < data.capacity(); i++) {
			if ((i & 255) == 0 && cancel.getAsBoolean()) return;
			ChartPlotterCollisionData.Chunk chunk = data.chunkAt(i);
			if (chunk == null) continue;
			long key = data.keyAt(i);
			int x = ((int) (key >> 32) << 3) - minX;
			int y = ((int) key << 3) - minY;
			if (x < 1 || y < 1 || x + 7 >= width - 1 || y + 7 >= height - 1) continue;
			for (long bits = chunk.known & ~chunk.blocked; bits != 0; bits &= bits - 1) {
				int bit = Long.numberOfTrailingZeros(bits);
				int a = x + (bit & 7) + (y + (bit >>> 3)) * width;
				clearance[a] = 1;
				open[a >>> 6] |= 1L << a;
			}
		}
		for (int y = 1; y < height - 1; y++) {
			if ((y & 31) == 0 && cancel.getAsBoolean()) return;
			for (int a = y * width + 1, end = (y + 1) * width - 1; a < end; a++) {
				if (clearance[a] != 0) clearance[a] = (byte) Math.min(100, 1 + Math.min(Math.min(clearance[a - 1], clearance[a - width]), Math.min(clearance[a - width - 1], clearance[a - width + 1])));
			}
		}
		for (int y = height - 2; y > 0; y--) {
			if ((y & 31) == 0 && cancel.getAsBoolean()) return;
			for (int a = (y + 1) * width - 2, end = y * width; a > end; a--) if (clearance[a] > 1) clearance[a] = (byte) Math.min(clearance[a], 1 + Math.min(Math.min(clearance[a + 1], clearance[a + width]), Math.min(clearance[a + width - 1], clearance[a + width + 1])));
		}
	}
	static ChartPlotterRouteTerrain create(ChartPlotterCollisionData data, ChartPlotterRouteMotion motion, int sx, int sy, BooleanSupplier cancel) {
		if (cancel.getAsBoolean() || data.flagAt(sx, sy) != ChartPlotterCollisionData.OPEN) return null;
		LongIntMap seen = new LongIntMap(Math.max(16, data.size() * 2));
		long[] queue = new long[data.size()];
		queue[0] = ChartPlotterCollisionData.key(sx >> 3, sy >> 3);
		seen.put(queue[0], 1);
		int count = 1;
		int minX = sx >> 3;
		int minY = sy >> 3;
		int maxX = minX;
		int maxY = minY;
		for (int i = 0; i < count; i++) {
			if ((i & 255) == 0 && cancel.getAsBoolean()) return null;
			int x = (int) (queue[i] >> 32);
			int y = (int) queue[i];
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
				if (dx == 0 && dy == 0) continue;
				long key = ChartPlotterCollisionData.key(x + dx, y + dy);
				if (seen.get(key) != LongIntMap.MISS) continue;
				ChartPlotterCollisionData.Chunk chunk = data.chunk(x + dx, y + dy);
				if (chunk == null || (chunk.known & ~chunk.blocked) == 0) continue;
				seen.put(key, 1);
				queue[count++] = key;
			}
		}
		long width = ((long) maxX - minX + 1) * 8 + 2;
		long height = ((long) maxY - minY + 1) * 8 + 2;
		if (width > MAX_AREA || height > MAX_AREA || width * height > MAX_AREA) return null;
		return new ChartPlotterRouteTerrain(data, motion, minX * 8 - 1, minY * 8 - 1, (int) width, (int) height, cancel);
	}
	static boolean connected(ChartPlotterCollisionData data, int sx, int sy, int tx, int ty, int radius, BooleanSupplier cancel) {
		if (data.flagAt(sx, sy) != ChartPlotterCollisionData.OPEN) return false;
		LongIntMap seen = new LongIntMap(256);
		long[] keys = new long[Math.min(128, data.size())];
		long[] reached = new long[keys.length];
		boolean[] queued = new boolean[keys.length];
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		keys[0] = ChartPlotterCollisionData.key(sx >> 3, sy >> 3);
		reached[0] = 1L << ((sx & 7) + ((sy & 7) << 3));
		seen.put(keys[0], 0);
		queued[0] = true;
		queue.add(0);
		int count = 1;
		int visited = 0;
		while (!queue.isEmpty()) {
			if ((visited++ & 255) == 0 && cancel.getAsBoolean()) return false;
			int a = queue.remove();
			queued[a] = false;
			int x = (int) (keys[a] >> 32);
			int y = (int) keys[a];
			ChartPlotterCollisionData.Chunk current = data.chunk(x, y);
			if (current == null) continue;
			long open = current.known & ~current.blocked;
			long bits = reached[a];
			while (true) {
				long next = (bits | (bits & 0xfefefefefefefefeL) >>> 1 | (bits & 0x7f7f7f7f7f7f7f7fL) << 1 | bits >>> 8 | bits << 8) & open;
				if (next == bits) break;
				bits = next;
			}
			reached[a] = bits;
			if (x >= (tx - radius >> 3) && x <= (tx + radius >> 3) && y >= (ty - radius >> 3) && y <= (ty + radius >> 3)) {
				for (long candidates = bits; candidates != 0; candidates &= candidates - 1) {
					int bit = Long.numberOfTrailingZeros(candidates);
					if (Math.abs((x << 3) + (bit & 7) - tx) <= radius && Math.abs((y << 3) + (bit >>> 3) - ty) <= radius) return true;
				}
			}
			for (int d = 0; d < 4; d++) {
				int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
				int ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
				long key = ChartPlotterCollisionData.key(nx, ny);
				long edge = d == 0 ? bits >>> 7 & 0x0101010101010101L : d == 1 ? bits << 7 & 0x8080808080808080L : d == 2 ? bits >>> 56 : bits << 56;
				if (edge == 0) continue;
				ChartPlotterCollisionData.Chunk chunk = data.chunk(nx, ny);
				if (chunk == null) continue;
				edge &= chunk.known & ~chunk.blocked;
				if (edge == 0) continue;
				int b = seen.get(key);
				if (b == LongIntMap.MISS) {
					if (count == keys.length) {
						int capacity = Math.min(data.size(), count * 2);
						keys = Arrays.copyOf(keys, capacity);
						reached = Arrays.copyOf(reached, capacity);
						queued = Arrays.copyOf(queued, capacity);
					}
					b = count++;
					keys[b] = key;
					seen.put(key, b);
				}
				if ((edge & ~reached[b]) == 0) continue;
				reached[b] |= edge;
				if (!queued[b]) {queue.add(b); queued[b] = true;}
			}
		}
		return false;
	}
	int at(int x, int y) {
		x -= minX;
		y -= minY;
		return x < 0 || y < 0 || x >= width || y >= height ? -1 : x + y * width;
	}
}
