package com.chartplotter.route;
import java.util.Arrays;
final class ChartPlotterRouteMotion {
	final int[] x = new int[16];
	final int[] y = new int[16];
	final int[] cost = new int[16];
	final int[][] cells = new int[16][];
	final double speed;
	final double offsetX;
	final double offsetY;
	private final int sideCost;
	private final int diagonalX;
	private final int diagonalY;
	int radius;
	ChartPlotterRouteMotion(double speed, double offsetX, double offsetY) {
		if (!Double.isFinite(speed) || speed < 0.5 || speed > 16 || !(offsetX >= 0 && offsetX < 1 && offsetY >= 0 && offsetY < 1)) throw new IllegalArgumentException("Unsupported sailing speed");
		this.speed = speed;
		this.offsetX = offsetX;
		this.offsetY = offsetY;
		for (int d = 0; d < 16; d++) {
			int vx = ChartPlotterRouteMoves.vectorX(speed, ChartPlotterRouteMoves.OR[d]);
			int vy = ChartPlotterRouteMoves.vectorY(speed, ChartPlotterRouteMoves.OR[d]);
			int a = Math.abs(vx);
			int b = Math.abs(vy);
			while (b != 0) {int n = a % b; a = b; b = n;}
			if (a == 0) throw new IllegalArgumentException("Sailing speed has no movement");
			x[d] = vx / a;
			y[d] = vy / a;
			cost[d] = (int) Math.floor(1000 * Math.hypot(x[d], y[d]));
			radius = Math.max(radius, Math.max(Math.abs(x[d]), Math.abs(y[d])));
			cells[d] = cells(x[d], y[d], offsetX, offsetY);
		}
		sideCost = (cost[3] - cost[4] * x[3]) / y[3];
		diagonalX = (cost[3] - cost[2] * y[3]) / (x[3] - y[3]);
		diagonalY = (cost[2] * x[3] - cost[3]) / (x[3] - y[3]);
	}
	int lowerBound(int dx, int dy) {
		int a = Math.max(Math.abs(dx), Math.abs(dy));
		int b = Math.min(Math.abs(dx), Math.abs(dy));
		return (int) Math.min(ChartPlotterRouteTerrain.MAX_COST, Math.max((long) cost[4] * a + (long) sideCost * b, (long) diagonalX * a + (long) diagonalY * b));
	}
	int dir(int dx, int dy) {
		for (int d = 0; d < 16; d++) if ((long) dx * y[d] == (long) dy * x[d] && (long) dx * x[d] + (long) dy * y[d] > 0) return d;
		return -1;
	}
	private static int[] cells(int dx, int dy, double fx, double fy) {
		int[] cells = new int[(Math.abs(dx) + 3) * (Math.abs(dy) + 3)];
		int n = 0;
		for (int y = Math.min(0, dy) - 1; y <= Math.max(0, dy) + 1; y++) for (int x = Math.min(0, dx) - 1; x <= Math.max(0, dx) + 1; x++) {
			double lo = 0;
			double hi = 1;
			if (dx == 0) {if (fx < x || fx > x + 1) continue;}
			else {
				double a = (x - fx) / dx;
				double b = (x + 1 - fx) / dx;
				lo = Math.max(lo, Math.min(a, b));
				hi = Math.min(hi, Math.max(a, b));
			}
			if (dy == 0) {if (fy < y || fy > y + 1) continue;}
			else {
				double a = (y - fy) / dy;
				double b = (y + 1 - fy) / dy;
				lo = Math.max(lo, Math.min(a, b));
				hi = Math.min(hi, Math.max(a, b));
			}
			if (lo <= hi + 1e-12) cells[n++] = x << 16 | y & 65535;
		}
		return Arrays.copyOf(cells, n);
	}
}
