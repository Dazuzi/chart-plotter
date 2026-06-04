package com.chartplotter.overlay;
import java.awt.BasicStroke;
import java.awt.Stroke;
final class ChartPlotterStrokeCache {
	private final int cap;
	private final int join;
	private final float[] dash;
	private int width = Integer.MIN_VALUE;
	private Stroke solid;
	private Stroke dashed;
	ChartPlotterStrokeCache(int cap, int join, float[] dash) {
		this.cap = cap;
		this.join = join;
		this.dash = dash;
	}
	Stroke solid(int w) {
		update(w);
		return solid;
	}
	Stroke dashed(int w) {
		update(w);
		return dashed;
	}
	@SuppressWarnings("MagicConstant")
	private void update(int w) {
		if (w == width) return;
		width = w;
		solid = new BasicStroke(w, cap, join);
		dashed = new BasicStroke(w, cap, join, 10, dash, 0);
	}
}
