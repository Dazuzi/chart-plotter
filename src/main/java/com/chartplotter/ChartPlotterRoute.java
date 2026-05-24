package com.chartplotter;
final class ChartPlotterRoute {
	static final int PENDING = 0;
	static final int OK = 1;
	static final int UNCHARTED = 2;
	static final int NO_ROUTE = 3;
	static final int COMPLEX = 4;
	static final int BLOCKED = 5;
	final int status;
	final int sx;
	final int sy;
	final int tx;
	final int ty;
	final int[] x;
	final int[] y;
	final int n;
	final int turnBias;
	final boolean fast;
	final ChartPlotterRouteEffort effort;
	private ChartPlotterRoute(int status, int sx, int sy, int tx, int ty, int[] x, int[] y, int n, int turnBias, boolean fast, ChartPlotterRouteEffort effort) {
		this.status = status;
		this.sx = sx;
		this.sy = sy;
		this.tx = tx;
		this.ty = ty;
		this.x = x;
		this.y = y;
		this.n = n;
		this.turnBias = turnBias;
		this.fast = fast;
		this.effort = effort;
	}
	static ChartPlotterRoute pending(int sx, int sy, int tx, int ty, int turnBias, boolean fast) {return new ChartPlotterRoute(PENDING, sx, sy, tx, ty, new int[0], new int[0], 0, turnBias, fast, null);}
	static ChartPlotterRoute uncharted(int sx, int sy, int tx, int ty, int turnBias, boolean fast) {return new ChartPlotterRoute(UNCHARTED, sx, sy, tx, ty, new int[0], new int[0], 0, turnBias, fast, null);}
	static ChartPlotterRoute none(int sx, int sy, int tx, int ty, int turnBias, boolean fast) {return new ChartPlotterRoute(NO_ROUTE, sx, sy, tx, ty, new int[0], new int[0], 0, turnBias, fast, null);}
	static ChartPlotterRoute complex(int sx, int sy, int tx, int ty, int turnBias, boolean fast) {return new ChartPlotterRoute(COMPLEX, sx, sy, tx, ty, new int[0], new int[0], 0, turnBias, fast, null);}
	static ChartPlotterRoute blocked(int sx, int sy, int tx, int ty, int turnBias, boolean fast) {return new ChartPlotterRoute(BLOCKED, sx, sy, tx, ty, new int[0], new int[0], 0, turnBias, fast, null);}
	static ChartPlotterRoute ok(int sx, int sy, int tx, int ty, int[] x, int[] y, int n, int turnBias, boolean fast) {return new ChartPlotterRoute(OK, sx, sy, tx, ty, x, y, n, turnBias, fast, null);}
	ChartPlotterRoute effort(ChartPlotterRouteEffort effort) {return new ChartPlotterRoute(status, sx, sy, tx, ty, x, y, n, turnBias, fast, effort);}
	boolean target(int x, int y, int r) {return Math.max(Math.abs(tx - x), Math.abs(ty - y)) <= r;}
	boolean start(int x, int y) {return sx == x && sy == y;}
	ChartPlotterRoute advance(int sx, int sy) {
		if (status != OK || n < 2) return null;
		if (start(sx, sy)) return this;
		int bi = -1;
		double bd = 10;
		for (int i = 0; i < n - 1; i++) {
			double d = dist(sx, sy, x[i], y[i], x[i + 1], y[i + 1]);
			if (d < bd) {
				bd = d;
				bi = i;
			}
		}
		if (bi < 0) return null;
		int skip = sx == x[bi + 1] && sy == y[bi + 1] ? bi + 2 : bi + 1;
		int nn = n - skip + 1;
		if (nn < 1) nn = 1;
		int[] ox = new int[nn];
		int[] oy = new int[nn];
		ox[0] = sx;
		oy[0] = sy;
		for (int i = 1; i < nn; i++) {
			ox[i] = x[skip + i - 1];
			oy[i] = y[skip + i - 1];
		}
		return ok(sx, sy, tx, ty, ox, oy, nn, turnBias, fast).effort(effort);
	}
	String text() {
		if (status == PENDING) return "Charting course";
		if (status == UNCHARTED) return "Uncharted waters";
		if (status == BLOCKED) return "Not sailable";
		if (status == NO_ROUTE) return "No route found";
		if (status == COMPLEX) return "Route too complex";
		return null;
	}
	private static double dist(int px, int py, int ax, int ay, int bx, int by) {
		int dx = bx - ax;
		int dy = by - ay;
		int wx = px - ax;
		int wy = py - ay;
		int dd = dx * dx + dy * dy;
		if (dd == 0) return wx * wx + wy * wy;
		double t = (double) (wx * dx + wy * dy) / dd;
		if (t < 0) t = 0;
		else if (t > 1) t = 1;
		double x = ax + dx * t - px;
		double y = ay + dy * t - py;
		return x * x + y * y;
	}
}
