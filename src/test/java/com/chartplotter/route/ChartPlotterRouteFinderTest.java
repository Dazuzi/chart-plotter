package com.chartplotter.route;

import com.chartplotter.collision.ChartPlotterCollisionData;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChartPlotterRouteFinderTest {
	@Test
	public void refinesArrivalInsideTolerance() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = new HashMap<>();
		for (int x = -16; x <= 32; x++) for (int y = -16; y <= 32; y++) chunks.put(ChartPlotterCollisionData.key(x, y), new ChartPlotterCollisionData.Chunk(-1L, 0));
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterSparseNodes.Snapshot sparse = new ChartPlotterSparseNodes.Snapshot(new int[]{50}, new int[]{20});
		ChartPlotterRoute route = ChartPlotterRouteFinder.find(data, null, -1, 0, 0, 60, 21, 5, false, 175, 10, sparse, 80, () -> false);
		assertEquals(ChartPlotterRoute.OK, route.status);
		assertTrue(route.n > 0);
		int distance = Math.max(Math.abs(route.x[route.n - 1] - route.tx), Math.abs(route.y[route.n - 1] - route.ty));
		assertTrue(Integer.toString(distance), distance <= 2);
	}
}
