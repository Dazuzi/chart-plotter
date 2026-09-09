package com.chartplotter.route;
import com.chartplotter.collision.ChartPlotterCollisionData;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;
public class ChartPlotterWaypointTest {
	@Test
	public void everyLegConnectsAtTheWaypointMarker() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-4, -4, 24, 24));
		ChartPlotterRoute incoming = null;
		ChartPlotterTrip trip = ChartPlotterTrip.empty(1);
		int sx = 0;
		int sy = 0;
		for (int[] target : new int[][]{{40, 5}, {80, 40}, {120, 80}}) {
			ChartPlotterRoute route = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), sx, sy, target[0], target[1], 5, 1, 100, incoming, () -> false).find();
			assertEquals(ChartPlotterRoute.OK, route.status);
			assertEquals(target[0], route.tx);
			assertEquals(target[1], route.ty);
			assertTrue(ChartPlotterRoutes.near(route.x[route.n - 1], route.y[route.n - 1], route.tx, route.ty));
			assertTrue(data.clear(route.x[route.n - 1] + 0.5, route.y[route.n - 1] + 0.5, route.tx + 0.5, route.ty + 0.5));
			trip = trip.append(1, target[0], target[1], route);
			if (incoming != null) {
				assertTrue(route.continues(incoming));
				assertEquals(route.x[0] + route.offsetX, trip.markerX(trip.size() - 2), 0);
				assertEquals(route.y[0] + route.offsetY, trip.markerY(trip.size() - 2), 0);
			}
			assertTrue(route.valid(data, () -> false));
			incoming = route;
			sx = route.x[route.n - 1];
			sy = route.y[route.n - 1];
		}
	}
	@Test
	public void nearbyWaterDoesNotMakeLandAReachableWaypoint() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		ChartPlotterRoutingAudit.block(chunks, 20, 0);
		ChartPlotterRoute route = new ChartPlotterRouteFinder(new ChartPlotterCollisionData(chunks), null, 0, 0, 20, 0, 5, 1, 100, null, () -> false).find();
		assertEquals(ChartPlotterRoute.BLOCKED, route.status);
	}
	@Test
	public void aLandBarrierCannotBeSkippedAtArrival() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -32; y < 72; y++) ChartPlotterRoutingAudit.block(chunks, 16, y);
		ChartPlotterRoute route = new ChartPlotterRouteFinder(new ChartPlotterCollisionData(chunks), null, 0, 0, 20, 0, 5, 1, 100, null, () -> false).find();
		assertEquals(ChartPlotterRoute.NO_ROUTE, route.status);
	}
	@Test
	public void shorelineWaterKeepsItsMarkerAndBlockedClicksSnapToWater() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-8, -8, 8, 8);
		for (int y = 1; y < 72; y++) for (int x = -64; x < 72; x++) ChartPlotterRoutingAudit.block(chunks, x, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		for (double speed : new double[]{0.5, 1, 3}) {
			ChartPlotterRouteHull hull = new ChartPlotterRouteHull(ChartPlotterRoutingAudit.offsetHull(), new ChartPlotterRouteMotion(speed, 0.5, 0.5), 0, false);
			for (int clickY : new int[]{-8, -3, 0, 2}) {
				long target = ChartPlotterRoutes.target(data, 20, clickY, 20, -40, 10);
				int tx = (int) (target >> 32);
				int ty = (int) target;
				assertEquals(20, tx);
				assertEquals(Math.min(0, clickY), ty);
				assertEquals(ChartPlotterCollisionData.OPEN, data.flagAt(tx, ty));
				if (ty == 0) assertEquals(ChartPlotterCollisionData.BLOCKED, hull.flag(data, tx, ty, -1));
				ChartPlotterRoute route = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), 20, -40, tx, ty, 5, speed, 100, null, () -> false).find();
				assertEquals(ChartPlotterRoute.OK, route.status);
				assertTrue(route.y[route.n - 1] < ty);
				assertTrue(data.clear(route.x[route.n - 1] + 0.5, route.y[route.n - 1] + 0.5, tx + 0.5, ty + 0.5));
				assertTrue(route.valid(data, () -> false));
				assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, ChartPlotterRoutingAudit.offsetHull(), route));
				ChartPlotterRoute next = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), route.x[route.n - 1], route.y[route.n - 1], 50, -40, 5, speed, 100, route, () -> false).find();
				assertTrue(next.continues(route));
				assertTrue(next.valid(data, () -> false));
				assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, ChartPlotterRoutingAudit.offsetHull(), next));
				ChartPlotterTrip trip = ChartPlotterTrip.single(1, tx, ty, route).append(1, 50, -40, next);
				assertEquals(next.x[0] + next.offsetX, trip.markerX(0), 0);
				assertEquals(next.y[0] + next.offsetY, trip.markerY(0), 0);
				assertEquals(tx, trip.x(0));
				assertEquals(ty, trip.y(0));
			}
			assertEquals(ChartPlotterCollisionData.key(20, 30), ChartPlotterRoutes.target(data, 20, 30, 20, -40, 10));
			assertEquals(ChartPlotterCollisionData.BLOCKED, data.flagAt(20, 30));
		}
	}
	@Test
	public void snappingAnOpenTileCannotCrossALandBarrier() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -2; y <= 2; y++) for (int x = 18; x <= 22; x++) if (x != 20 || y != 0) ChartPlotterRoutingAudit.block(chunks, x, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		assertEquals(ChartPlotterCollisionData.OPEN, data.flagAt(20, 0));
		assertEquals(ChartPlotterCollisionData.key(20, 0), ChartPlotterRoutes.target(data, 20, 0, 0, 0, 10));
		assertEquals(ChartPlotterRoute.NO_ROUTE, new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), 0, 0, 20, 0, 5, 1, 100, null, () -> false).find().status);
	}
	@Test
	public void unknownWaypointsCannotBorrowNearbyKnownWater() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-4, -4, -1, 4));
		assertEquals(ChartPlotterCollisionData.UNKNOWN, data.flagAt(0, 0));
		assertEquals(ChartPlotterCollisionData.key(0, 0), ChartPlotterRoutes.target(data, 0, 0, -20, 0, 10));
		assertEquals(ChartPlotterRoute.UNCHARTED, new ChartPlotterRouteFinder(data, null, -20, 0, 0, 0, 5, 1, 100, null, () -> false).find().status);
	}
	@Test
	public void routeValidationChecksTheShortWaterConnection() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRoute route = new ChartPlotterRouteFinder(data, null, 0, 0, 40, 0, 5, 1, 100, null, () -> false).find();
		assertTrue(route.valid(data, () -> false));
		assertEquals(26, route.x[route.n - 1]);
		ChartPlotterRoute distant = ChartPlotterRoute.ok(0, 0, 40, 0, new int[]{0, 25}, new int[]{0, 0}, 2, 5, 100).plan(route.motion, route.hull, -1);
		assertFalse(distant.valid(data, () -> false));
		ChartPlotterRoutingAudit.block(chunks, 33, 0);
		assertFalse(route.valid(new ChartPlotterCollisionData(chunks), () -> false));
	}
	@Test
	public void nearbyArrivalsDetourUntilTheWaypointHasAClearWaterConnection() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -20; y <= 20; y++) ChartPlotterRoutingAudit.block(chunks, 16, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		for (int sx : new int[]{0, 12}) for (int bias : new int[]{5, 40}) {
			ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.config(64, 256), sx, 0, 20, 0, bias, 1, 100, null, () -> false);
			ChartPlotterRoute route = search.find();
			assertEquals(ChartPlotterRoute.OK, route.status);
			assertTrue(search.expanded > 0);
			assertTrue(route.valid(data, () -> false));
			assertTrue(data.clear(route.x[route.n - 1] + 0.5, route.y[route.n - 1] + 0.5, 20.5, 0.5));
			assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, search.config, route));
		}
	}
}
