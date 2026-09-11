package com.chartplotter.collision;
import net.runelite.api.WorldEntityConfig;
import java.util.function.IntBinaryOperator;
public final class ChartPlotterHull {
	public final double ox;
	public final double oy;
	public final double hw;
	public final double hh;
	public final double reach;
	public final double margin;
	public final double rotationPadding;
	public final double turnRadius;
	public ChartPlotterHull(WorldEntityConfig config) {
		ox = config == null ? 0 : config.getBoundsX() / 128.0;
		oy = config == null ? 0 : config.getBoundsY() / 128.0;
		hw = config == null ? 0 : config.getBoundsWidth() / 256.0;
		hh = config == null ? 0 : config.getBoundsHeight() / 256.0;
		reach = Math.hypot(Math.abs(ox) + hw, Math.abs(oy) + hh);
		margin = config == null ? 1e-5 : 2 / 128.0 + 2 * reach / 65536;
		rotationPadding = 2 * reach * Math.sin(Math.PI / 256) + 1e-5;
		turnRadius = reach + Math.sqrt(2) * (margin + rotationPadding);
		if (hw < 0 || hh < 0 || reach > 64) throw new IllegalArgumentException("Unsupported boat footprint");
	}
	public boolean matches(WorldEntityConfig config) {return config == null ? hw == 0 && hh == 0 : ox == config.getBoundsX() / 128.0 && oy == config.getBoundsY() / 128.0 && hw == config.getBoundsWidth() / 256.0 && hh == config.getBoundsHeight() / 256.0;}
	public int pose(ChartPlotterCollisionData data, double x, double y, int orientation) {return tiles(orientation, x, y, 0, 0, 0, data::flagAt);}
	public boolean recedes(double ax, double ay, int from, double bx, double by, int to, int tx, int ty) {
		double angle = (from & 2047) * Math.PI / 1024;
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		double rx = tx + 0.5 - ax - cos * ox - sin * oy;
		double ry = ty + 0.5 - ay - cos * oy + sin * ox;
		double w = hw + margin;
		double h = hh + margin;
		double ex = Math.abs(cos) * w + Math.abs(sin) * h + 0.5;
		double ey = Math.abs(sin) * w + Math.abs(cos) * h + 0.5;
		double tileExtent = (Math.abs(cos) + Math.abs(sin)) / 2;
		double depth = Math.min(Math.min(ex - Math.abs(rx), ey - Math.abs(ry)), Math.min(w + tileExtent - Math.abs(rx * cos - ry * sin), h + tileExtent - Math.abs(rx * sin + ry * cos)));
		if (depth <= 0) return false;
		double turn = ((to - from + 1024 & 2047) - 1024) * Math.PI / 1024;
		for (int axis = 0; axis < 4; axis++) {
			double nx = axis == 0 ? 1 : axis == 1 ? 0 : axis == 2 ? cos : sin;
			double ny = axis == 0 ? 0 : axis == 1 ? 1 : axis == 2 ? -sin : cos;
			double distance = nx * rx + ny * ry;
			double extent = axis == 0 ? ex : axis == 1 ? ey : axis == 2 ? w + tileExtent : h + tileExtent;
			if (extent - Math.abs(distance) > depth + 1e-9) continue;
			for (int sign = -1; sign <= 1; sign += 2) if (sign * distance >= 0 && recedes(angle, turn, nx * sign, ny * sign, bx - ax, by - ay)) return true;
		}
		return false;
	}
	private boolean recedes(double angle, double turn, double nx, double ny, double dx, double dy) {
		double move = nx * dx + ny * dy;
		if (turn == 0) return move <= 1e-9;
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		double u = nx * cos - ny * sin;
		double v = nx * sin + ny * cos;
		double limit = u * ox + v * oy + Math.abs(u) * (hw + margin) + Math.abs(v) * (hh + margin) + 1e-9;
		double endCos = Math.cos(angle + turn);
		double endSin = Math.sin(angle + turn);
		for (int i = 0; i < 4; i++) {
			double x = ox + (i < 2 ? hw + margin : -hw - margin);
			double y = oy + ((i & 1) == 0 ? hh + margin : -hh - margin);
			double a = nx * x + ny * y;
			double b = nx * y - ny * x;
			if (move + a * endCos + b * endSin > limit) return false;
			double rate = move / (turn * Math.hypot(a, b));
			if (Math.abs(rate) > 1) continue;
			double phase = Math.atan2(b, a);
			double root = Math.asin(rate);
			for (int j = 0; j < 2; j++) {
				double t = Math.IEEEremainder(phase + (j == 0 ? root : Math.PI - root) - angle, Math.PI * 2) / turn;
				if (t > 0 && t < 1 && move * t + a * Math.cos(angle + turn * t) + b * Math.sin(angle + turn * t) > limit) return false;
			}
		}
		return true;
	}
	public int sweep(ChartPlotterCollisionData data, double ax, double ay, int from, double bx, double by, int to) {return sweep(ax, ay, from, bx, by, to, data::flagAt);}
	public int sweep(double ax, double ay, int from, double bx, double by, int to, IntBinaryOperator flags) {
		int delta = (to - from + 1024 & 2047) - 1024;
		if (delta == 0) return tiles(from, ax, ay, bx - ax, by - ay, 0, flags);
		if (delta == -1024 && ax == bx && ay == by) return circle(ax, ay, flags);
		if (delta == -1024) delta = 2048;
		int steps = (Math.abs(delta) + 15) / 16;
		boolean unknown = false;
		for (int i = 0; i <= steps; i++) {
			int flag = tiles(from + delta * i / steps, ax, ay, bx - ax, by - ay, rotationPadding, flags);
			if (flag == ChartPlotterCollisionData.BLOCKED) return flag;
			unknown |= flag != ChartPlotterCollisionData.OPEN;
		}
		return unknown ? ChartPlotterCollisionData.UNKNOWN : ChartPlotterCollisionData.OPEN;
	}
	public int circle(double ax, double ay, IntBinaryOperator flags) {
		boolean unknown = false;
		for (int py = (int) Math.floor(ay - turnRadius); py <= (int) Math.floor(ay + turnRadius); py++) for (int px = (int) Math.floor(ax - turnRadius); px <= (int) Math.floor(ax + turnRadius); px++) {
			if (Math.hypot(Math.max(0, Math.max(px - ax, ax - px - 1)), Math.max(0, Math.max(py - ay, ay - py - 1))) > turnRadius) continue;
			int flag = flags.applyAsInt(px, py);
			if (flag == ChartPlotterCollisionData.BLOCKED) return flag;
			unknown |= flag != ChartPlotterCollisionData.OPEN;
		}
		return unknown ? ChartPlotterCollisionData.UNKNOWN : ChartPlotterCollisionData.OPEN;
	}
	public int tiles(int orientation, double ax, double ay, double dx, double dy, double padding, IntBinaryOperator flags) {
		double angle = (orientation & 2047) * Math.PI / 1024;
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		double x = ax + cos * ox + sin * oy + dx / 2;
		double y = ay + cos * oy - sin * ox + dy / 2;
		double w = hw + padding + margin;
		double h = hh + padding + margin;
		double ex = Math.abs(cos) * w + Math.abs(sin) * h + Math.abs(dx) / 2;
		double ey = Math.abs(sin) * w + Math.abs(cos) * h + Math.abs(dy) / 2;
		double tileExtent = (Math.abs(cos) + Math.abs(sin)) / 2;
		double eu = w + Math.abs(dx * cos - dy * sin) / 2 + tileExtent;
		double ev = h + Math.abs(dx * sin + dy * cos) / 2 + tileExtent;
		double en = w * Math.abs(-dy * cos - dx * sin) + h * Math.abs(-dy * sin + dx * cos) + (Math.abs(dx) + Math.abs(dy)) / 2;
		boolean unknown = false;
		for (int py = (int) Math.floor(y - ey); py <= (int) Math.floor(y + ey); py++) {
			for (int px = (int) Math.floor(x - ex); px <= (int) Math.floor(x + ex); px++) {
				double rx = px + 0.5 - x;
				double ry = py + 0.5 - y;
				if (Math.abs(rx) >= ex + 0.5 || Math.abs(ry) >= ey + 0.5 || Math.abs(rx * cos - ry * sin) >= eu || Math.abs(rx * sin + ry * cos) >= ev) continue;
				if ((dx != 0 || dy != 0) && Math.abs(-rx * dy + ry * dx) >= en) continue;
				int flag = flags.applyAsInt(px, py);
				if (flag == ChartPlotterCollisionData.BLOCKED) return flag;
				unknown |= flag != ChartPlotterCollisionData.OPEN;
			}
		}
		return unknown ? ChartPlotterCollisionData.UNKNOWN : ChartPlotterCollisionData.OPEN;
	}
}
