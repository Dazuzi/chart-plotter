package com.chartplotter;
import net.runelite.api.Perspective;
final class ChartPlotterMath {
	private ChartPlotterMath() {}
	static int rotateX(int cx, int o, int x, int y) {return cx + (int) (((long) Perspective.COSINE[o] * x + (long) Perspective.SINE[o] * y) >> 16);}
	static int rotateY(int cy, int o, int x, int y) {return cy + (int) (((long) Perspective.COSINE[o] * y - (long) Perspective.SINE[o] * x) >> 16);}
	static int chebyshev(int ax, int ay, int bx, int by) {return Math.max(Math.abs(ax - bx), Math.abs(ay - by));}
}
