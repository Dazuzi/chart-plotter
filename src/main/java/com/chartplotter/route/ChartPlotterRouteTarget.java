package com.chartplotter.route;
import com.chartplotter.collision.ChartPlotterCollisionData;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
final class ChartPlotterRouteTarget {
	static final int RADIUS = 14;
	static final int LIMIT = 20000;
	private static final int SIDE = RADIUS * 2 + 1;
	private static final int[] DX = {-1, 0, 1, -1, 1, -1, 0, 1};
	private static final int[] DY = {-1, -1, -1, 0, 0, 1, 1, 1};
	final int x;
	final int y;
	private final int[] distance = new int[SIDE * SIDE];
	private final byte[] next = new byte[SIDE * SIDE];
	private final ChartPlotterCollisionData.Chunk[] chunks;
	private final int chunkX;
	private final int chunkY;
	private final int chunkWidth;
	private ChartPlotterRouteTarget(ChartPlotterCollisionData data, int x, int y) {
		this.x = x;
		this.y = y;
		chunkX = x - RADIUS >> 3;
		chunkY = y - RADIUS >> 3;
		chunkWidth = (x + RADIUS >> 3) - chunkX + 1;
		chunks = new ChartPlotterCollisionData.Chunk[chunkWidth * ((y + RADIUS >> 3) - chunkY + 1)];
		for (int i = 0; i < chunks.length; i++) chunks[i] = data.chunk(chunkX + i % chunkWidth, chunkY + i / chunkWidth);
		Arrays.fill(distance, Integer.MAX_VALUE);
	}
	static ChartPlotterRouteTarget create(ChartPlotterCollisionData data, int x, int y, BooleanSupplier cancel) {
		if (cancel.getAsBoolean() || data.flagAt(x, y) != ChartPlotterCollisionData.OPEN) return null;
		ChartPlotterRouteTarget target = new ChartPlotterRouteTarget(data, x, y);
		boolean[] open = new boolean[SIDE * SIDE];
		for (int i = 0; i < open.length; i++) open[i] = data.flagAt(x - RADIUS + i % SIDE, y - RADIUS + i / SIDE) == ChartPlotterCollisionData.OPEN;
		ChartPlotterPathHeap queue = new ChartPlotterPathHeap(target.distance, null);
		int center = RADIUS + RADIUS * SIDE;
		target.distance[center] = 0;
		queue.add(center);
		int expanded = 0;
		while (queue.size > 0) {
			if ((expanded++ & 63) == 0 && cancel.getAsBoolean()) return null;
			int a = queue.poll();
			int ax = a % SIDE;
			int ay = a / SIDE;
			for (int d = 0; d < 8; d++) {
				int bx = ax + DX[d];
				int by = ay + DY[d];
				if (bx < 0 || by < 0 || bx >= SIDE || by >= SIDE) continue;
				int b = bx + by * SIDE;
				if (!open[b] || DX[d] != 0 && DY[d] != 0 && (!open[bx + ay * SIDE] || !open[ax + by * SIDE])) continue;
				int cost = target.distance[a] + (DX[d] != 0 && DY[d] != 0 ? 1414 : 1000);
				if (cost > LIMIT || cost >= target.distance[b]) continue;
				target.distance[b] = cost;
				target.next[b] = (byte) (7 - d);
				queue.add(b);
			}
		}
		return cancel.getAsBoolean() ? null : target;
	}
	boolean contains(int x, int y) {
		x += RADIUS - this.x;
		y += RADIUS - this.y;
		return x >= 0 && y >= 0 && x < SIDE && y < SIDE && distance[x + y * SIDE] <= LIMIT;
	}
	long bytes() {return distance.length * 5L + chunks.length * 8L;}
	boolean matches(ChartPlotterCollisionData data) {
		for (int i = 0; i < chunks.length; i++) if (chunks[i] != data.chunk(chunkX + i % chunkWidth, chunkY + i / chunkWidth)) return false;
		return true;
	}
	long[] path(ChartPlotterCollisionData data, int sx, int sy) {
		if (!contains(sx, sy)) throw new IllegalArgumentException("Arrival outside destination region");
		long[] path = new long[LIMIT / 1000 + 1];
		int n = 0;
		int a = sx - x + RADIUS + (sy - y + RADIUS) * SIDE;
		while (true) {
			path[n++] = ChartPlotterCollisionData.key(x - RADIUS + a % SIDE, y - RADIUS + a / SIDE);
			if (distance[a] == 0) break;
			int d = next[a];
			a += DX[d] + DY[d] * SIDE;
		}
		int count = 1;
		for (int i = 0; i + 1 < n;) {
			int j = n - 1;
			while (j > i + 1 && !data.clear((int) (path[i] >> 32) + 0.5, (int) path[i] + 0.5, (int) (path[j] >> 32) + 0.5, (int) path[j] + 0.5)) j--;
			path[count++] = path[j];
			i = j;
		}
		return Arrays.copyOf(path, count);
	}
}
