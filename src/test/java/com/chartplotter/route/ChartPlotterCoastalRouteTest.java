package com.chartplotter.route;
import com.chartplotter.collision.ChartPlotterCollisionCodec;
import com.chartplotter.collision.ChartPlotterCollisionData;
import org.junit.Test;
import java.awt.geom.Line2D;
import java.util.Map;
import static org.junit.Assert.*;
public class ChartPlotterCoastalRouteTest {
	@Test
	public void arrivalGuidesRespectBlockedTileEdgesAtFractionalPositions() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-2, -2, 2, 2);
		ChartPlotterRoutingAudit.block(chunks, 0, 0);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		double[] edges = {0, 0, 1, 0, 1, 1, 0, 1, 0, 0};
		for (double x = -2; x <= 2; x += 0.25) for (double y = -2; y <= 2; y += 0.25) {
			for (double gx = -1.5; gx <= 2.5; gx++) for (double gy = -1.5; gy <= 2.5; gy++) {
				boolean expected = !(x >= 0 && x <= 1 && y >= 0 && y <= 1);
				for (int i = 0; i < 8; i += 2) expected &= !Line2D.linesIntersect(x, y, gx, gy, edges[i], edges[i + 1], edges[i + 2], edges[i + 3]);
				assertEquals("from=" + x + "," + y + " to=" + gx + "," + gy, expected, data.clear(x, y, gx, gy));
				assertEquals(expected, data.clear(gx, gy, x, y));
			}
		}
	}
	@Test
	public void coastalArrivalsCannotStopOnTheOppositeSideOfLand() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
		for (int x = -32; x < 40; x++) {
			ChartPlotterRoutingAudit.block(chunks, x, -10);
			for (int y = 1; y < 40; y++) ChartPlotterRoutingAudit.block(chunks, x, y);
		}
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		assertFalse(data.clear(0.5, -14.5, 0.5, 0.5));
		ChartPlotterRoute route = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), 1024, 0, -24, 0, 0, 5, false, 3, 0.5, 0.5, 140, () -> false).find();
		assertEquals(ChartPlotterRoute.NO_ROUTE, route.status);
	}
	@Test
	public void largeBoatCanApproachTheBundledCoastFromEveryHeading() {
		ChartPlotterCollisionCodec.Text text = ChartPlotterCollisionCodec.readText(getClass().getResourceAsStream("/com/chartplotter/collision.txt"));
		assertNotNull("Bundled collision data must load", text);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(text.data);
		int[][] coast = {{2700, 3200}, {2722, 3147}, {2736, 3136}, {2752, 3124}, {2757, 3100}, {2754, 3076}, {2748, 3052}, {2730, 3034}, {2700, 3067}, {2650, 2990}, {2687, 3087}, {2684, 3093}, {2681, 3100}, {2683, 3108}, {2686, 3114}, {2678, 3149}};
		for (int d = 0; d < coast.length; d++) {
			long goal = ChartPlotterRoutes.target(data, coast[d][0], coast[d][1], 2700, 3100);
			int x = (int) (goal >> 32);
			int y = (int) goal;
			assertTrue(Math.abs(x - coast[d][0]) <= 2);
			assertTrue(Math.abs(y - coast[d][1]) <= 2);
			long begin = System.nanoTime();
			ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), ChartPlotterRouteMoves.OR[d], 2700, 3100, x, y, 5, false, 3, 0.5, 0.5, 140, () -> System.nanoTime() - begin >= 3_000_000_000L);
			ChartPlotterRoute route = search.find();
			assertEquals("heading=" + ChartPlotterRouteMoves.OR[d], ChartPlotterRoute.OK, route.status);
			assertTrue(Math.abs(route.x[route.n - 1] - x) <= 14);
			assertTrue(Math.abs(route.y[route.n - 1] - y) <= 14);
			assertTrue(route.valid(data, () -> false));
			assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, search.config, route));
		}
	}
}
