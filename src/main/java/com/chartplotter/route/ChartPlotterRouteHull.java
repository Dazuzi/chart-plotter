package com.chartplotter.route;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.collision.ChartPlotterHull;
import net.runelite.api.WorldEntityConfig;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicReferenceArray;
final class ChartPlotterRouteHull {
	final Mask[] hull = new Mask[16];
	final Mask[] move = new Mask[16];
	final Mask[] point = new Mask[16];
	private final AtomicReferenceArray<Mask> turns = new AtomicReferenceArray<>(256);
	private final BitSet[] arcs = new BitSet[16];
	final Mask circle;
	final Mask core;
	final double offsetX;
	final double offsetY;
	final boolean reverse;
	final ChartPlotterHull geometry;
	private final int radius;
	private final int side;
	private final int stride;
	ChartPlotterRouteHull(WorldEntityConfig config, ChartPlotterRouteMotion motion, int stride, boolean reverse) {
		this.stride = stride;
		offsetX = motion.offsetX;
		offsetY = motion.offsetY;
		this.reverse = reverse;
		geometry = new ChartPlotterHull(config);
		radius = (int) Math.ceil(geometry.turnRadius) + motion.radius + 2;
		if (radius > Short.MAX_VALUE || (2L * radius + 1) * (2L * radius + 1) > ChartPlotterRouteTerrain.MAX_AREA) throw new ArithmeticException("Route hull mask exceeds search area budget");
		side = radius * 2 + 1;
		BitSet cells = new BitSet(side * side);
		geometry.circle(offsetX, offsetY, (x, y) -> {cells.set(x + radius + (y + radius) * side); return ChartPlotterCollisionData.OPEN;});
		circle = mask(cells);
		cells.clear();
		double inner = Math.max(0, Math.min(geometry.hw - Math.abs(geometry.ox), geometry.hh - Math.abs(geometry.oy)));
		for (int y = -radius; y <= radius; y++) for (int x = -radius; x <= radius; x++) {
			if (Math.hypot(Math.max(0, Math.max(x - offsetX, offsetX - x - 1)), Math.max(0, Math.max(y - offsetY, offsetY - y - 1))) < inner - 1e-5) cells.set(x + radius + (y + radius) * side);
		}
		cells.set(radius + radius * side);
		core = mask(cells);
		for (int d = 0; d < 16; d++) {
			int o = (1024 + d * 128 + (reverse ? 1024 : 0)) & 2047;
			cells.clear();
			add(cells, o, 0, 0, 0);
			cells.set(radius + radius * side);
			hull[d] = mask(cells);
			cells.clear();
			for (int coordinate : motion.cells[d]) cells.set((coordinate >> 16) + radius + ((short) coordinate + radius) * side);
			point[d] = mask(cells);
			add(cells, o, motion.x[d], motion.y[d], 0);
			move[d] = mask(cells);
		}
	}
	Mask turn(int from, int to) {
		int change = (to - from + 24 & 15) - 8;
		if (change == 0) return hull[from];
		if (change == -8) return circle;
		Mask cached = turns.get(from * 16 + to);
		if (cached != null) return cached;
		synchronized (this) {
			cached = turns.get(from * 16 + to);
			if (cached != null) return cached;
			BitSet cells = new BitSet(side * side);
			for (int i = 0; i < Math.abs(change); i++) {
				int d = change > 0 ? from + i & 15 : from - i - 1 & 15;
				BitSet arc = arcs[d];
				if (arc == null) {
					arc = arcs[d] = new BitSet(side * side);
					int o = ChartPlotterRouteMoves.OR[d] + (reverse ? 1024 : 0);
					for (int angle = 0; angle <= 128; angle += 16) add(arc, o + angle, 0, 0, geometry.rotationPadding);
				}
				cells.or(arc);
			}
			cached = mask(cells);
			turns.set(from * 16 + to, cached);
			turns.set(to * 16 + from, cached);
			return cached;
		}
	}
	boolean matches(WorldEntityConfig config) {return geometry.matches(config);}
	boolean moveBlocked(ChartPlotterRouteTerrain terrain, int[] moves, int a, int d) {
		int known = 1 << (d + 16);
		int valid = 1 << d;
		if ((moves[a] & known) != 0) return (moves[a] & valid) == 0;
		moves[a] |= known;
		if (!move[d].clear(terrain, a)) return true;
		moves[a] |= valid;
		return false;
	}
	int flag(ChartPlotterCollisionData data, int x, int y) {
		boolean unknown = false;
		for (Mask mask : hull) {
			int f = mask.flag(data, x, y);
			if (f == ChartPlotterCollisionData.OPEN) return f;
			unknown |= f == ChartPlotterCollisionData.UNKNOWN;
		}
		return unknown ? ChartPlotterCollisionData.UNKNOWN : ChartPlotterCollisionData.BLOCKED;
	}
	private void add(BitSet cells, int orientation, double dx, double dy, double padding) {
		geometry.tiles(orientation, offsetX, offsetY, dx, dy, padding, (x, y) -> {
			cells.set(x + radius + (y + radius) * side);
			return ChartPlotterCollisionData.OPEN;
		});
	}
	private Mask mask(BitSet cells) {
		int groups = 0;
		for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(Math.min(i + 64, (i / side + 1) * side))) groups++;
		int[] coordinates = new int[cells.cardinality()];
		int[] rows = new int[groups];
		long[] bits = new long[groups];
		int minX = 0;
		int minY = 0;
		int maxX = 0;
		int maxY = 0;
		int n = 0;
		int count = 0;
		for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1)) {
			int x = i % side - radius;
			int y = i / side - radius;
			coordinates[n++] = x << 16 | y & 65535;
			if (count == 0 || y != (short) rows[count - 1] || x - (rows[count - 1] >> 16) >= 64) rows[count++] = x << 16 | y & 65535;
			bits[count - 1] |= 1L << (x - (rows[count - 1] >> 16));
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
		}
		return new Mask(coordinates, rows, bits, stride, minX, minY, maxX, maxY);
	}
	static final class Mask {
		final int[] offsets;
		final int[] coordinates;
		final int[] rows;
		final long[] bits;
		int stride;
		final int minX;
		final int minY;
		final int maxX;
		final int maxY;
		final int radius;
		Mask(int[] coordinates, int[] rows, long[] bits, int stride, int minX, int minY, int maxX, int maxY) {
			this.coordinates = coordinates;
			this.rows = rows;
			this.bits = bits;
			offsets = new int[rows.length];
			for (int i = 0; i < rows.length; i++) offsets[i] = (rows[i] >> 16) + (short) rows[i] * stride;
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
				for (int i = 0; i < offsets.length; i++) offsets[i] = (rows[i] >> 16) + (short) rows[i] * stride;
			}
			for (int i = 0; i < offsets.length; i++) {
				int b = a + offsets[i];
				int shift = b & 63;
				long value = terrain.open[b >>> 6] >>> shift;
				if (shift != 0) value |= terrain.open[(b >>> 6) + 1] << (64 - shift);
				if ((value & bits[i]) != bits[i]) return false;
			}
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
