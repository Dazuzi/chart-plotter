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
	public void arrivalCannotStopAcrossLandFromTheWaypoint() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
		for (int x = -32; x < 40; x++) {
			ChartPlotterRoutingAudit.block(chunks, x, -10);
			for (int y = 1; y < 40; y++) ChartPlotterRoutingAudit.block(chunks, x, y);
		}
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		assertFalse(data.clear(0.5, -14.5, 0.5, 0.5));
		ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.config(64, 256), 0, -24, 0, 0, 5, 3, 140, null, () -> false);
		ChartPlotterRoute route = search.find();
		assertEquals(ChartPlotterRoute.NO_ROUTE, route.status);
	}
	@Test
	public void blockedUnchartedAndIsolatedDestinationTilesRejectNearbyWater() {
		for (int flag : new int[]{ChartPlotterCollisionData.OPEN, ChartPlotterCollisionData.BLOCKED, ChartPlotterCollisionData.UNKNOWN}) {
			Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-8, -8, flag == ChartPlotterCollisionData.UNKNOWN ? -1 : 8, 8);
			if (flag != ChartPlotterCollisionData.UNKNOWN) for (int x = 0; x < 72; x++) for (int y = -64; y < 72; y++) if (flag != ChartPlotterCollisionData.OPEN || x != 8 || y != 0) ChartPlotterRoutingAudit.block(chunks, x, y);
			for (boolean detour : new boolean[]{false, true}) {
				if (detour) for (int y = -12; y <= 12; y++) ChartPlotterRoutingAudit.block(chunks, -24, y);
				ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
				assertEquals(flag, data.flagAt(8, 0));
				for (boolean large : new boolean[]{false, true}) {
					ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, large ? ChartPlotterRoutingAudit.offsetHull() : ChartPlotterRoutingAudit.config(64, 256), -40, 0, 8, 0, 5, 1, 100, null, () -> false);
					ChartPlotterRoute route = search.find();
					assertEquals("flag=" + flag + " detour=" + detour + " large=" + large, flag == ChartPlotterCollisionData.UNKNOWN ? ChartPlotterRoute.UNCHARTED : flag == ChartPlotterCollisionData.OPEN ? ChartPlotterRoute.NO_ROUTE : ChartPlotterRoute.BLOCKED, route.status);
					assertEquals(8, route.tx);
					assertEquals(0, route.ty);
					assertFalse(route.valid(data, () -> false));
				}
			}
		}
	}
	@Test
	public void anOpenTileAtTheCompletionBoundaryDoesNotValidateALandWaypoint() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -14; y <= 14; y++) for (int x = 26; x <= 54; x++) ChartPlotterRoutingAudit.block(chunks, x, y);
		ChartPlotterCollisionData blocked = new ChartPlotterCollisionData(chunks);
		assertEquals(ChartPlotterCollisionData.OPEN, blocked.flagAt(25, 14));
		assertFalse(ChartPlotterRoutes.near(25, 14, 40, 0));
		assertEquals(ChartPlotterRoute.BLOCKED, new ChartPlotterRouteFinder(blocked, null, 0, 0, 40, 0, 5, 1, 100, null, () -> false).find().status);
		long key = ChartPlotterCollisionData.key(26 >> 3, 14 >> 3);
		chunks.computeIfPresent(key, (k, chunk) -> new ChartPlotterCollisionData.Chunk(chunk.known, chunk.blocked & ~(1L << ((26 & 7) + ((14 & 7) << 3)))));
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, null, 0, 0, 40, 0, 5, 1, 100, null, () -> false);
		ChartPlotterRoute route = search.find();
		assertEquals(ChartPlotterCollisionData.OPEN, data.flagAt(26, 14));
		assertEquals(ChartPlotterRoute.BLOCKED, route.status);
	}
	@Test
	public void coastalDepartureUsesNearbyWaterWhenTheExactHullCannotLeave() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
		for (int y = 0; y < 40; y++) for (int x = -32; x < 40; x++) if (y != 0 || x < 19 || x > 21) ChartPlotterRoutingAudit.block(chunks, x, y);
		for (boolean detour : new boolean[]{false, true}) {
			if (detour) for (int x = 18; x <= 22; x++) ChartPlotterRoutingAudit.block(chunks, x, -10);
			ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
			for (double speed : new double[]{0.5, 1, 3}) for (int bias : new int[]{5, 40}) {
				ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.config(64, 256), 20, 0, 20, -30, bias, speed, 100, null, () -> false);
				ChartPlotterRoute route = search.find();
				assertEquals(ChartPlotterCollisionData.OPEN, search.hull.flag(data, 20, 0, -1));
				for (int d = 0; d < 16; d++) assertNotEquals(ChartPlotterCollisionData.OPEN, search.hull.move[d].flag(data, 20, 0));
				assertEquals(ChartPlotterRoute.OK, route.status);
				assertEquals(20, route.sx);
				assertEquals(0, route.sy);
				assertTrue(route.y[0] < 0);
				assertTrue(Math.max(Math.abs(route.x[0] - 20), Math.abs(route.y[0])) <= 4);
				assertTrue(data.clear(route.x[0] + 0.5, route.y[0] + 0.5, 20.5, 0.5));
				assertNotNull(search.terrain);
				assertTrue(route.valid(data, () -> false));
				assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, search.config, route));
			}
		}
	}
	@Test
	public void coastalDeparturesCannotStartOnTheOppositeSideOfLand() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
		for (int x = -32; x < 40; x++) {
			ChartPlotterRoutingAudit.block(chunks, x, -10);
			for (int y = 1; y < 40; y++) ChartPlotterRoutingAudit.block(chunks, x, y);
		}
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRoute route = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), 0, -4, 0, -24, 5, 3, 140, null, () -> false).find();
		assertEquals(ChartPlotterRoute.NO_ROUTE, route.status);
	}
	@Test(timeout = 30_000)
	public void largeBoatCanArriveAtAndLeaveBundledCoastalWater() {
		long started = System.nanoTime();
		ChartPlotterCollisionCodec.Text text = ChartPlotterCollisionCodec.readText(getClass().getResourceAsStream("/com/chartplotter/collision.txt"), () -> false);
		assertNotNull("Bundled collision data must load", text);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(text.data);
		int[][] coast = {{2700, 3200}, {2722, 3147}, {2736, 3136}, {2752, 3124}, {2757, 3100}, {2754, 3076}, {2748, 3052}, {2730, 3034}, {2700, 3067}, {2650, 2990}, {2687, 3087}, {2684, 3093}, {2681, 3100}, {2683, 3108}, {2686, 3114}, {2678, 3149}};
		for (int[] ints : coast) {
			long goal = ChartPlotterRoutes.target(data, ints[0], ints[1], 2700, 3100, 10);
			int x = (int) (goal >> 32);
			int y = (int) goal;
			assertTrue(Math.abs(x - ints[0]) <= 10);
			assertTrue(Math.abs(y - ints[1]) <= 10);
			assertEquals(ChartPlotterCollisionData.OPEN, data.flagAt(x, y));
			for (boolean departure : new boolean[]{false, true}) {
				ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), departure ? x : 2700, departure ? y : 3100, departure ? 2700 : x, departure ? 3100 : y, 5, 3, 140, null, () -> System.nanoTime() - started >= 25_000_000_000L);
				ChartPlotterRoute route = search.find();
				assertEquals("coast=" + x + "," + y + " departure=" + departure, ChartPlotterRoute.OK, route.status);
				assertTrue(Math.max(Math.abs(route.x[0] - route.sx), Math.abs(route.y[0] - route.sy)) <= ChartPlotterRoutes.REACH_RADIUS);
				assertTrue(ChartPlotterRoutes.near(route.x[route.n - 1], route.y[route.n - 1], route.tx, route.ty));
				assertTrue(data.clear(route.x[route.n - 1] + 0.5, route.y[route.n - 1] + 0.5, route.tx + 0.5, route.ty + 0.5));
				assertTrue(data.clear(route.x[0] + 0.5, route.y[0] + 0.5, route.sx + 0.5, route.sy + 0.5));
				assertTrue(route.valid(data, () -> false));
				assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, search.config, route));
			}
		}
	}
	@Test
	public void disconnectedBundledCoastalTilesCannotBeReachedFromNearbyWater() {
		ChartPlotterCollisionCodec.Text text = ChartPlotterCollisionCodec.readText(getClass().getResourceAsStream("/com/chartplotter/collision.txt"), () -> false);
		assertNotNull(text);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(text.data);
		assertEquals(ChartPlotterCollisionData.OPEN, data.flagAt(2760, 3103));
		assertFalse(ChartPlotterRouteTerrain.connected(data, 2700, 3100, 2760, 3103, 0, () -> false));
		ChartPlotterRoute route = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), 2700, 3100, 2760, 3103, 5, 3, 140, null, () -> false).find();
		assertEquals(ChartPlotterRoute.NO_ROUTE, route.status);
		assertFalse(route.valid(data, () -> false));
	}
}
