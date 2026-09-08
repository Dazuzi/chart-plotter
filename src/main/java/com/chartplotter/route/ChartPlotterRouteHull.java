package com.chartplotter.route;
import com.chartplotter.collision.ChartPlotterCollisionData;
import net.runelite.api.WorldEntityConfig;
import java.util.BitSet;
final class ChartPlotterRouteHull {
	final Mask[] hull = new Mask[16];
	final Mask[] move = new Mask[16];
	final Mask[] turn = new Mask[256];
	final Mask circle;
	final Mask core;
	final double offsetX;
	final double offsetY;
	final boolean reverse;
	private final double margin;
	private final double ox;
	private final double oy;
	private final double hw;
	private final double hh;
	private final int radius;
	private final int side;
	private final int stride;
	ChartPlotterRouteHull(WorldEntityConfig config, ChartPlotterRouteMotion motion, int stride, boolean reverse) {
		this.stride = stride;
		offsetX = motion.offsetX;
		offsetY = motion.offsetY;
		this.reverse = reverse;
		ox = config == null ? 0 : config.getBoundsX() / 128.0;
		oy = config == null ? 0 : config.getBoundsY() / 128.0;
		hw = config == null ? 0 : config.getBoundsWidth() / 256.0;
		hh = config == null ? 0 : config.getBoundsHeight() / 256.0;
		double reach = Math.hypot(Math.abs(ox) + hw, Math.abs(oy) + hh);
		margin = config == null ? 1e-5 : 1 / 128.0 + reach / 65536;
		if (hw < 0 || hh < 0 || reach > 64) throw new IllegalArgumentException("Unsupported boat footprint");
		radius = (int) Math.ceil(reach) + motion.radius + 2;
		side = radius * 2 + 1;
		BitSet cells = new BitSet(side * side);
		for (int y = -radius; y <= radius; y++) for (int x = -radius; x <= radius; x++) {
			if (Math.hypot(Math.max(0, Math.max(x - offsetX, offsetX - x - 1)), Math.max(0, Math.max(y - offsetY, offsetY - y - 1))) <= reach + Math.sqrt(2) * margin) cells.set(x + radius + (y + radius) * side);
		}
		circle = mask(cells);
		cells.clear();
		double inner = Math.max(0, Math.min(hw - Math.abs(ox), hh - Math.abs(oy)));
		for (int y = -radius; y <= radius; y++) for (int x = -radius; x <= radius; x++) {
			if (Math.hypot(Math.max(0, Math.max(x - offsetX, offsetX - x - 1)), Math.max(0, Math.max(y - offsetY, offsetY - y - 1))) < inner - 1e-5) cells.set(x + radius + (y + radius) * side);
		}
		cells.set(radius + radius * side);
		core = mask(cells);
		BitSet[] arcs = new BitSet[16];
		for (int d = 0; d < 16; d++) {
			int o = (1024 + d * 128 + (reverse ? 1024 : 0)) & 2047;
			cells.clear();
			add(cells, o, 0, 0, 0);
			cells.set(radius + radius * side);
			hull[d] = mask(cells);
			cells.clear();
			add(cells, o, motion.x[d], motion.y[d], 0);
			for (int coordinate : motion.cells[d]) cells.set((coordinate >> 16) + radius + ((short) coordinate + radius) * side);
			move[d] = mask(cells);
			arcs[d] = new BitSet(side * side);
			double padding = 2 * reach * Math.sin(Math.PI / 256) + 1e-5;
			for (int i = 0; i <= 128; i += 16) add(arcs[d], o + i, 0, 0, padding);
		}
		for (int d = 0; d < 16; d++) for (int next = 0; next < 16; next++) {
			cells.clear();
			int change = (next - d + 24 & 15) - 8;
			if (change == 0) {turn[d * 16 + next] = hull[d]; continue;}
			if (change == -8) {turn[d * 16 + next] = circle; continue;}
			for (int i = 0; i < Math.abs(change); i++) cells.or(arcs[change > 0 ? d + i & 15 : d - i - 1 & 15]);
			turn[d * 16 + next] = mask(cells);
		}
	}
	boolean matches(WorldEntityConfig config) {return config == null ? hw == 0 && hh == 0 : ox == config.getBoundsX() / 128.0 && oy == config.getBoundsY() / 128.0 && hw == config.getBoundsWidth() / 256.0 && hh == config.getBoundsHeight() / 256.0;}
	int flag(ChartPlotterCollisionData data, int x, int y, int d) {
		if (d >= 0) return hull[d].flag(data, x, y);
		boolean unknown = false;
		for (Mask mask : hull) {
			int f = mask.flag(data, x, y);
			if (f == ChartPlotterCollisionData.OPEN) return f;
			unknown |= f == ChartPlotterCollisionData.UNKNOWN;
		}
		return unknown ? ChartPlotterCollisionData.UNKNOWN : ChartPlotterCollisionData.BLOCKED;
	}
	private void add(BitSet cells, int orientation, double dx, double dy, double padding) {
		double angle = (orientation & 2047) * Math.PI / 1024;
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		double x = offsetX + cos * ox + sin * oy + dx / 2;
		double y = offsetY + cos * oy - sin * ox + dy / 2;
		double w = hw + padding + margin;
		double h = hh + padding + margin;
		double ex = Math.abs(cos) * w + Math.abs(sin) * h + Math.abs(dx) / 2;
		double ey = Math.abs(sin) * w + Math.abs(cos) * h + Math.abs(dy) / 2;
		double tileExtent = (Math.abs(cos) + Math.abs(sin)) / 2;
		double eu = w + Math.abs(dx * cos - dy * sin) / 2 + tileExtent;
		double ev = h + Math.abs(dx * sin + dy * cos) / 2 + tileExtent;
		double en = w * Math.abs(-dy * cos - dx * sin) + h * Math.abs(-dy * sin + dx * cos) + (Math.abs(dx) + Math.abs(dy)) / 2;
		for (int py = Math.max(-radius, (int) Math.floor(y - ey)); py <= Math.min(radius, (int) Math.floor(y + ey)); py++) {
			for (int px = Math.max(-radius, (int) Math.floor(x - ex)); px <= Math.min(radius, (int) Math.floor(x + ex)); px++) {
				double rx = px + 0.5 - x;
				double ry = py + 0.5 - y;
				if (Math.abs(rx) >= ex + 0.5 || Math.abs(ry) >= ey + 0.5 || Math.abs(rx * cos - ry * sin) >= eu || Math.abs(rx * sin + ry * cos) >= ev) continue;
				if ((dx != 0 || dy != 0) && Math.abs(-rx * dy + ry * dx) >= en) continue;
				cells.set(px + radius + (py + radius) * side);
			}
		}
	}
	private Mask mask(BitSet cells) {
		int[] offsets = new int[cells.cardinality()];
		int[] coordinates = new int[offsets.length];
		int minX = 0;
		int minY = 0;
		int maxX = 0;
		int maxY = 0;
		int n = 0;
		for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1)) {
			int x = i % side - radius;
			int y = i / side - radius;
			coordinates[n] = x << 16 | y & 65535;
			offsets[n++] = x + y * stride;
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
		}
		return new Mask(offsets, coordinates, stride, minX, minY, maxX, maxY);
	}
	static final class Mask {
		final int[] offsets;
		final int[] coordinates;
		int stride;
		final int minX;
		final int minY;
		final int maxX;
		final int maxY;
		final int radius;
		Mask(int[] offsets, int[] coordinates, int stride, int minX, int minY, int maxX, int maxY) {
			this.offsets = offsets;
			this.coordinates = coordinates;
			this.stride = stride;
			this.minX = minX;
			this.minY = minY;
			this.maxX = maxX;
			this.maxY = maxY;
			radius = Math.max(Math.max(-minX, -minY), Math.max(maxX, maxY));
		}
		boolean clear(ChartPlotterRouteTerrain terrain, int a) {
			if (a < 0 || a >= terrain.clearance.length || terrain.clearance[a] == 0) return false;
			if (terrain.clearance[a] > radius) return true;
			int x = a % terrain.width;
			int y = a / terrain.width;
			if (x + minX < 0 || y + minY < 0 || x + maxX >= terrain.width || y + maxY >= terrain.height) return false;
			if (stride != terrain.width) {
				stride = terrain.width;
				for (int i = 0; i < offsets.length; i++) offsets[i] = (coordinates[i] >> 16) + (short) coordinates[i] * stride;
			}
			for (int offset : offsets) if (terrain.clearance[a + offset] == 0) return false;
			return true;
		}
		int flag(ChartPlotterCollisionData data, int x, int y) {
			boolean unknown = false;
			for (int coordinate : coordinates) {
				int flag = data.flagAt(x + (coordinate >> 16), y + (short) coordinate);
				if (flag == ChartPlotterCollisionData.BLOCKED) return flag;
				unknown |= flag != ChartPlotterCollisionData.OPEN;
			}
			return unknown ? ChartPlotterCollisionData.UNKNOWN : ChartPlotterCollisionData.OPEN;
		}
	}
}
