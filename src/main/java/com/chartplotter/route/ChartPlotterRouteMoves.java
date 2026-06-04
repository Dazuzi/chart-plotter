package com.chartplotter.route;
import com.chartplotter.util.ChartPlotterMath;
import net.runelite.api.Perspective;
public final class ChartPlotterRouteMoves {
	static final int[] DX = {0, 4, 7, 9, 10, 9, 7, 4, 0, -4, -7, -9, -10, -9, -7, -4};
	static final int[] DY = {10, 9, 7, 4, 0, -4, -7, -9, -10, -9, -7, -4, 0, 4, 7, 9};
	static final int[] COST = {100, 98, 99, 98, 100, 98, 99, 98, 100, 98, 99, 98, 100, 98, 99, 98};
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
	public static boolean model(int dx, int dy, double speed) {
		if (speed <= 0) return false;
		for (int o : OR) {
			int vx = vectorX(speed, o);
			int vy = vectorY(speed, o);
			if (vx == 0 && vy == 0) continue;
			if ((long) dx * vy == (long) dy * vx && dx * vx + dy * vy > 0) return true;
		}
		return false;
	}
	public static double speedBucket(double speed) {return Math.round(speed * 2) / 2.0;}
	private static int vectorX(double speed, int o) {return ChartPlotterMath.snap(ChartPlotterMath.round(-Perspective.SINE[o] * speed / 512.0));}
	private static int vectorY(double speed, int o) {return ChartPlotterMath.snap(ChartPlotterMath.round(-Perspective.COSINE[o] * speed / 512.0));}
}
