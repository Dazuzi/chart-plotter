package com.chartplotter.route;

import com.chartplotter.collision.ChartPlotterCollisionData;

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
	int at(int x, int y) {
		x -= minX;
		y -= minY;
		return x < 0 || y < 0 || x >= width || y >= height ? -1 : x + y * width;
	}
}
