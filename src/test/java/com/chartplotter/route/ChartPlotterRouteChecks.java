package com.chartplotter.route;
import com.chartplotter.collision.ChartPlotterCollisionData;
import java.util.HashMap;
import java.util.Map;
public final class ChartPlotterRouteChecks {
	private ChartPlotterRouteChecks() {}
	public static void run() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = open();
		long k = ChartPlotterCollisionData.key(10 >> 3, 10 >> 3);
		long b = 1L << ((10 & 7) + ((10 & 7) << 3));
		chunks.compute(k, (kk, c) -> c == null ? new ChartPlotterCollisionData.Chunk(b, b) : new ChartPlotterCollisionData.Chunk(c.known, c.blocked | b));
		long t = ChartPlotterRoutes.target(new ChartPlotterRouteGrid(new ChartPlotterCollisionData(chunks)), 10, 10, 10, 0);
		eq(10, (int) (t >> 32));
		eq(9, (int) t);
		t = ChartPlotterRoutes.target(new ChartPlotterRouteGrid(new ChartPlotterCollisionData(chunks)), 12, 12, 10, 0);
		eq(12, (int) (t >> 32));
		eq(12, (int) t);
	}
	private static Map<Long, ChartPlotterCollisionData.Chunk> open() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = new HashMap<>();
		for (int x = 0; x < 4; x++) {
			for (int y = 0; y < 4; y++) chunks.put(ChartPlotterCollisionData.key(x, y), new ChartPlotterCollisionData.Chunk(-1L, 0));
		}
		return chunks;
	}
	private static void eq(long a, long b) {if (a != b) throw new AssertionError();}
}
