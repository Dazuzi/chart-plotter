package com.chartplotter;
final class ChartPlotterRoute {
	static final int PENDING = 0;
	static final int OK = 1;
	static final int UNCHARTED = 2;
	static final int NO_ROUTE = 3;
	static final int COMPLEX = 4;
	static final int BLOCKED = 5;
	private static final int[] DX = {0, 4, 7, 11, 10, 9, 7, 4, 0, -5, -7, -9, -10, -11, -7, -5};
	private static final int[] DY = {10, 9, 7, 5, 0, -4, -7, -9, -10, -11, -7, -4, 0, 5, 7, 11};
	final int status;
	final int sx;
	final int sy;
	final int tx;
	final int ty;
	final int[] x;
	final int[] y;
	final int n;
	final int[] sparseX;
	final int[] sparseY;
	final int sparseN;
	final int sparseBand;
	final int turnBias;
	final int weight;
	final ChartPlotterRouteEffort effort;
	private ChartPlotterRoute(int status, int sx, int sy, int tx, int ty, int[] x, int[] y, int n, int[] sparseX, int[] sparseY, int sparseN, int sparseBand, int turnBias, int weight, ChartPlotterRouteEffort effort) {
		this.status = status;
		this.sx = sx;
		this.sy = sy;
		this.tx = tx;
		this.ty = ty;
		this.x = x;
		this.y = y;
		this.n = n;
		this.sparseX = sparseX;
		this.sparseY = sparseY;
		this.sparseN = sparseN;
		this.sparseBand = sparseBand;
		this.turnBias = turnBias;
		this.weight = weight;
		this.effort = effort;
	}
	private static ChartPlotterRoute empty(int status, int sx, int sy, int tx, int ty, int turnBias, int weight) {return new ChartPlotterRoute(status, sx, sy, tx, ty, new int[0], new int[0], 0, new int[0], new int[0], 0, 0, turnBias, weight, null);}
	static ChartPlotterRoute pending(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(PENDING, sx, sy, tx, ty, turnBias, weight);}
	static ChartPlotterRoute uncharted(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(UNCHARTED, sx, sy, tx, ty, turnBias, weight);}
	static ChartPlotterRoute none(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(NO_ROUTE, sx, sy, tx, ty, turnBias, weight);}
	static ChartPlotterRoute complex(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(COMPLEX, sx, sy, tx, ty, turnBias, weight);}
	static ChartPlotterRoute blocked(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(BLOCKED, sx, sy, tx, ty, turnBias, weight);}
	static ChartPlotterRoute ok(int sx, int sy, int tx, int ty, int[] x, int[] y, int n, int turnBias, int weight) {return new ChartPlotterRoute(OK, sx, sy, tx, ty, x, y, n, new int[0], new int[0], 0, 0, turnBias, weight, null);}
	ChartPlotterRoute sparse(int[] x, int[] y, int n, int band) {return new ChartPlotterRoute(status, sx, sy, tx, ty, this.x, this.y, this.n, x, y, n, band, turnBias, weight, effort);}
	ChartPlotterRoute effort(ChartPlotterRouteEffort effort) {return new ChartPlotterRoute(status, sx, sy, tx, ty, x, y, n, sparseX, sparseY, sparseN, sparseBand, turnBias, weight, effort);}
	boolean target(int x, int y, int r) {return ChartPlotterMath.chebyshev(x, y, tx, ty) <= r;}
	boolean start(int x, int y) {return sx == x && sy == y;}
	ChartPlotterRoute advance(int sx, int sy, int prune, int follow, int lead) {
		if (status != OK || n < 2) return null;
		if (start(sx, sy)) return this;
		int bi = -1;
		double bt = 0;
		int bdd = 0;
		double bd = follow * follow;
		for (int i = 0; i < n - 1; i++) {
			int dx = x[i + 1] - x[i];
			int dy = y[i + 1] - y[i];
			int wx = sx - x[i];
			int wy = sy - y[i];
			int dd = dx * dx + dy * dy;
			double t = dd == 0 ? 0 : (double) (wx * dx + wy * dy) / dd;
			if (t < 0) t = 0;
			else if (t > 1) t = 1;
			double px = x[i] + dx * t - sx;
			double py = y[i] + dy * t - sy;
			double d = px * px + py * py;
			if (d < bd) {
				bd = d;
				bi = i;
				bt = t;
				bdd = dd;
			}
		}
		if (bi < 0) return null;
		if (bd > prune * prune) return this;
		int dx = x[bi + 1] - x[bi];
		int dy = y[bi + 1] - y[bi];
		int dir = dir(dx, dy);
		int px;
		int py;
		if (dir >= 0) {
			int steps = steps(dx, dy, dir);
			int step = (int) Math.round(bt * steps + lead / (double) Math.max(Math.abs(DX[dir]), Math.abs(DY[dir])));
			if (step < 0) step = 0;
			else if (step > steps) step = steps;
			px = x[bi] + DX[dir] * step;
			py = y[bi] + DY[dir] * step;
		} else {
			if (bdd > 0) bt = Math.min(1, bt + lead / Math.sqrt(bdd));
			px = (int) Math.round(x[bi] + dx * bt);
			py = (int) Math.round(y[bi] + dy * bt);
		}
		int skip = px == x[bi + 1] && py == y[bi + 1] ? bi + 2 : bi + 1;
		if (px == x[0] && py == y[0] && skip == 1) return this;
		int nn = n - skip + 1;
		if (nn < 1) nn = 1;
		int[] ox = new int[nn];
		int[] oy = new int[nn];
		ox[0] = px;
		oy[0] = py;
		for (int i = 1; i < nn; i++) {
			ox[i] = x[skip + i - 1];
			oy[i] = y[skip + i - 1];
		}
		return ok(px, py, tx, ty, ox, oy, nn, turnBias, weight).sparse(sparseX, sparseY, sparseN, sparseBand).effort(effort);
	}
	private static int steps(int dx, int dy, int dir) {
		int ax = Math.abs(DX[dir]);
		int ay = Math.abs(DY[dir]);
		if (ax != 0) return Math.abs(dx) / ax;
		return Math.abs(dy) / ay;
	}
	private static int dir(int dx, int dy) {
		if (dx == 0 && dy == 0) return -1;
		for (int i = 0; i < DX.length; i++) {
			if ((long) dx * DY[i] == (long) dy * DX[i] && dx * DX[i] + dy * DY[i] > 0) return i;
		}
		return -1;
	}
	String text() {
		if (status == PENDING) return "Charting course";
		if (status == UNCHARTED) return "Uncharted waters";
		if (status == BLOCKED) return "Not sailable";
		if (status == NO_ROUTE) return "No route found";
		if (status == COMPLEX) return "Route too complex";
		return null;
	}
}
