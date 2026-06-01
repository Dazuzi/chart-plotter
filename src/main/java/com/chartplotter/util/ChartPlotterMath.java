package com.chartplotter.util;
import net.runelite.api.Perspective;
public final class ChartPlotterMath {
	private ChartPlotterMath() {}
	public static int rotateX(int cx, int o, int x, int y) {return cx + (int) (((long) Perspective.COSINE[o] * x + (long) Perspective.SINE[o] * y) >> 16);}
	public static int rotateY(int cy, int o, int x, int y) {return cy + (int) (((long) Perspective.COSINE[o] * y - (long) Perspective.SINE[o] * x) >> 16);}
	public static int chebyshev(int ax, int ay, int bx, int by) {return Math.max(Math.abs(ax - bx), Math.abs(ay - by));}
	public static double orientationToDegrees(int angle) {return 270 - angle / (2048 / 360.0);}
	public static int angleDir(int start, int target, int current) {
		double d = orientationToDegrees(target) - orientationToDegrees(start);
		if (d < -180) return -1;
		if (d > 180) return 1;
		if (d == 180 || d == -180) return current < 0 ? -1 : 1;
		if (d == 0) return 0;
		return -(int) (d / Math.abs(d));
	}
	public static int norm(int v) {return ((v % 2048) + 2048) % 2048;}
	public static int round(double v) {return (int) (Math.round(Math.abs(v)) * Math.signum(v));}
	public static int snap(int v) {return round(v / 32.0) * 32;}
	public static double speed(int vx, int vy) {
		double x = vx / 128.0;
		double y = vy / 128.0;
		return Math.round(Math.sqrt(x * x + y * y) / 0.5) * 0.5;
	}
}
