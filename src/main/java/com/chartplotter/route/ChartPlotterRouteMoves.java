package com.chartplotter.route;
import com.chartplotter.util.ChartPlotterMath;
public final class ChartPlotterRouteMoves {
	static final int[] OR = {1024, 1152, 1280, 1408, 1536, 1664, 1792, 1920, 0, 128, 256, 384, 512, 640, 768, 896};
	private ChartPlotterRouteMoves() {}
	public static boolean model(int dx, int dy, double speed) {
		if (speed <= 0) return false;
		for (int o : OR) {
			int vx = vectorX(speed, o);
			int vy = vectorY(speed, o);
			if (vx == 0 && vy == 0) continue;
			if ((long) dx * vy == (long) dy * vx && (long) dx * vx + (long) dy * vy > 0) return true;
		}
		return false;
	}
	public static boolean solid(int ax, int ay, int bx, int by, double speed) {return speed <= 0 || model(bx - ax, by - ay, speed);}
	public static boolean solid(double ax, double ay, double bx, double by, Model model) {return model == null || model.matches(bx - ax, by - ay);}
	public static double speedBucket(double speed) {return Math.round(speed * 2) / 2.0;}
	public static Model model(double speed) {return speed <= 0 ? null : new Model(speed);}
	static int vectorX(double speed, int o) {return ChartPlotterMath.velocityX(speed, o);}
	static int vectorY(double speed, int o) {return ChartPlotterMath.velocityY(speed, o);}
	public static final class Model {
		private final int[] x = new int[OR.length];
		private final int[] y = new int[OR.length];
		private Model(double speed) {
			for (int i = 0; i < OR.length; i++) {
				x[i] = vectorX(speed, OR[i]);
				y[i] = vectorY(speed, OR[i]);
			}
		}
		private boolean matches(double dx, double dy) {
			for (int i = 0; i < x.length; i++) {
				if (x[i] == 0 && y[i] == 0) continue;
				if (dx * y[i] == dy * x[i] && dx * x[i] + dy * y[i] > 0) return true;
			}
			return false;
		}
	}
}
