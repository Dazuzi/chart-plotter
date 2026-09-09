package com.chartplotter.route;
import com.chartplotter.ChartPlotterRouteEffort;
import com.chartplotter.collision.ChartPlotterCollisionData;
import java.util.function.BooleanSupplier;
public final class ChartPlotterRoute {
	private static final int[] EMPTY = new int[0];
	public static final int PENDING = 0;
	public static final int OK = 1;
	public static final int UNCHARTED = 2;
	public static final int NO_ROUTE = 3;
	public static final int COMPLEX = 4;
	public static final int BLOCKED = 5;
	public static final int FAILED = 6;
	public static final int TIMED_OUT = 7;
	public final int status;
	final boolean recalculating;
	public final int sx;
	public final int sy;
	public final int tx;
	public final int ty;
	public final int[] x;
	public final int[] y;
	public final int n;
	final ChartPlotterRouteMotion motion;
	final ChartPlotterRouteHull hull;
	final int heading;
	public final double offsetX;
	public final double offsetY;
	public final int turnBias;
	public final int weight;
	public final ChartPlotterRouteEffort effort;
	public final long time;
	public final long updated;
	private ChartPlotterRoute(int status, int sx, int sy, int tx, int ty, int[] x, int[] y, int n, ChartPlotterRouteMotion motion, ChartPlotterRouteHull hull, int heading, int turnBias, int weight, ChartPlotterRouteEffort effort, long time, long updated, boolean recalculating) {
		this.status = status;
		this.recalculating = recalculating;
		this.sx = sx;
		this.sy = sy;
		this.tx = tx;
		this.ty = ty;
		this.x = x;
		this.y = y;
		this.n = n;
		this.motion = motion;
		this.hull = hull;
		this.heading = heading;
		offsetX = motion == null ? 0.5 : motion.offsetX;
		offsetY = motion == null ? 0.5 : motion.offsetY;
		this.turnBias = turnBias;
		this.weight = weight;
		this.effort = effort;
		this.time = time;
		this.updated = updated;
	}
	private static ChartPlotterRoute empty(int status, int sx, int sy, int tx, int ty, int turnBias, int weight) {
		long now = System.currentTimeMillis();
		return new ChartPlotterRoute(status, sx, sy, tx, ty, EMPTY, EMPTY, 0, null, null, -1, turnBias, weight, null, now, now, false);
	}
	public static ChartPlotterRoute pending(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(PENDING, sx, sy, tx, ty, turnBias, weight);}
	public static ChartPlotterRoute uncharted(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(UNCHARTED, sx, sy, tx, ty, turnBias, weight);}
	public static ChartPlotterRoute none(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(NO_ROUTE, sx, sy, tx, ty, turnBias, weight);}
	public static ChartPlotterRoute complex(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(COMPLEX, sx, sy, tx, ty, turnBias, weight);}
	public static ChartPlotterRoute timedOut(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(TIMED_OUT, sx, sy, tx, ty, turnBias, weight);}
	public static ChartPlotterRoute blocked(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(BLOCKED, sx, sy, tx, ty, turnBias, weight);}
	public static ChartPlotterRoute ok(int sx, int sy, int tx, int ty, int[] x, int[] y, int n, int turnBias, int weight) {
		long now = System.currentTimeMillis();
		return new ChartPlotterRoute(OK, sx, sy, tx, ty, x, y, n, null, null, -1, turnBias, weight, null, now, now, false);
	}
	ChartPlotterRoute plan(ChartPlotterRouteMotion motion, ChartPlotterRouteHull hull, int heading) {return new ChartPlotterRoute(status, sx, sy, tx, ty, x, y, n, motion, hull, heading, turnBias, weight, effort, time, updated, recalculating);}
	public ChartPlotterRoute effort(ChartPlotterRouteEffort effort) {return new ChartPlotterRoute(status, sx, sy, tx, ty, x, y, n, motion, hull, heading, turnBias, weight, effort, time, updated, recalculating);}
	ChartPlotterRoute recalculate() {return recalculating ? this : new ChartPlotterRoute(status, sx, sy, tx, ty, x, y, n, motion, hull, heading, turnBias, weight, effort, time, updated, true);}
	public static ChartPlotterRoute failed(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(FAILED, sx, sy, tx, ty, turnBias, weight);}
	public boolean start(int x, int y) {return sx == x && sy == y;}
	int arrivalHeading() {
		int d = n < 2 || motion == null ? -1 : motion.dir(x[n - 1] - x[n - 2], y[n - 1] - y[n - 2]);
		return d < 0 ? heading : (ChartPlotterRouteMoves.OR[d] + (hull.reverse ? 1024 : 0)) & 2047;
	}
	boolean continues(ChartPlotterRoute incoming) {
		return status == OK && n > 0 && incoming != null && incoming.status == OK && incoming.n > 0 && start(incoming.x[incoming.n - 1], incoming.y[incoming.n - 1]) && x[0] == sx && y[0] == sy && heading == incoming.arrivalHeading();
	}
	public ChartPlotterRoute advance(double sx, double sy, int prune, int follow, double lead) {
		if (status != OK || n == 0) return null;
		if (n == 1) return Math.hypot(sx - x[0] - offsetX, sy - y[0] - offsetY) <= follow ? this : null;
		int segment = -1;
		double progress = 0;
		double distance = (double) follow * follow;
		for (int i = 0; i < n - 1; i++) {
			double dx = (double) x[i + 1] - x[i];
			double dy = (double) y[i + 1] - y[i];
			double wx = sx - x[i] - offsetX;
			double wy = sy - y[i] - offsetY;
			double dd = dx * dx + dy * dy;
			double projection = dd == 0 ? 1 : (wx * dx + wy * dy) / dd;
			double t = Math.max(0, Math.min(1, projection));
			double px = wx - dx * t;
			double py = wy - dy * t;
			double d = px * px + py * py;
			if (d < distance || segment < 0 && d == distance) {
				segment = i;
				progress = projection;
				distance = d;
			}
			if (d <= (double) prune * prune && t < 1) break;
		}
		if (segment < 0) return null;
		if (distance > (double) prune * prune || segment == 0 && progress <= 0) return this;
		int dx = x[segment + 1] - x[segment];
		int dy = y[segment + 1] - y[segment];
		int steps = Math.abs(dx);
		int b = Math.abs(dy);
		while (b != 0) {int next = steps % b; steps = b; b = next;}
		int step = steps == 0 ? 0 : Math.max(0, Math.min(steps, (int) Math.ceil(progress * steps + lead * steps / Math.hypot(dx, dy))));
		int px = steps == 0 ? x[segment] : x[segment] + dx / steps * step;
		int py = steps == 0 ? y[segment] : y[segment] + dy / steps * step;
		int skip = segment + (step == steps ? 2 : 1);
		if (skip == 1 && px == x[0] && py == y[0]) return this;
		int[] nx = new int[n - skip + 1];
		int[] ny = new int[nx.length];
		nx[0] = px;
		ny[0] = py;
		System.arraycopy(x, skip, nx, 1, n - skip);
		System.arraycopy(y, skip, ny, 1, n - skip);
		int incoming = step == 0 ? segment - 1 : segment;
		int dir = motion == null || incoming < 0 ? -1 : motion.dir(x[incoming + 1] - x[incoming], y[incoming + 1] - y[incoming]);
		int start = dir < 0 ? heading : (ChartPlotterRouteMoves.OR[dir] + (hull.reverse ? 1024 : 0)) & 2047;
		return new ChartPlotterRoute(status, px, py, tx, ty, nx, ny, nx.length, motion, hull, start, turnBias, weight, effort, time, System.currentTimeMillis(), recalculating);
	}
	private boolean departureClear(ChartPlotterCollisionData data) {
		if (status != OK || hull == null || motion == null || n == 0) return false;
		int previous = heading < 0 ? -1 : ((((heading - 1024 + 64) & 2047) >>> 7) + (hull.reverse ? 8 : 0)) & 15;
		if (hull.flag(data, x[0], y[0], previous) != ChartPlotterCollisionData.OPEN) return false;
		int d = n < 2 ? -1 : motion.dir(x[1] - x[0], y[1] - y[0]);
		return previous < 0 || d < 0 || previous == d || hull.circle.flag(data, x[0], y[0]) == ChartPlotterCollisionData.OPEN || hull.turn[previous * 16 + d].flag(data, x[0], y[0]) == ChartPlotterCollisionData.OPEN;
	}
	boolean valid(ChartPlotterCollisionData data, BooleanSupplier cancel) {
		if (cancel.getAsBoolean() || !departureClear(data)) return false;
		int previous = -1;
		for (int i = 1; i < n; i++) {
			int dx = x[i] - x[i - 1];
			int dy = y[i] - y[i - 1];
			int d = motion.dir(dx, dy);
			if (d < 0) return false;
			if (previous >= 0 && previous != d && hull.circle.flag(data, x[i - 1], y[i - 1]) != ChartPlotterCollisionData.OPEN && hull.turn[previous * 16 + d].flag(data, x[i - 1], y[i - 1]) != ChartPlotterCollisionData.OPEN) return false;
			int steps = motion.x[d] != 0 ? dx / motion.x[d] : dy / motion.y[d];
			for (int j = 0; j < steps; j++) {
				if ((j & 31) == 0 && cancel.getAsBoolean()) return false;
				if (hull.move[d].flag(data, x[i - 1] + motion.x[d] * j, y[i - 1] + motion.y[d] * j) != ChartPlotterCollisionData.OPEN) return false;
			}
			previous = d;
		}
		return !cancel.getAsBoolean() && ChartPlotterRoutes.near(x[n - 1], y[n - 1], tx, ty) && data.clear(x[n - 1] + offsetX, y[n - 1] + offsetY, tx + 0.5, ty + 0.5);
	}
	public String text() {
		if (status == PENDING) return "Charting course";
		if (status == UNCHARTED) return "Uncharted waters";
		if (status == BLOCKED) return "Not enough room for this boat";
		if (status == NO_ROUTE) return "No safe route found";
		if (status == COMPLEX) return "Search limit reached; try a nearer stop";
		if (status == TIMED_OUT) return "Search timed out; try a nearer stop";
		if (status == FAILED) return "Routing failed";
		return null;
	}
}
