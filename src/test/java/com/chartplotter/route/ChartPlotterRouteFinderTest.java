package com.chartplotter.route;

import com.chartplotter.collision.ChartPlotterCollisionData;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
		ChartPlotterCollisionData data = open();
		int barrierX = 20;
		for (int y = -20; y <= 20; y++) {
			long key = ChartPlotterCollisionData.key(barrierX >> 3, y >> 3);
			long mask = 1L << ((barrierX & 7) + ((y & 7) << 3));
			data.base.compute(key, (ignored, chunk) -> new ChartPlotterCollisionData.Chunk(-1L, (chunk == null ? 0 : chunk.blocked) | mask));
		}
		ChartPlotterSparseNodes.Snapshot sparse = new ChartPlotterSparseNodes.Snapshot(new int[]{0, 30, 30}, new int[]{30, 30, 0});
		ChartPlotterRoute route = ChartPlotterRouteFinder.find(data, null, -1, 0, 0, 21, 0, 5, false, 175, sparse, 80, () -> false);
		assertExact(route);
	}
	private static ChartPlotterCollisionData open() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = new HashMap<>();
		for (int x = -16; x <= 32; x++) for (int y = -16; y <= 32; y++) chunks.put(ChartPlotterCollisionData.key(x, y), new ChartPlotterCollisionData.Chunk(-1L, 0));
		return new ChartPlotterCollisionData(chunks);
	}
	private static void assertExact(ChartPlotterRoute route) {
		assertEquals(ChartPlotterRoute.OK, route.status);
		assertTrue(route.n > 0);
		assertEquals(route.tx, route.x[route.n - 1]);
		assertEquals(route.ty, route.y[route.n - 1]);
	}
}
