package com.chartplotter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntityConfig;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
public class ChartPlotterRouteFinderTest {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	@Test
	public void chartFindsKnownRoute() {
		Map<Long, int[]> data = known(0, -1, 5, 1);
		ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, null, -1, 0, 0, 5, 0, 5, false, false, false);
		assertEquals(ChartPlotterRoute.OK, r.status);
		assertEquals(0, r.x[0]);
		assertEquals(5, r.x[r.n - 1]);
		assertEquals(0, r.y[r.n - 1]);
	}
	@Test
	public void unknownTargetReportsUncharted() {
		Map<Long, int[]> data = known(0, 0, 0, 0);
		ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, null, -1, 0, 0, 5, 0, 5, false, false, false);
		assertEquals(ChartPlotterRoute.UNCHARTED, r.status);
	}
	@Test
	public void blockedTargetHasNoRoute() {
		Map<Long, int[]> data = known(0, 0, 5, 0);
		put(data, 5, 0, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
		ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, null, -1, 0, 0, 5, 0, 5, false, false, false);
		assertEquals(ChartPlotterRoute.NO_ROUTE, r.status);
	}
	@Test
	public void turnBiasKeepsRouteShort() {
		Map<Long, int[]> data = known(0, 0, 12, 4);
		for (int x = 1; x < 12; x++) put(data, x, 0, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
		ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, null, -1, 0, 0, 12, 0, 10, false, false, false);
		assertEquals(ChartPlotterRoute.OK, r.status);
		assertTrue(r.n <= 5);
	}
	@Test
	public void footprintAvoidsAdjacentBlock() {
		Map<Long, int[]> data = known(-2, -4, 7, 4);
		put(data, 2, 1, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
		ChartPlotterRoute point = ChartPlotterRouteFinder.find(data, null, -1, 0, 0, 5, 0, 5, false, false, false);
		ChartPlotterRoute wide = ChartPlotterRouteFinder.find(data, rect(TS * 2, TS * 2), -1, 0, 0, 5, 0, 5, false, false, false);
		ChartPlotterRoute bi = ChartPlotterRouteFinder.find(data, rect(TS * 2, TS * 2), -1, 0, 0, 5, 0, 5, true, false, false);
		assertEquals(ChartPlotterRoute.OK, point.status);
		assertEquals(ChartPlotterRoute.OK, wide.status);
		assertEquals(ChartPlotterRoute.OK, bi.status);
		assertEquals(2, point.n);
		assertTrue(wide.n > 2);
		assertTrue(bi.n > 2);
	}
	@Test
	public void footprintReportsUnchartedEdge() {
		Map<Long, int[]> data = known(0, 0, 5, 0);
		ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, rect(TS * 2, TS * 2), -1, 0, 0, 5, 0, 5, false, false, false);
		assertEquals(ChartPlotterRoute.UNCHARTED, r.status);
	}
	@Test
	public void currentHeadingEscapesSleeve() {
		Map<Long, int[]> data = known(-4, -3, 8, 3);
		for (int x = -4; x <= 8; x++) {
			put(data, x, -3, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
			put(data, x, 3, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
		}
		ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, rect(TS * 3, 64), 1536, 0, 0, 5, 0, 5, false, false, false);
		assertEquals(ChartPlotterRoute.OK, r.status);
		assertEquals(2, r.n);
	}
	@Test
	public void reverseModeIsExplicit() {
		WorldEntityConfig wc = rectY(TS * 2, TS, TS);
		boolean found = false;
		for (int x = -8; x <= 8 && !found; x++) {
			for (int y = -8; y <= 8 && !found; y++) {
				Map<Long, int[]> data = known(-10, -10, 10, 10);
				put(data, x, y, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
				boolean fwd = ChartPlotterRouteFinder.clear(data, wc, 1536, 0, 0, 2, 0, false);
				boolean rev = ChartPlotterRouteFinder.clear(data, wc, 1536, 0, 0, 2, 0, true);
				found = !fwd && rev;
			}
		}
		assertTrue(found);
	}
	@Test
	public void twoLegDirectAvoidsThinBlock() {
		Map<Long, int[]> data = known(0, -8, 12, 8);
		put(data, 6, 0, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
		ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, null, -1, 0, 0, 12, 0, 5, false, false, false);
		assertEquals(ChartPlotterRoute.OK, r.status);
		assertEquals(3, r.n);
		assertTrue(r.y[1] != 0);
	}
	@Test
	public void retryExpandsBounds() {
		Map<Long, int[]> data = known(-80, -80, 100, 80);
		for (int y = -50; y <= 50; y++) put(data, 10, y, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
		ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, null, -1, 0, 0, 20, 0, 5, false, false, false);
		assertEquals(ChartPlotterRoute.OK, r.status);
		assertTrue(maxAbsY(r) > 37);
	}
	@Test
	public void fastRouteSetsFlag() {
		Map<Long, int[]> data = known(0, -1, 5, 1);
		ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, null, -1, 0, 0, 5, 0, 5, false, false, true);
		assertEquals(ChartPlotterRoute.OK, r.status);
		assertTrue(r.fast);
	}
	@Test
	public void routeAdvanceTrimsPassedWaypoints() {
		ChartPlotterRoute r = ChartPlotterRoute.ok(0, 0, 10, 0, new int[]{0, 5, 10}, new int[]{0, 0, 0}, 3, 5, true);
		ChartPlotterRoute a = r.advance(6, 0);
		assertNotNull(a);
		assertEquals(6, a.sx);
		assertEquals(2, a.n);
		assertEquals(6, a.x[0]);
		assertEquals(10, a.x[1]);
		assertTrue(a.fast);
	}
	@Test
	public void bidirectionalFindsKnownRoute() {
		Map<Long, int[]> data = known(0, 0, 12, 4);
		for (int x = 1; x < 12; x++) put(data, x, 0, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
		ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, null, -1, 0, 0, 12, 0, 10, true, false, false);
		assertEquals(ChartPlotterRoute.OK, r.status);
		assertEquals(0, r.x[0]);
		assertEquals(12, r.x[r.n - 1]);
		assertEquals(0, r.y[r.n - 1]);
	}
	@Test
	public void routeUsesLegalAngles() {
		Map<Long, int[]> data = known(0, 0, 8, 8);
		ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, null, -1, 0, 0, 6, 4, 5, false, false, false);
		assertEquals(ChartPlotterRoute.OK, r.status);
		for (int i = 1; i < r.n; i++) assertTrue(legal(r.x[i] - r.x[i - 1], r.y[i] - r.y[i - 1]));
	}
	private static Map<Long, int[]> known(int minX, int minY, int maxX, int maxY) {
		Map<Long, int[]> data = new HashMap<>();
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) put(data, x, y, 0);
		}
		return data;
	}
	private static void put(Map<Long, int[]> data, int x, int y, int f) {
		int[] c = data.computeIfAbsent(key(x >> 3, y >> 3), k -> empty());
		c[(x & 7) + ((y & 7) << 3)] = f;
	}
	private static int[] empty() {
		int[] v = new int[64];
		Arrays.fill(v, ChartPlotterCollisionCache.UNKNOWN);
		return v;
	}
	private static long key(int x, int y) {return (long) x << 32 ^ y & 0xffffffffL;}
	private static int maxAbsY(ChartPlotterRoute r) {
		int n = 0;
		for (int i = 0; i < r.n; i++) n = Math.max(n, Math.abs(r.y[i]));
		return n;
	}
	private static boolean legal(int dx, int dy) {
		int[] x = {0, 1, 1, 2, 1, 2, 1, 1, 0, -1, -1, -2, -1, -2, -1, -1};
		int[] y = {1, 2, 1, 1, 0, -1, -1, -2, -1, -2, -1, -1, 0, 1, 1, 2};
		for (int i = 0; i < x.length; i++) {
			if ((long) dx * y[i] == (long) dy * x[i] && dx * x[i] + dy * y[i] > 0) return true;
		}
		return false;
	}
	private static WorldEntityConfig rect(int w, int h) {
		return rectY(w, h, 0);
	}
	private static WorldEntityConfig rectY(int w, int h, int oy) {
		return new WorldEntityConfig() {
			@Override
			public int getId() {return 0;}
			@Override
			public int getCategory() {return 0;}
			@Override
			public int getBoundsX() {return 0;}
			@Override
			public int getBoundsY() {return oy;}
			@Override
			public int getBoundsWidth() {return w;}
			@Override
			public int getBoundsHeight() {return h;}
		};
	}
}
