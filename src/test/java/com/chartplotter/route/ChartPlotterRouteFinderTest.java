package com.chartplotter.route;

import com.chartplotter.collision.ChartPlotterCollisionData;
import net.runelite.api.WorldEntityConfig;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.*;

public class ChartPlotterRouteFinderTest {
	@Test
	public void reachesOpenWaypointExactly() {
		ChartPlotterCollisionData data = open();
		ChartPlotterSparseNodes.Snapshot sparse = new ChartPlotterSparseNodes.Snapshot(new int[]{50}, new int[]{20});
		ChartPlotterRoute route = ChartPlotterRouteFinder.find(data, null, -1, 0, 0, 40, 5, 5, false, 175, sparse, 80, () -> false);
		assertExact(route);
	}
	@Test
	public void reachesWaypointAcrossNearbyBarrier() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = openChunks();
		int barrierX = 20;
		for (int y = -20; y <= 20; y++) {
			long key = ChartPlotterCollisionData.key(barrierX >> 3, y >> 3);
			long mask = 1L << ((barrierX & 7) + ((y & 7) << 3));
			chunks.compute(key, (ignored, chunk) -> new ChartPlotterCollisionData.Chunk(-1L, (chunk == null ? 0 : chunk.blocked) | mask));
		}
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterSparseNodes.Snapshot sparse = new ChartPlotterSparseNodes.Snapshot(new int[]{0, 30, 30}, new int[]{30, 30, 0});
		ChartPlotterRoute route = ChartPlotterRouteFinder.find(data, null, -1, 0, 0, 21, 0, 5, false, 175, sparse, 80, () -> false);
		assertExact(route);
	}
	@Test
	public void sparseLinksPreserveExactBoundaryAcrossNegativeBuckets() {
		ChartPlotterSparseNodes.Snapshot nodes = new ChartPlotterSparseNodes.Snapshot(new int[]{-64, 64}, new int[]{0, 0});
		ChartPlotterSparseRouteFinder.Path path = ChartPlotterSparseRouteFinder.path(open(), nodes, -192, 0, 192, 0, 80, () -> false);
		assertEquals(4, path.n);
		assertArrayEquals(new int[]{-192, -64, 64, 192}, path.x);
		assertArrayEquals(new int[]{0, 0, 0, 0}, path.y);
	}
	@Test
	public void cancellationStopsBeforeSparseIndexWork() {
		ChartPlotterSparseNodes.Snapshot nodes = new ChartPlotterSparseNodes.Snapshot(new int[]{-64, 64}, new int[]{0, 0});
		assertTrue(ChartPlotterSparseRouteFinder.path(open(), nodes, -128, 0, 128, 0, 80, () -> true).pending);
	}
	@Test
	public void spatialSparseGraphMatchesBruteForceCosts() {
		int[] x = new int[49];
		int[] y = new int[49];
		int n = 0;
		for (int wx = -192; wx <= 192; wx += 64) {
			for (int wy = -192; wy <= 192; wy += 64) {
				x[n] = wx;
				y[n++] = wy;
			}
		}
		ChartPlotterSparseNodes.Snapshot nodes = new ChartPlotterSparseNodes.Snapshot(x, y);
		ChartPlotterCollisionData data = open();
		for (int i = 0; i < 16; i++) {
			int sx = -230 + i % 4 * 7;
			int sy = -180 + i / 4 * 120;
			int tx = 230 - i % 4 * 9;
			int ty = 180 - i / 4 * 120;
			ChartPlotterSparseRouteFinder.Path path = ChartPlotterSparseRouteFinder.path(data, nodes, sx, sy, tx, ty, 80, () -> false);
			assertEquals(brute(nodes, sx, sy, tx, ty), path.cost);
		}
	}
	@Test
	public void sparseEdgeCacheTracksCollisionSnapshotIdentity() {
		ChartPlotterSparseNodes.Snapshot nodes = new ChartPlotterSparseNodes.Snapshot(new int[]{-64, 64}, new int[]{0, 0});
		assertEquals(4, ChartPlotterSparseRouteFinder.path(open(), nodes, -192, 0, 192, 0, 80, () -> false).n);
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = openChunks();
		long key = ChartPlotterCollisionData.key(0, 0);
		chunks.put(key, new ChartPlotterCollisionData.Chunk(-1L, 1L));
		ChartPlotterCollisionData blocked = new ChartPlotterCollisionData(chunks);
		assertNull(ChartPlotterSparseRouteFinder.path(blocked, nodes, -192, 0, 192, 0, 80, () -> false));
	}
	@Test
	public void targetPerimeterMatchesFullSquareSelection() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = openChunks();
		Random random = new Random(7);
		for (int x = -30; x <= 30; x++) for (int y = -30; y <= 30; y++) if (random.nextInt(4) != 0) block(chunks, x, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		for (int i = 0; i < 100; i++) {
			int tx = random.nextInt(41) - 20;
			int ty = random.nextInt(41) - 20;
			int sx = random.nextInt(81) - 40;
			int sy = random.nextInt(81) - 40;
			assertEquals(targetReference(data, tx, ty, sx, sy), ChartPlotterRoutes.target(new ChartPlotterRouteGrid(data), tx, ty, sx, sy));
		}
	}
	@Test
	public void routeUpdatesPreserveStopCoordinateIdentity() {
		ChartPlotterRoute route = ChartPlotterRoute.pending(0, 0, 10, 20, 0, 250);
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 10, 20, route);
		assertSame(trip.stopKey(), trip.route(0, route).stopKey());
		assertNotSame(trip.stopKey(), trip.move(2, 0, 11, 20).stopKey());
	}
	@Test
	public void routeGridReusesGenerationClearedThreadBuffers() {
		ChartPlotterCollisionData open = open();
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = openChunks();
		chunks.put(ChartPlotterCollisionData.key(0, 0), new ChartPlotterCollisionData.Chunk(-1L, 1L));
		ChartPlotterCollisionData blocked = new ChartPlotterCollisionData(chunks);
		ChartPlotterRouteGrid.Footprint footprint = new ChartPlotterRouteGrid.Footprint(config());
		ChartPlotterRouteBounds bounds = new ChartPlotterRouteBounds(-10, -10, 10, 10);
		ChartPlotterRouteGrid first = null;
		for (int i = 0; i < 70; i++) {
			ChartPlotterRouteGrid grid = ChartPlotterRouteGrid.lazy((i & 1) == 0 ? blocked : open, footprint, 2, 1);
			grid.cache(bounds, 1 << 20);
			if (first == null) first = grid;
			else {
				assertSame(first.raw, grid.raw);
				assertSame(first.cached, grid.cached);
				assertSame(first.cachedDirs, grid.cachedDirs);
			}
			int expected = (i & 1) == 0 ? ChartPlotterCollisionData.BLOCKED : ChartPlotterCollisionData.OPEN;
			assertEquals(expected, grid.flag(0, 0));
			assertEquals(expected, grid.flag(0, 0, 0));
		}
	}
	@Test
	public void moveCachesInvalidateAcrossGenerationWrap() {
		ChartPlotterRouteBounds bounds = new ChartPlotterRouteBounds(0, 0, 2, 2);
		ChartPlotterRouteWork.MoveCache moves = new ChartPlotterRouteWork.MoveCache();
		ChartPlotterRouteWork.BaseMoveCache base = new ChartPlotterRouteWork.BaseMoveCache();
		for (int i = 0; i < 70; i++) {
			moves.reset(bounds);
			base.reset(bounds, null);
			assertEquals(LongIntMap.MISS, moves.get(0, 0, 0));
			assertEquals(LongIntMap.MISS, base.get(0, 0, 0));
			moves.put(0, 0, 0, 1);
			base.put(0, 0, 0, 1);
			assertEquals(1, moves.get(0, 0, 0));
			assertEquals(1, base.get(0, 0, 0));
		}
	}
	@Test
	public void sparseCorridorReusesClearedThreadMask() {
		ChartPlotterSparseNodes.Snapshot nodes = new ChartPlotterSparseNodes.Snapshot(new int[]{-64, 64}, new int[]{0, 0});
		ChartPlotterSparseRouteFinder.Path path = ChartPlotterSparseRouteFinder.path(open(), nodes, -192, 0, 192, 0, 80, () -> false);
		ChartPlotterSparseRouteFinder.Corridor first = ChartPlotterSparseRouteFinder.corridor(path, 20, () -> false);
		assertNotNull(first);
		int cells = first.cells;
		for (int i = 0; i < 300; i++) {
			ChartPlotterSparseRouteFinder.Corridor next = ChartPlotterSparseRouteFinder.corridor(path, 20, () -> false);
			assertNotNull(next);
			assertSame(first.mask, next.mask);
			assertEquals(cells, next.cells);
		}
	}
	private static int brute(ChartPlotterSparseNodes.Snapshot nodes, int sx, int sy, int tx, int ty) {
		int n = nodes.x.length + 2;
		int[] cost = new int[n];
		boolean[] done = new boolean[n];
		Arrays.fill(cost, Integer.MAX_VALUE);
		cost[0] = 0;
		for (;;) {
			int a = -1;
			for (int i = 0; i < n; i++) if (!done[i] && cost[i] != Integer.MAX_VALUE && (a < 0 || cost[i] < cost[a])) a = i;
			if (a == 1) return cost[a];
			done[a] = true;
			int ax = x(nodes, a, sx, tx);
			int ay = y(nodes, a, sy, ty);
			for (int b = 1; b < n; b++) {
				if (done[b] || b == a) continue;
				int bx = x(nodes, b, sx, tx);
				int by = y(nodes, b, sy, ty);
				int link = a < 2 || b < 2 ? 192 : 128;
				if (ChartPlotterRouteUtil.dist(ax, ay, bx, by) > link) continue;
				cost[b] = Math.min(cost[b], cost[a] + ChartPlotterRouteUtil.h(ax, ay, bx, by));
			}
		}
	}
	private static int x(ChartPlotterSparseNodes.Snapshot nodes, int i, int start, int target) {return i == 0 ? start : i == 1 ? target : nodes.x[i - 2];}
	private static int y(ChartPlotterSparseNodes.Snapshot nodes, int i, int start, int target) {return i == 0 ? start : i == 1 ? target : nodes.y[i - 2];}
	private static long targetReference(ChartPlotterCollisionData data, int tx, int ty, int sx, int sy) {
		int f = data.flagAt(tx, ty);
		if (f == ChartPlotterCollisionData.UNKNOWN || f == ChartPlotterCollisionData.OPEN) return ChartPlotterCollisionData.key(tx, ty);
		int bx = tx;
		int by = ty;
		long best = Long.MAX_VALUE;
		for (int r = 1; r <= 10; r++) {
			for (int y = ty - r; y <= ty + r; y++) {
				for (int x = tx - r; x <= tx + r; x++) {
					if (Math.max(Math.abs(x - tx), Math.abs(y - ty)) != r || data.flagAt(x, y) != ChartPlotterCollisionData.OPEN) continue;
					long dx = x - sx;
					long dy = y - sy;
					long score = dx * dx + dy * dy;
					if (score >= best) continue;
					bx = x;
					by = y;
					best = score;
				}
			}
			if (best != Long.MAX_VALUE) return ChartPlotterCollisionData.key(bx, by);
		}
		return ChartPlotterCollisionData.key(tx, ty);
	}
	private static void block(Map<Long, ChartPlotterCollisionData.Chunk> chunks, int x, int y) {
		long key = ChartPlotterCollisionData.key(x >> 3, y >> 3);
		long mask = 1L << ((x & 7) + ((y & 7) << 3));
		chunks.compute(key, (k, old) -> new ChartPlotterCollisionData.Chunk(-1L, (old == null ? 0 : old.blocked) | mask));
	}
	private static ChartPlotterCollisionData open() {
		return new ChartPlotterCollisionData(openChunks());
	}
	private static Map<Long, ChartPlotterCollisionData.Chunk> openChunks() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = new HashMap<>();
		for (int x = -32; x <= 32; x++) for (int y = -32; y <= 32; y++) chunks.put(ChartPlotterCollisionData.key(x, y), new ChartPlotterCollisionData.Chunk(-1L, 0));
		return chunks;
	}
	private static WorldEntityConfig config() {
		return new WorldEntityConfig() {
			public int getId() {return 1;}
			public int getCategory() {return 1;}
			public int getBoundsX() {return 0;}
			public int getBoundsY() {return 0;}
			public int getBoundsWidth() {return 128;}
			public int getBoundsHeight() {return 128;}
		};
	}
	private static void assertExact(ChartPlotterRoute route) {
		assertEquals(ChartPlotterRoute.OK, route.status);
		assertTrue(route.n > 0);
		assertEquals(route.tx, route.x[route.n - 1]);
		assertEquals(route.ty, route.y[route.n - 1]);
	}
}
