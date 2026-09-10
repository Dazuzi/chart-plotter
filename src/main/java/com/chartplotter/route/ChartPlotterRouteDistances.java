package com.chartplotter.route;
import java.util.function.BooleanSupplier;
final class ChartPlotterRouteDistances {
	private final ChartPlotterRouteTerrain terrain;
	private final ChartPlotterRouteHull hull;
	private final BooleanSupplier cancel;
	private final int[] distance;
	private final int focusX;
	private final int focusY;
	private final int focusRadius;
	private ChartPlotterPathQueue queue;
	private boolean initialized;
	int expanded;
	ChartPlotterRouteDistances(ChartPlotterRouteTerrain terrain, ChartPlotterRouteHull hull, int gx, int gy, int radius, int focusX, int focusY, int focusRadius, ChartPlotterRouteTarget target, BooleanSupplier cancel) {
		this.terrain = terrain;
		this.hull = hull;
		this.cancel = cancel;
		this.focusX = focusX - terrain.minX;
		this.focusY = focusY - terrain.minY;
		this.focusRadius = focusRadius;
		distance = new int[terrain.clearance.length];
		int step = 0;
		for (int cost : terrain.motion.cost) step = Math.max(step, cost);
		queue = new ChartPlotterPathQueue(distance, Math.max(step * 2, radius * 2 * terrain.motion.cost[2]));
		for (int y = gy - radius; y <= gy + radius; y++) {
			if (cancel.getAsBoolean()) return;
			for (int x = gx - radius; x <= gx + radius; x++) {
				int a = terrain.at(x, y);
				if (a < 0 || target == null || !target.contains(x, y)) continue;
				for (ChartPlotterRouteHull.Mask pose : hull.hull) {
					if (!pose.clear(terrain, a)) continue;
					distance[a] = 1 + potential(x - terrain.minX, y - terrain.minY);
					queue.add(a, 0);
					break;
				}
			}
		}
		initialized = true;
	}
	int get(int target) {
		if (!initialized) return -1;
		if (target < 0 || target >= distance.length || terrain.clearance[target] == 0) return 0;
		if (distance[target] < 0) return -distance[target];
		if (distance[target] == Integer.MAX_VALUE) return 0;
		if (distance[target] == 1 + potential(target % terrain.width, target / terrain.width)) return 1;
		if (queue == null) return 0;
		if (!hull.core.clear(terrain, target)) {distance[target] = Integer.MAX_VALUE; return 0;}
		ChartPlotterRouteMotion motion = terrain.motion;
		while (queue.size > 0) {
			if ((expanded & 255) == 0 && cancel.getAsBoolean()) return -1;
			int a = queue.poll();
			int x = a % terrain.width;
			int y = a / terrain.width;
			int cost = distance[a] - potential(x, y);
			distance[a] = -cost;
			expanded++;
			for (int d = 0; d < 16; d++) {
				int nx = x + motion.x[d];
				int ny = y + motion.y[d];
				if (nx <= 0 || ny <= 0 || nx >= terrain.width - 1 || ny >= terrain.height - 1) continue;
				int b = a + terrain.delta[d];
				if (distance[b] < 0 || distance[b] == Integer.MAX_VALUE || terrain.clearance[b] == 0) continue;
				int next = cost + motion.cost[d];
				if (next > ChartPlotterRouteTerrain.MAX_COST) {terrain.limited = true; continue;}
				next += potential(nx, ny);
				if (distance[b] != 0 && next >= distance[b] || !hull.point[d].clear(terrain, a)) continue;
				if (distance[b] == 0 && !hull.core.clear(terrain, b)) {distance[b] = Integer.MAX_VALUE; continue;}
				int previous = distance[b];
				distance[b] = next;
				queue.add(b, previous);
			}
			if (a == target) break;
		}
		if (queue.size == 0) queue = null;
		return distance[target] < 0 ? -distance[target] : 0;
	}
	private int potential(int x, int y) {return terrain.motion.lowerBound(Math.max(0, Math.abs(x - focusX) - focusRadius), Math.max(0, Math.abs(y - focusY) - focusRadius));}
	long bytes() {return distance.length * 4L + (queue == null ? 0 : queue.bytes());}
}
