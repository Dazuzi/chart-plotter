package com.chartplotter.route;
import com.chartplotter.collision.ChartPlotterCollisionData;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
final class ChartPlotterRouteTerrain {
	static final int MAX_AREA = 8 << 20;
	static final int MAX_COST = Integer.MAX_VALUE / 4;
	private final ChartPlotterCollisionData data;
	boolean limited;
	final ChartPlotterRouteMotion motion;
	final int[][] traversed = new int[16][];
	final int minX;
	final int minY;
	final int width;
	final int height;
	final byte[] clearance;
	final int[] distance;
	final int[] delta = new int[16];
	private ChartPlotterRouteTerrain(ChartPlotterCollisionData data, ChartPlotterRouteMotion motion, int minX, int minY, int width, int height, BooleanSupplier cancel) {
		this.data = data;
		this.motion = motion;
		this.minX = minX;
		this.minY = minY;
		this.width = width;
		this.height = height;
		clearance = new byte[width * height];
		distance = new int[clearance.length];
		for (int d = 0; d < 16; d++) {
			delta[d] = motion.x[d] + motion.y[d] * width;
			traversed[d] = new int[motion.cells[d].length];
			for (int i = 0; i < traversed[d].length; i++) traversed[d][i] = (motion.cells[d][i] >> 16) + (short) motion.cells[d][i] * width;
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
				clearance[x + (bit & 7) + (y + (bit >>> 3)) * width] = 1;
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
			for (int a = (y + 1) * width - 2, end = y * width; a > end; a--) clearance[a] = (byte) Math.min(clearance[a], 1 + Math.min(Math.min(clearance[a + 1], clearance[a + width]), Math.min(clearance[a + width - 1], clearance[a + width + 1])));
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
	boolean lowerBound(int gx, int gy, int radius, int[] distance, ChartPlotterRouteHull hull, BooleanSupplier cancel) {
		Arrays.fill(distance, 0);
		int step = 0;
		for (int cost : motion.cost) step = Math.max(step, cost);
		ChartPlotterPathQueue heap = new ChartPlotterPathQueue(distance, step);
		for (int y = gy - radius; y <= gy + radius; y++) for (int x = gx - radius; x <= gx + radius; x++) {
			int a = at(x, y);
			if (a < 0 || !data.clear(x + hull.offsetX, y + hull.offsetY, gx + 0.5, gy + 0.5)) continue;
			for (ChartPlotterRouteHull.Mask pose : hull.hull) {
				if (!pose.clear(this, a)) continue;
				distance[a] = 1;
				heap.add(a, 0);
				break;
			}
		}
		byte[] core = new byte[clearance.length];
		int visited = 0;
		while (heap.size > 0) {
			if ((visited++ & 255) == 0 && cancel.getAsBoolean()) return false;
			int a = heap.poll();
			int x = a % width;
			int y = a / width;
			for (int d = 0; d < 16; d++) {
				int nx = x + motion.x[d];
				int ny = y + motion.y[d];
				if (nx <= 0 || ny <= 0 || nx >= width - 1 || ny >= height - 1) continue;
				int b = a + delta[d];
				int cost = distance[a] + motion.cost[d];
				if (cost > MAX_COST) {limited = true; continue;}
				if (distance[b] != 0 && cost >= distance[b] || pointMoveBlocked(a, d)) continue;
				if (core[b] == 0) core[b] = hull.core.clear(this, b) ? (byte) 1 : 2;
				if (core[b] != 1) continue;
				int previous = distance[b];
				distance[b] = cost;
				heap.add(b, previous);
			}
		}
		return true;
	}
	boolean pointMoveBlocked(int a, int d) {
		int b = a + delta[d];
		if (b < 0 || b >= clearance.length || clearance[a] == 0 || clearance[b] == 0) return true;
		if (clearance[a] > motion.radius) return false;
		for (int offset : traversed[d]) if (clearance[a + offset] == 0) return true;
		return false;
	}
}
