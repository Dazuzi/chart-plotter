package com.chartplotter.route;

import com.chartplotter.ChartPlotterRouteEffort;
import com.chartplotter.ChartPlotterTurnPreference;
import com.chartplotter.collision.ChartPlotterCollisionCodec;
import com.chartplotter.collision.ChartPlotterCollisionData;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntityConfig;

import java.awt.geom.Path2D;
import java.util.*;

public final class ChartPlotterRoutingAudit {
	private static final int WARMUPS = 2;
	private static final int SAMPLES = 5;
	private final Set<String> cases;
	private final ChartPlotterRouteEffort[] efforts;
	private final ChartPlotterTurnPreference shape;
	private int passed;
	private ChartPlotterRoutingAudit() {
		String selected = System.getProperty("chartplotter.auditCases");
		cases = selected == null ? null : new LinkedHashSet<>();
		if (cases != null) for (String name : selected.split(",", -1)) cases.add(name.trim());
		efforts = Arrays.stream(System.getProperty("chartplotter.auditEngines", "FAST,BALANCED,MAXIMUM").split(",", -1)).map(String::trim).map(ChartPlotterRouteEffort::valueOf).distinct().toArray(ChartPlotterRouteEffort[]::new);
		shape = ChartPlotterTurnPreference.valueOf(System.getProperty("chartplotter.auditShape", "BALANCED").trim());
	}
	public static void main(String[] args) {
		Locale.setDefault(Locale.ROOT);
		try {
			if (args.length != 0) throw new IllegalArgumentException("Unexpected argument: " + args[0]);
			new ChartPlotterRoutingAudit().benchmark();
		} catch (IllegalArgumentException | AssertionError e) {
			System.err.println("routingAudit FAILED " + e.getMessage());
			System.exit(1);
		}
	}
	private void benchmark() {
		ChartPlotterCollisionCodec.Text bundled = ChartPlotterCollisionCodec.readText(ChartPlotterRoutingAudit.class.getResourceAsStream("/com/chartplotter/collision.txt"));
		if (bundled == null) throw new AssertionError("Bundled collision data must load");
		System.out.printf("routingAudit java=%s data=%s warmups=%d samples=%d%n", System.getProperty("java.version"), bundled.version, WARMUPS, SAMPLES);
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = open(0, 0, 127, 127);
		benchmark("open", new ChartPlotterCollisionData(chunks), 64, 512, 320, 512, 3, ChartPlotterRoute.OK);
		for (int y = 448; y <= 576; y++) block(chunks, 192, y);
		ChartPlotterCollisionData detour = new ChartPlotterCollisionData(chunks);
		benchmark("local-detour", detour, 64, 512, 320, 512, 1, ChartPlotterRoute.OK);
		benchmark("local-detour-speed3", detour, 64, 512, 320, 512, 3, ChartPlotterRoute.OK);
		for (int y = 0; y < 1024; y++) block(chunks, 192, y);
		benchmark("disconnected", new ChartPlotterCollisionData(chunks), 64, 512, 320, 512, 3, ChartPlotterRoute.NO_ROUTE);
		chunks = open(0, 0, 127, 127);
		for (int y = 0; y < 1024; y++) block(chunks, 196, y);
		benchmark("disconnected-interior", new ChartPlotterCollisionData(chunks), 64, 512, 320, 512, 3, ChartPlotterRoute.NO_ROUTE);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(bundled.data);
		benchmark("coastal", data, 2700, 3100, 2736, 3136, 3, ChartPlotterRoute.OK);
		benchmark("coastal-departure", data, 2736, 3136, 2700, 3100, 3, ChartPlotterRoute.OK);
		benchmark("regional", data, 2700, 3100, 2650, 2990, 3, ChartPlotterRoute.OK);
		benchmark("long-crossing", data, 2799, 3405, 2382, 3539, 1, ChartPlotterRoute.OK);
		benchmark("long-crossing-speed3", data, 2799, 3405, 2382, 3539, 3, ChartPlotterRoute.OK);
		if (cases != null && !cases.isEmpty()) throw new IllegalArgumentException("Unknown cases: " + cases);
		System.out.printf("routingAudit passed=%d%n", passed);
	}
	private void benchmark(String name, ChartPlotterCollisionData data, int sx, int sy, int tx, int ty, double speed, int expected) {
		if (cases != null && !cases.remove(name)) return;
		long target = ChartPlotterRoutes.target(data, tx, ty, sx, sy, 10);
		tx = (int) (target >> 32);
		ty = (int) target;
		WorldEntityConfig config = offsetHull();
		String status = expected == ChartPlotterRoute.OK ? "OK" : "NO_ROUTE";
		long[][] elapsed = new long[efforts.length][SAMPLES];
		String[][] results = new String[efforts.length][SAMPLES];
		for (int sample = -WARMUPS; sample < SAMPLES; sample++) for (int i = 0; i < efforts.length; i++) {
			int engine = (i + sample + WARMUPS) % efforts.length;
			ChartPlotterRouteEffort effort = efforts[engine];
			String context = "case=" + name + " effort=" + effort.name() + " shape=" + shape.name() + " sample=" + sample;
			long started = System.nanoTime();
			ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, config, sx, sy, tx, ty, shape.bias, speed, effort.weight, null, () -> System.nanoTime() - started >= effort.nanos);
			ChartPlotterRoute route = search.find();
			long nanos = System.nanoTime() - started;
			if (route.status != expected) throw new AssertionError(context + " expected=" + status + " actual=" + (route.status == ChartPlotterRoute.OK ? "OK" : route.status == ChartPlotterRoute.PENDING ? "TIMED_OUT" : route.text()));
			if (route.status == ChartPlotterRoute.OK) {
				if (!route.valid(data, () -> false)) throw new AssertionError(context + " invalid_route");
				int[] clips = clips(data, config, route);
				if (clips[0] != 0 || clips[1] != 0) throw new AssertionError(context + " hull_clips=" + Arrays.toString(clips));
			}
			if (sample < 0) continue;
			elapsed[engine][sample] = nanos;
			results[engine][sample] = String.format("cost=%d length=%.3f turns=%d expanded=%d peak_open=%d array_mib=%.3f", search.routeCost, length(route), turns(route), search.expanded, search.heap == null ? 0 : search.heap.peak, search.bytes() / 1048576.0);
		}
		for (int i = 0; i < efforts.length; i++) {
			long[] sorted = elapsed[i].clone();
			Arrays.sort(sorted);
			int median = 0;
			while (elapsed[i][median] != sorted[SAMPLES / 2]) median++;
			System.out.printf("case=%s effort=%s shape=%s speed=%.2f status=%s median_ms=%.3f max_ms=%.3f %s%n", name, efforts[i].name(), shape.name(), speed, status, sorted[SAMPLES / 2] / 1e6, sorted[SAMPLES - 1] / 1e6, results[i][median]);
			passed++;
		}
	}
	static int[] clips(ChartPlotterCollisionData data, WorldEntityConfig config, ChartPlotterRoute route) {
		if (route.n == 0) throw new AssertionError("Empty route");
		int[] clips = new int[2];
		int previous = route.heading;
		if (route.n == 1) {
			clips[0] = 1;
			for (int d = 0; d < (previous < 0 ? 16 : 1); d++) {
				int orientation = previous < 0 ? d * 128 : previous;
				if (!hullClip(data, config, route.x[0] + route.offsetX, route.y[0] + route.offsetY, orientation)) {clips[0] = 0; break;}
			}
		}
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
