package com.chartplotter.route;
import java.util.Arrays;
final class ChartPlotterRouteSmoother {
	private ChartPlotterRouteSmoother() {}
	static ChartPlotterRoute smooth(ChartPlotterRouteFinder search, ChartPlotterRoute route) {
		ChartPlotterRouteMotion motion = search.motion;
		boolean anchored = route.x[0] == search.sx && route.y[0] == search.sy;
		int direction = search.heading < 0 || !anchored ? -1 : ((((search.heading - 1024 + 64) & 2047) >>> 7) + (search.hull.reverse ? 8 : 0)) & 15;
		while (route.n > 2 && !search.cancel.getAsBoolean()) {
			int bestI = -1;
			int bestJ = 0;
			int bestX = 0;
			int bestY = 0;
			int bestSaved = 0;
			boolean bestCorner = false;
			long bestGain = 0;
			for (int i = 0; i < route.n - 2; i++) {
				int before = i == 0 ? direction : motion.dir(route.x[i] - route.x[i - 1], route.y[i] - route.y[i - 1]);
				int oldFirst = motion.dir(route.x[i + 1] - route.x[i], route.y[i + 1] - route.y[i]);
				long oldCost = search.turnCost(before, oldFirst);
				int previous = oldFirst;
				oldCost += (long) motion.cost[oldFirst] * (motion.x[oldFirst] == 0 ? (route.y[i + 1] - route.y[i]) / motion.y[oldFirst] : (route.x[i + 1] - route.x[i]) / motion.x[oldFirst]);
				for (int j = i + 2; j < route.n; j++) {
					if (search.cancel.getAsBoolean()) return route;
					int after = j + 1 == route.n ? -1 : motion.dir(route.x[j + 1] - route.x[j], route.y[j + 1] - route.y[j]);
					int oldLast = motion.dir(route.x[j] - route.x[j - 1], route.y[j] - route.y[j - 1]);
					oldCost += search.turnCost(previous, oldLast) + (long) motion.cost[oldLast] * (motion.x[oldLast] == 0 ? (route.y[j] - route.y[j - 1]) / motion.y[oldLast] : (route.x[j] - route.x[j - 1]) / motion.x[oldLast]);
					previous = oldLast;
					int oldTurns = j - i - 1 + change(before, oldFirst) + change(oldLast, after);
					long vx = (long) route.x[j] - route.x[i];
					long vy = (long) route.y[j] - route.y[i];
					for (int d = 0; d < 16; d++) for (int e = 0; e < 16; e++) {
						long a;
						long b;
						if (d == e) {
							if (vx * motion.y[d] != vy * motion.x[d]) continue;
							int step = motion.x[d] != 0 ? motion.x[d] : motion.y[d];
							long distance = motion.x[d] != 0 ? vx : vy;
							if (distance % step != 0) continue;
							a = distance / step;
							b = 0;
						} else {
							int det = motion.x[d] * motion.y[e] - motion.y[d] * motion.x[e];
							if (det == 0) continue;
							a = vx * motion.y[e] - vy * motion.x[e];
							b = motion.x[d] * vy - motion.y[d] * vx;
							if (a % det != 0 || b % det != 0) continue;
							a /= det;
							b /= det;
							if (b <= 0) continue;
						}
						if (a <= 0 || a > Integer.MAX_VALUE || b > Integer.MAX_VALUE) continue;
						int saved = oldTurns - change(before, d) - (b == 0 ? 0 : 1) - change(e, after);
						if (saved < 0) continue;
						long gain = oldCost + search.turnCost(oldLast, after) - a * motion.cost[d] - b * motion.cost[e] - search.turnCost(before, d) - search.turnCost(d, e) - search.turnCost(e, after);
						if (gain < bestGain || gain == bestGain && saved <= bestSaved) continue;
						long mx = route.x[i] + a * motion.x[d];
						long my = route.y[i] + a * motion.y[d];
						if (mx < Integer.MIN_VALUE || mx > Integer.MAX_VALUE || my < Integer.MIN_VALUE || my > Integer.MAX_VALUE) continue;
						if (i == 0 && anchored && (search.startBlocked & 1 << d) != 0 || before >= 0 && search.turnBlocked(route.x[i], route.y[i], before, d) || after >= 0 && search.turnBlocked(route.x[j], route.y[j], e, after)) continue;
						if (search.lineBlocked(route.x[i], route.y[i], d, (int) a) || b > 0 && (search.turnBlocked((int) mx, (int) my, d, e) || search.lineBlocked((int) mx, (int) my, e, (int) b))) continue;
						bestI = i;
						bestJ = j;
						bestX = (int) mx;
						bestY = (int) my;
						bestCorner = b > 0;
						bestSaved = saved;
						bestGain = gain;
					}
				}
			}
			if (bestI < 0) break;
			int n = route.n - (bestJ - bestI - 1) + (bestCorner ? 1 : 0);
			int[] x = new int[n];
			int[] y = new int[n];
			System.arraycopy(route.x, 0, x, 0, bestI + 1);
			System.arraycopy(route.y, 0, y, 0, bestI + 1);
			int next = bestI + 1;
			if (bestCorner) {x[next] = bestX; y[next++] = bestY;}
			System.arraycopy(route.x, bestJ, x, next, route.n - bestJ);
			System.arraycopy(route.y, bestJ, y, next, route.n - bestJ);
			int count = 1;
			for (int i = 1; i < n; i++) {
				if (i + 1 < n && motion.dir(x[i] - x[count - 1], y[i] - y[count - 1]) == motion.dir(x[i + 1] - x[i], y[i + 1] - y[i])) continue;
				x[count] = x[i];
				y[count++] = y[i];
			}
			route = ChartPlotterRoute.ok(route.sx, route.sy, route.tx, route.ty, count == n ? x : Arrays.copyOf(x, count), count == n ? y : Arrays.copyOf(y, count), count, route.turnBias, route.weight);
		}
		return route;
	}
	private static int change(int a, int b) {return a < 0 || b < 0 || a == b ? 0 : 1;}
}
