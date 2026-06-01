package com.chartplotter.route;
import com.chartplotter.ChartPlotterRouteEffort;
import com.chartplotter.util.ChartPlotterMath;
public final class ChartPlotterRoute {
	public static final int PENDING = 0;
	public static final int OK = 1;
	public static final int UNCHARTED = 2;
	public static final int NO_ROUTE = 3;
	public static final int COMPLEX = 4;
	public static final int BLOCKED = 5;
	private static final int[] DX = ChartPlotterRouteMoves.DX;
	private static final int[] DY = ChartPlotterRouteMoves.DY;
	public final int status;
	public final int sx;
	public final int sy;
	public final int tx;
	public final int ty;
	public final int[] x;
	public final int[] y;
	public final int n;
	public final int[] sparseX;
	public final int[] sparseY;
	public final int sparseN;
	public final int sparseBand;
	public final int turnBias;
	public final int weight;
	public final ChartPlotterRouteEffort effort;
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
	public static ChartPlotterRoute pending(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(PENDING, sx, sy, tx, ty, turnBias, weight);}
	public static ChartPlotterRoute uncharted(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(UNCHARTED, sx, sy, tx, ty, turnBias, weight);}
	public static ChartPlotterRoute none(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(NO_ROUTE, sx, sy, tx, ty, turnBias, weight);}
	public static ChartPlotterRoute complex(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(COMPLEX, sx, sy, tx, ty, turnBias, weight);}
	public static ChartPlotterRoute blocked(int sx, int sy, int tx, int ty, int turnBias, int weight) {return empty(BLOCKED, sx, sy, tx, ty, turnBias, weight);}
	public static ChartPlotterRoute ok(int sx, int sy, int tx, int ty, int[] x, int[] y, int n, int turnBias, int weight) {return new ChartPlotterRoute(OK, sx, sy, tx, ty, x, y, n, new int[0], new int[0], 0, 0, turnBias, weight, null);}
	public ChartPlotterRoute sparse(int[] x, int[] y, int n, int band) {return new ChartPlotterRoute(status, sx, sy, tx, ty, this.x, this.y, this.n, x, y, n, band, turnBias, weight, effort);}
	public ChartPlotterRoute effort(ChartPlotterRouteEffort effort) {return new ChartPlotterRoute(status, sx, sy, tx, ty, x, y, n, sparseX, sparseY, sparseN, sparseBand, turnBias, weight, effort);}
	public boolean target(int x, int y, int r) {return ChartPlotterMath.chebyshev(x, y, tx, ty) <= r;}
	public boolean start(int x, int y) {return sx == x && sy == y;}
	public ChartPlotterRoute advance(int sx, int sy, int prune, int follow, int lead) {
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
			int steps = ChartPlotterRouteMoves.steps(dx, dy, dir);
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
	private static int dir(int dx, int dy) {return ChartPlotterRouteMoves.dir(dx, dy);}
	public String text() {
		if (status == PENDING) return "Charting course";
		if (status == UNCHARTED) return "Uncharted waters";
		if (status == BLOCKED) return "Not sailable";
		if (status == NO_ROUTE) return "No route found";
		if (status == COMPLEX) return "Route too complex";
		return null;
	}
}
