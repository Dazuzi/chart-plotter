package com.chartplotter.route;
import com.chartplotter.util.ChartPlotterMath;
import net.runelite.api.Perspective;
final class ChartPlotterRouteUtil {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private ChartPlotterRouteUtil() {}
	static int center(int v) {return v * TS + TS / 2;}
	static int dist(int ax, int ay, int bx, int by) {return ChartPlotterMath.chebyshev(ax, ay, bx, by);}
	static int h(int x, int y, int tx, int ty) {
		int dx = Math.abs(tx - x);
		int dy = Math.abs(ty - y);
		int a = Math.max(dx, dy);
		int b = Math.min(dx, dy);
		return 9 * b <= 4 * a ? 10 * a + 2 * b : (290 * a + 205 * b) / 35;
	}
	static int cap(int sx, int sy, int tx, int ty, int margin) {
		int h = h(sx, sy, tx, ty);
		return Math.max(h * 14 / 10 + 200, h + margin * 10 + 160);
	}
	static long state(int x, int y, int d) {return ((long) x & 0xfffffL) << 44 | ((long) y & 0xfffffL) << 4 | d;}
}
