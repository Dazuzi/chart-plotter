package com.chartplotter;
import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionCodec;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.route.ChartPlotterRoute;
import com.chartplotter.route.ChartPlotterRouteChecks;
import com.chartplotter.route.ChartPlotterRoutes;
import com.chartplotter.route.ChartPlotterRouteFinder;
import com.chartplotter.route.ChartPlotterSparseCodec;
import com.chartplotter.route.ChartPlotterSparseNodes;
import com.chartplotter.runtime.ChartPlotterFeatures;
import com.chartplotter.runtime.ChartPlotterProjection;
import com.chartplotter.runtime.ChartPlotterWorldMap;
import com.chartplotter.util.ChartPlotterMath;
import java.io.File;
import java.io.InputStream;
import java.awt.Rectangle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Point;
public final class ChartPlotterChecks {
	private ChartPlotterChecks() {}
	public static void main(String[] args) throws Exception {
		math();
		route();
		ChartPlotterRouteChecks.run();
		routeFinderProbe();
		projection();
		collision();
		sparse();
		features();
		worldMap();
	}
	private static void math() {
		eq(0, ChartPlotterMath.norm(0));
		eq(0, ChartPlotterMath.norm(2048));
		eq(2047, ChartPlotterMath.norm(-1));
		eq(1, ChartPlotterMath.angleDir(0, 128, 0));
		eq(-1, ChartPlotterMath.angleDir(128, 0, 0));
		eq(90.0, ChartPlotterMath.orientationToDegrees(1024));
		eq(32, ChartPlotterMath.snap(31));
		eq(-32, ChartPlotterMath.snap(-31));
		eq(0.5, ChartPlotterMath.speed(64, 0));
	}
	private static void route() {
		eq("Charting course", ChartPlotterRoute.pending(0, 0, 1, 1, 0, 100).text());
		eq("Uncharted waters", ChartPlotterRoute.uncharted(0, 0, 1, 1, 0, 100).text());
		eq("Not sailable", ChartPlotterRoute.blocked(0, 0, 1, 1, 0, 100).text());
		eq("No route found", ChartPlotterRoute.none(0, 0, 1, 1, 0, 100).text());
		eq("Route too complex", ChartPlotterRoute.complex(0, 0, 1, 1, 0, 100).text());
		ChartPlotterRoute r = ChartPlotterRoute.ok(0, 0, 0, 20, new int[]{0, 0, 0}, new int[]{0, 10, 20}, 3, 0, 100);
		ChartPlotterRoute a = r.advance(0, 8, 8, 24, 12);
		if (a == null) fail();
		eq(2, a.n);
		eq(10, a.y[0]);
		eq(20, a.y[1]);
		ChartPlotterRoutes.Turn t0 = ChartPlotterRoutes.turn(r, 0, 0, 1, 0, 1);
		yes(t0.valid);
		eq(0, t0.x);
		eq(10, t0.y);
		eq(10, t0.ticks);
		ChartPlotterRoute corner = ChartPlotterRoute.ok(0, 10, 10, 10, new int[]{0, 10}, new int[]{10, 10}, 2, 0, 100);
		ChartPlotterRoutes.Turn t1 = ChartPlotterRoutes.turn(corner, 0, 8, 1, 0, 1);
		yes(t1.valid);
		eq(10, t1.x);
		eq(10, t1.y);
		eq(11, t1.ticks);
		eq(-1, ChartPlotterRoutes.turn(corner, 0, 8, 0, 0, 1).ticks);
		no(ChartPlotterRoutes.turn(null, 0, 0, 1, 0, 1).valid);
	}
	private static void routeFinderProbe() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = open();
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterSparseNodes.Snapshot nodes = new ChartPlotterSparseNodes.Snapshot(new int[]{100, 164, 228, 292, 340}, new int[]{100, 100, 100, 100, 100});
		route(ChartPlotterRouteFinder.find(data, null, -1, 100, 100, 340, 100, 0, false, 125, 2, nodes, 100, () -> false), ChartPlotterRoute.OK, 100, 100, 111, 105, 120, 101, 280, 101, 340, 101);
		route(ChartPlotterRouteFinder.find(data, null, 512, 100, 100, 340, 100, 0, true, 125, 2, nodes, 100, () -> false), ChartPlotterRoute.OK, 100, 100, 252, 100, 342, 100);
		route(ChartPlotterRouteFinder.find(data, null, -1, 100, 100, 340, 100, 0, false, 250, 2, nodes, 100, () -> false), ChartPlotterRoute.OK, 100, 100, 111, 105, 271, 105, 321, 105, 330, 101, 340, 101);
		route(ChartPlotterRouteFinder.find(data, null, 512, 100, 100, 340, 100, 4, false, 125, 2, nodes, 100, () -> false), ChartPlotterRoute.OK, 100, 100, 340, 100);
		route(ChartPlotterRouteFinder.find(data, null, -1, 100, 100, 340, 100, 0, false, 125, 2, nodes, 100, () -> true), ChartPlotterRoute.PENDING);
		Map<Long, ChartPlotterCollisionData.Chunk> blocked = open();
		block(blocked, 220, 100);
		route(ChartPlotterRouteFinder.find(new ChartPlotterCollisionData(blocked), null, -1, 100, 100, 340, 100, 0, false, 125, 2, nodes, 100, () -> false), ChartPlotterRoute.NO_ROUTE);
		Map<Long, ChartPlotterCollisionData.Chunk> unknown = open();
		unknown.remove(ChartPlotterCollisionData.key(220 >> 3, 100 >> 3));
		route(ChartPlotterRouteFinder.find(new ChartPlotterCollisionData(unknown), null, -1, 100, 100, 340, 100, 0, false, 125, 2, nodes, 100, () -> false), ChartPlotterRoute.NO_ROUTE);
	}
	private static Map<Long, ChartPlotterCollisionData.Chunk> open() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = new HashMap<>();
		for (int x = 0; x < 64; x++) {
			for (int y = 0; y < 64; y++) chunks.put(ChartPlotterCollisionData.key(x, y), new ChartPlotterCollisionData.Chunk(-1L, 0));
		}
		return chunks;
	}
	private static void block(Map<Long, ChartPlotterCollisionData.Chunk> chunks, int x, int y) {
		long k = ChartPlotterCollisionData.key(x >> 3, y >> 3);
		long b = 1L << ((x & 7) + ((y & 7) << 3));
		chunks.compute(k, (kk, c) -> {
			if (c == null) c = new ChartPlotterCollisionData.Chunk(0, 0);
			return new ChartPlotterCollisionData.Chunk(c.known, c.blocked | b);
		});
	}
	private static void projection() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = open();
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		int p = 100 * 128 + 64;
		path(ChartPlotterProjection.fixture(data, p, p, 512, 0, 4, 0, 0, 0, 4, false, false, false), false, Integer.MAX_VALUE, 12864, 12864, 0);
		path(ChartPlotterProjection.fixture(data, p, p, 0, 128, 4, 1, 0, 0, 4, false, false, false), false, Integer.MAX_VALUE, 12864, 12864, 128);
		path(ChartPlotterProjection.fixture(data, p, p, 0, 0, 4, 0, 0, 0.5, 2, false, false, false), false, Integer.MAX_VALUE, 12864, 12864, 0, 12864, 12800, 0, 12864, 12672, 0, 12864, 12480, 0, 12864, 12224, 0);
		path(ChartPlotterProjection.fixture(data, p, p, 0, 0, 3, 0, 1, 0.5, 2, true, false, false), false, Integer.MAX_VALUE, 12864, 12864, 0, 12864, 13056, 0, 12864, 13312, 0, 12864, 13568, 0);
		Map<Long, ChartPlotterCollisionData.Chunk> blocked = open();
		block(blocked, 100, 98);
		path(ChartPlotterProjection.fixture(new ChartPlotterCollisionData(blocked), p, p, 0, 0, 4, 0, 1, 0, 1, false, true, false), true, 2, 12864, 12864, 0, 12864, 12800, 0);
		ChartPlotterProjection.Path ext = ChartPlotterProjection.fixture(new ChartPlotterCollisionData(blocked), p, p, 0, 0, 4, 0, 1, 0, 1, false, true, true);
		path(ext, true, 2, 12864, 12864, 0, 12864, 12800, 0, 12864, 12736, 0, 12864, 12608, 0, 12864, 12480, 0, 12864, 12352, 0);
		Map<Long, ChartPlotterCollisionData.Chunk> unknown = open();
		unknown.remove(ChartPlotterCollisionData.key(100 >> 3, 98 >> 3));
		ChartPlotterProjection.Path unk = ChartPlotterProjection.fixture(new ChartPlotterCollisionData(unknown), p, p, 0, 0, 4, 0, 1, 0, 1, false, true, false);
		path(unk, false, Integer.MAX_VALUE, 12864, 12864, 0, 12864, 12736, 0, 12864, 12608, 0, 12864, 12480, 0, 12864, 12352, 0);
		eq(1, ChartPlotterProjection.match(ext, unk));
	}
	private static void path(ChartPlotterProjection.Path p, boolean blocked, int blockedAt, int... v) {
		eq(blocked, p.blocked);
		eq(blockedAt, p.blockedAt);
		eq(v.length / 3, p.n);
		for (int i = 0; i < p.n; i++) {
			eq(v[i * 3], p.x[i]);
			eq(v[i * 3 + 1], p.y[i]);
			eq(v[i * 3 + 2], p.o[i]);
		}
	}
	private static void route(ChartPlotterRoute r, int status, int... p) {
		eq(status, r.status);
		eq(p.length / 2, r.n);
		for (int i = 0; i < r.n; i++) {
			eq(p[i * 2], r.x[i]);
			eq(p[i * 2 + 1], r.y[i]);
		}
	}
	private static void collision() throws Exception {
		long k = ChartPlotterCollisionData.key(0x12345678, 0x76543210);
		eq(0x12345678, (int) (k >> 32));
		eq(0x76543210, (int) k);
		ChartPlotterCollisionData.Chunk c = new ChartPlotterCollisionData.Chunk(3, 2);
		eq(ChartPlotterCollisionCache.OPEN, c.flag(0));
		eq(ChartPlotterCollisionCache.BLOCKED, c.flag(1));
		eq(ChartPlotterCollisionCache.UNKNOWN, c.flag(2));
		File dir = Files.createTempDirectory("chart-plotter-collision").toFile();
		File file = new File(dir, "collision.bin");
		Map<Long, ChartPlotterCollisionData.Chunk> data = new HashMap<>();
		data.put(ChartPlotterCollisionData.key(3, 4), c);
		yes(ChartPlotterCollisionCodec.write(dir, file, data));
		Map<Long, ChartPlotterCollisionData.Chunk> read = ChartPlotterCollisionCodec.read(file);
		eq(1, read.size());
		eq(ChartPlotterCollisionCache.BLOCKED, read.get(ChartPlotterCollisionData.key(3, 4)).flag(1));
		try (InputStream in = ChartPlotterChecks.class.getResourceAsStream("/com/chartplotter/collision.txt")) {
			if (in == null) fail();
			Map<Long, ChartPlotterCollisionData.Chunk> text = ChartPlotterCollisionCodec.readText(in);
			File out = new File(dir, "collision-text.bin");
			yes(!text.isEmpty());
			yes(ChartPlotterCollisionCodec.write(dir, out, text));
			same(text, ChartPlotterCollisionCodec.read(out));
			Files.delete(out.toPath());
		}
		Files.delete(file.toPath());
		Files.delete(dir.toPath());
	}
	private static void same(Map<Long, ChartPlotterCollisionData.Chunk> a, Map<Long, ChartPlotterCollisionData.Chunk> b) {
		eq(a.size(), b.size());
		for (Map.Entry<Long, ChartPlotterCollisionData.Chunk> e : a.entrySet()) {
			ChartPlotterCollisionData.Chunk c = b.get(e.getKey());
			if (c == null) fail();
			eq(e.getValue().known, c.known);
			eq(e.getValue().blocked, c.blocked);
		}
	}
	private static void sparse() throws Exception {
		try (InputStream in = ChartPlotterChecks.class.getResourceAsStream("/com/chartplotter/sparse-nodes.txt")) {
			if (in == null) fail();
			Matcher m = Pattern.compile("\\d+").matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			int n = 0;
			while (m.find()) n++;
			eq(558, n / 2);
		}
		File dir = Files.createTempDirectory("chart-plotter-sparse").toFile();
		File file = new File(dir, "sparse.bin");
		ChartPlotterSparseNodes.Snapshot nodes = new ChartPlotterSparseNodes.Snapshot(new int[]{1, 3}, new int[]{2, 4});
		yes(ChartPlotterSparseCodec.write(dir, file, nodes));
		ChartPlotterSparseNodes.Snapshot read = ChartPlotterSparseCodec.read(file);
		if (read == null) fail();
		eq(2, read.x.length);
		eq(3, read.x[1]);
		eq(4, read.y[1]);
		Files.delete(file.toPath());
		Files.delete(dir.toPath());
	}
	private static void features() {
		for (int world = 0; world < 2; world++) {
			for (int minimap = 0; minimap < 2; minimap++) {
				for (int worldMap = 0; worldMap < 2; worldMap++) {
					for (ChartPlotterCacheOverlay cache : ChartPlotterCacheOverlay.values()) {
						for (int edit = 0; edit < 2; edit++) {
							for (int turn = 0; turn < 2; turn++) features(world != 0, minimap != 0, worldMap != 0, cache, edit != 0, turn != 0);
						}
					}
				}
			}
		}
		ChartPlotterFeatures off = ChartPlotterFeatures.off();
		no(off.routes);
		no(off.cacheView);
		no(off.edit);
		no(off.nextTurn);
		no(off.worldOverlay);
		no(off.minimapOverlay);
		no(off.worldMapOverlay);
		no(off.input);
		no(off.tracking);
		no(off.cache(true));
		ChartPlotterFeatures world = ChartPlotterFeatures.of(true, false, false, ChartPlotterCacheOverlay.OFF, false, false);
		yes(world.routes);
		yes(world.worldOverlay);
		yes(world.input);
		yes(world.tracking);
		no(world.cache(false));
		yes(world.cache(true));
		ChartPlotterFeatures cache = ChartPlotterFeatures.of(false, false, false, ChartPlotterCacheOverlay.BOTH, false, false);
		no(cache.routes);
		yes(cache.cacheView);
		yes(cache.worldOverlay);
		yes(cache.worldMapOverlay);
		no(cache.input);
		yes(cache.tracking);
		no(cache.cache(false));
		yes(cache.cache(true));
		ChartPlotterFeatures edit = ChartPlotterFeatures.of(false, false, false, ChartPlotterCacheOverlay.OFF, true, false);
		no(edit.routes);
		yes(edit.edit);
		yes(edit.worldMapOverlay);
		yes(edit.input);
		yes(edit.tracking);
		yes(edit.cache(false));
		ChartPlotterFeatures turn = ChartPlotterFeatures.of(false, false, false, ChartPlotterCacheOverlay.OFF, false, true);
		no(turn.routes);
		yes(turn.nextTurn);
		yes(turn.worldOverlay);
		no(turn.minimapOverlay);
		no(turn.worldMapOverlay);
		no(turn.input);
		yes(turn.tracking);
		no(turn.cache(false));
		no(turn.cache(true));
	}
	private static void features(boolean world, boolean minimap, boolean worldMap, ChartPlotterCacheOverlay cache, boolean edit, boolean nextTurn) {
		ChartPlotterFeatures f = ChartPlotterFeatures.of(world, minimap, worldMap, cache, edit, nextTurn);
		boolean routes = world || minimap || worldMap;
		boolean cacheView = cache != ChartPlotterCacheOverlay.OFF;
		eq(routes, f.routes);
		eq(cacheView, f.cacheView);
		eq(edit, f.edit);
		eq(nextTurn, f.nextTurn);
		eq(world || cache.world || nextTurn, f.worldOverlay);
		eq(minimap, f.minimapOverlay);
		eq(worldMap || cache.worldMap || edit, f.worldMapOverlay);
		eq(routes || edit, f.input);
		eq(routes || cacheView || edit || nextTurn, f.tracking);
		eq(edit, f.cache(false));
		eq(edit || routes || cacheView, f.cache(true));
	}
	private static void worldMap() {
		ChartPlotterWorldMap map = new ChartPlotterWorldMap(null);
		ChartPlotterWorldMap.State s = new ChartPlotterWorldMap.State(null, 4, new Rectangle(10, 20, 200, 100), 50, 25, new Point(3000, 3000), 2, 0, 0);
		Point p = map.point(s, 3001, 3002, 0.5, 0.5);
		double[] w = map.world(p, s);
		eq(3001, (int) Math.floor(w[0]));
		eq(3002, (int) Math.floor(w[1]));
	}
	private static void eq(long a, long b) {if (a != b) fail();}
	private static void eq(int a, int b) {if (a != b) fail();}
	private static void eq(double a, double b) {if (Double.compare(a, b) != 0) fail();}
	private static void eq(boolean a, boolean b) {if (a != b) fail();}
	private static void eq(String a, String b) {if (!a.equals(b)) fail();}
	private static void yes(boolean v) {if (!v) fail();}
	private static void no(boolean v) {if (v) fail();}
	private static void fail() {throw new AssertionError();}
}
