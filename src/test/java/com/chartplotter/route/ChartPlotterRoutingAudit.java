package com.chartplotter.route;

import com.chartplotter.ChartPlotterRouteEffort;
import com.chartplotter.collision.ChartPlotterCollisionCodec;
import com.chartplotter.collision.ChartPlotterCollisionData;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntityConfig;

import java.awt.geom.Path2D;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

public final class ChartPlotterRoutingAudit {
	private ChartPlotterRoutingAudit() {}
	public static void main(String[] args) {
		Locale.setDefault(Locale.ROOT);
		if (args.length == 0 || args[0].equals("synthetic")) {
			ChartPlotterCollisionData data = new ChartPlotterCollisionData(open(-4, -4, 20, 4));
			for (int[] target : new int[][]{{40, 5}, {100, 1}, {0, 0}}) {
				long started = System.nanoTime();
				ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, offsetHull(), 0, 0, target[0], target[1], 5, 1, 100, null, () -> false);
				ChartPlotterRoute route = search.find();
				System.out.printf("destination=%d,%d status=%d length=%.3f turns=%d ms=%.3f%n", target[0], target[1], route.status, length(route), turns(route), (System.nanoTime() - started) / 1e6);
				geometry("synthetic", route);
			}
		} else if (args[0].equals("benchmark")) benchmark();
		else throw new IllegalArgumentException("Expected synthetic or benchmark");
	}
	private static void benchmark() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = open(0, 0, 127, 127);
		benchmark("open", new ChartPlotterCollisionData(chunks), 64, 512, 320, 512, 3);
		for (int y = 448; y <= 576; y++) block(chunks, 192, y);
		ChartPlotterCollisionData detour = new ChartPlotterCollisionData(chunks);
		benchmark("local-detour", detour, 64, 512, 320, 512, 1);
		benchmark("local-detour-speed3", detour, 64, 512, 320, 512, 3);
		for (int y = 0; y < 1024; y++) block(chunks, 192, y);
		benchmark("disconnected", new ChartPlotterCollisionData(chunks), 64, 512, 320, 512, 3);
		chunks = open(0, 0, 127, 127);
		for (int y = 0; y < 1024; y++) block(chunks, 196, y);
		benchmark("disconnected-interior", new ChartPlotterCollisionData(chunks), 64, 512, 320, 512, 3);
		ChartPlotterCollisionCodec.Text bundled = ChartPlotterCollisionCodec.readText(ChartPlotterRoutingAudit.class.getResourceAsStream("/com/chartplotter/collision.txt"));
		if (bundled == null) throw new AssertionError("Bundled collision data must load");
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(bundled.data);
		benchmark("coastal", data, 2700, 3100, 2736, 3136, 3);
		benchmark("coastal-departure", data, 2736, 3136, 2700, 3100, 3);
		benchmark("regional", data, 2700, 3100, 2650, 2990, 3);
		benchmark("long-crossing", data, 2799, 3405, 2382, 3539, 1);
		benchmark("long-crossing-speed3", data, 2799, 3405, 2382, 3539, 3);
	}
	private static void benchmark(String name, ChartPlotterCollisionData data, int sx, int sy, int tx, int ty, double speed) {
		if (!Arrays.asList(System.getProperty("chartplotter.auditCases", name).split(",")).contains(name)) return;
		System.out.printf("case=%s java=%s chunks=%d speed=%.2f%n", name, System.getProperty("java.version"), data.size(), speed);
		int bias = com.chartplotter.ChartPlotterTurnPreference.valueOf(System.getProperty("chartplotter.auditShape", "BALANCED")).bias;
		WorldEntityConfig config = offsetHull();
		benchmark(data, config, (weight, cancel) -> new ChartPlotterRouteFinder(data, config, sx, sy, tx, ty, bias, speed, weight, null, cancel));
	}
	private static void benchmark(ChartPlotterCollisionData data, WorldEntityConfig config, BiFunction<Integer, BooleanSupplier, ChartPlotterRouteFinder> create) {
		System.out.println("engine,first_ms,median_ms,max_ms,completed,status,cost,length,turns,expanded,peak_open,array_mib");
		for (String name : System.getProperty("chartplotter.auditEngines", "FAST,BALANCED,MAXIMUM").split(",")) {
			ChartPlotterRouteEffort effort = ChartPlotterRouteEffort.valueOf(name);
			double[] elapsed = new double[6];
			ChartPlotterRouteFinder search = null;
			ChartPlotterRoute route = null;
			int completed = 0;
			for (int i = 0; i < elapsed.length; i++) {
				long started = System.nanoTime();
				search = create.apply(effort.weight, () -> System.nanoTime() - started > effort.nanos);
				route = search.find();
				elapsed[i] = (System.nanoTime() - started) / 1e6;
				if (route.status == ChartPlotterRoute.OK) {
					completed++;
					if (!route.valid(data, () -> false)) throw new AssertionError("Invalid route");
				}
			}
			double first = elapsed[0];
			Arrays.sort(elapsed, 1, elapsed.length);
			System.out.printf("%s,%.3f,%.3f,%.3f,%d/6,%d,%d,%.3f,%d,%d,%d,%.3f%n", name, first, elapsed[3], Math.max(first, elapsed[5]), completed, route.status, search.routeCost, length(route), turns(route), search.expanded, search.heap == null ? 0 : search.heap.peak, search.bytes() / 1048576.0);
			geometry(name, route);
			if (route.status == ChartPlotterRoute.OK && config != null) {
				int[] clips = clips(data, config, route);
				System.out.printf("hull_clips,%s,segments=%d,turns=%d,independent_validation=%s%n", name, clips[0], clips[1], clips[0] == 0 && clips[1] == 0);
				if (clips[0] != 0 || clips[1] != 0) throw new AssertionError("Hull clips an obstacle");
			}
		}
	}
	static int[] clips(ChartPlotterCollisionData data, WorldEntityConfig config, ChartPlotterRoute route) {
		int[] clips = new int[2];
		int previous = route.heading;
		for (int i = 1; i < route.n; i++) {
			int dx = route.x[i] - route.x[i - 1];
			int dy = route.y[i] - route.y[i - 1];
			int d = route.motion.dir(dx, dy);
			if (d < 0) throw new AssertionError("Unsupported route bearing");
			int orientation = ChartPlotterRouteMoves.OR[d] + (route.hull.reverse ? 1024 : 0) & 2047;
			if (previous < 0) previous = orientation;
			int delta = (orientation - previous + 3072 & 2047) - 1024;
			int steps = Math.max(1, Math.abs(delta) / 8);
			for (int j = 0; j <= steps; j++) {
				if (hullClip(data, config, route.x[i - 1] + route.offsetX, route.y[i - 1] + route.offsetY, previous + delta * j / steps & 2047)) {clips[1]++; break;}
			}
			steps = Math.max(1, Math.max(Math.abs(dx), Math.abs(dy)) * 8);
			for (int j = 0; j <= steps; j++) {
				if (hullClip(data, config, route.x[i - 1] + route.offsetX + (double) dx * j / steps, route.y[i - 1] + route.offsetY + (double) dy * j / steps, orientation)) {clips[0]++; break;}
			}
			previous = orientation;
		}
		return clips;
	}
	@SuppressWarnings("JavaPrintToLogpoint")
	private static void geometry(String name, ChartPlotterRoute route) {
		StringBuilder text = new StringBuilder("geometry,").append(name).append(',');
		for (int i = 0; i < route.n; i++) text.append(route.x[i]).append(':').append(route.y[i]).append(';');
		System.out.println(text);
	}
	static int turns(ChartPlotterRoute route) {
		int turns = 0;
		for (int i = 2; i < route.n; i++) {
			long ax = (long) route.x[i - 1] - route.x[i - 2];
			long ay = (long) route.y[i - 1] - route.y[i - 2];
			long bx = (long) route.x[i] - route.x[i - 1];
			long by = (long) route.y[i] - route.y[i - 1];
			if (ax * by != ay * bx || ax * bx + ay * by <= 0) turns++;
		}
		return turns;
	}
	static boolean hullClip(ChartPlotterCollisionData data, WorldEntityConfig config, double x, double y, int orientation) {
		double hw = config.getBoundsWidth() / 256.0;
		double hh = config.getBoundsHeight() / 256.0;
		double ox = config.getBoundsX() / 128.0;
		double oy = config.getBoundsY() / 128.0;
		double cos = Perspective.COSINE[orientation] / 65536.0;
		double sin = Perspective.SINE[orientation] / 65536.0;
		Path2D.Double shape = new Path2D.Double();
		for (int corner = 0; corner < 4; corner++) {
			double px = ox + (corner == 0 || corner == 3 ? -hw : hw);
			double py = oy + (corner < 2 ? -hh : hh);
			double rx = x + cos * px + sin * py;
			double ry = y + cos * py - sin * px;
			if (corner == 0) shape.moveTo(rx, ry);
			else shape.lineTo(rx, ry);
		}
		shape.closePath();
		java.awt.geom.Rectangle2D bounds = shape.getBounds2D();
		for (int py = (int) Math.floor(bounds.getMinY()); py < Math.ceil(bounds.getMaxY()); py++) for (int px = (int) Math.floor(bounds.getMinX()); px < Math.ceil(bounds.getMaxX()); px++) {
			if (data.flagAt(px, py) != ChartPlotterCollisionData.OPEN && shape.intersects(px + 1e-9, py + 1e-9, 1 - 2e-9, 1 - 2e-9)) return true;
		}
		return false;
	}
	static Map<Long, ChartPlotterCollisionData.Chunk> open(int minX, int minY, int maxX, int maxY) {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = new HashMap<>();
		for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) chunks.put(ChartPlotterCollisionData.key(x, y), new ChartPlotterCollisionData.Chunk(-1L, 0));
		return chunks;
	}
	static void block(Map<Long, ChartPlotterCollisionData.Chunk> chunks, int x, int y) {
		long key = ChartPlotterCollisionData.key(x >> 3, y >> 3);
		long mask = 1L << ((x & 7) + ((y & 7) << 3));
		chunks.compute(key, (ignored, old) -> new ChartPlotterCollisionData.Chunk(-1L, (old == null ? 0 : old.blocked) | mask));
	}
	static WorldEntityConfig config(int width, int height) {
		return new WorldEntityConfig() {
			public int getId() {return 0;}
			public int getCategory() {return 0;}
			public int getBoundsX() {return 0;}
			public int getBoundsY() {return 0;}
			public int getBoundsWidth() {return width;}
			public int getBoundsHeight() {return height;}
		};
	}
	static double length(ChartPlotterRoute route) {
		double value = 0;
		for (int i = 1; i < route.n; i++) value += Math.hypot(route.x[i] - route.x[i - 1], route.y[i] - route.y[i - 1]);
		return value;
	}
	static WorldEntityConfig offsetHull() {return new WorldEntityConfig() {
		public int getId() {return 3;}
		public int getCategory() {return 2395;}
		public int getBoundsX() {return 0;}
		public int getBoundsY() {return -256;}
		public int getBoundsWidth() {return 384;}
		public int getBoundsHeight() {return 1280;}
	};}
}
