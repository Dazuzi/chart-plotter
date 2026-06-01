package com.chartplotter;
final class ChartPlotterRouteMoves {
	static final int[] DX = {0, 4, 7, 11, 10, 9, 7, 4, 0, -5, -7, -9, -10, -11, -7, -5};
	static final int[] DY = {10, 9, 7, 5, 0, -4, -7, -9, -10, -11, -7, -4, 0, 5, 7, 11};
	static final int[] COST = {100, 98, 98, 120, 100, 98, 98, 98, 100, 120, 98, 98, 100, 120, 98, 120};
	static final int[] OR = {1024, 1152, 1280, 1408, 1536, 1664, 1792, 1920, 0, 128, 256, 384, 512, 640, 768, 896};
	private ChartPlotterRouteMoves() {}
	static int dir(int dx, int dy) {
		if (dx == 0 && dy == 0) return -1;
		for (int i = 0; i < DX.length; i++) {
			if ((long) dx * DY[i] == (long) dy * DX[i] && dx * DX[i] + dy * DY[i] > 0) return i;
		}
		return -1;
	}
	static int steps(int dx, int dy, int dir) {
		int ax = Math.abs(DX[dir]);
		int ay = Math.abs(DY[dir]);
		return ax != 0 ? Math.abs(dx) / ax : Math.abs(dy) / ay;
	}
}
