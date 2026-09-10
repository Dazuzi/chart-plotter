package com.chartplotter.route;
import com.chartplotter.collision.ChartPlotterCollisionData;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;
public class ChartPlotterWaypointTest {
	@Test
	public void aBentInletConnectsEveryStopWithoutSendingTheHullIntoIt() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-8, -8, 8, 8);
		for (int y = -64; y < 72; y++) for (int x = 1; x < 72; x++) if (!(y == 0 && x <= 4 || x == 4 && y >= 0 && y <= 4)) ChartPlotterRoutingAudit.block(chunks, x, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		for (int sx : new int[]{-2, -12}) for (double speed : new double[]{0.5, 1, 3}) for (int bias : new int[]{0, 5, 40}) {
			ChartPlotterRouteFinder finder = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), sx, -32, 4, 4, bias, speed, 100, () -> false);
			ChartPlotterRoute route = finder.find();
			assertEquals("speed=" + speed + " bias=" + bias, ChartPlotterRoute.OK, route.status);
			assertTrue(route.valid(data, () -> false));
			assertEquals(ChartPlotterCollisionData.BLOCKED, route.hull.flag(data, 4, 4, -1));
			assertTrue(route.connection.length > 2);
			assertTrue(ChartPlotterRoutingAudit.connectionClear(data, route));
			assertFalse(data.clear(route.connectionX(0), route.connectionY(0), 4.5, 4.5));
			assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, finder.config, route));
			ChartPlotterTrip trip = ChartPlotterTrip.single(1, 4, 4, route);
			assertTrue(trip.reached(route.x[route.n - 1], route.y[route.n - 1], data));
			assertTrue(trip.reached(4, 4, data));
			ChartPlotterRoute next = new ChartPlotterRouteFinder(data, finder.config, 4, 4, -20, 30, bias, speed, 100, () -> false).find();
			assertEquals("speed=" + speed + " bias=" + bias, ChartPlotterRoute.OK, next.status);
			assertTrue(next.start(4, 4));
			assertTrue(next.valid(data, () -> false));
			assertTrue(next.departure.length > 2);
			assertTrue(ChartPlotterRoutingAudit.connectionClear(data, next));
			assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, finder.config, next));
			ChartPlotterTrip appended = trip.append(2, -20, 30, next);
			assertEquals(trip.markerX(0), appended.markerX(0), 0);
			assertEquals(trip.markerY(0), appended.markerY(0), 0);
			assertSame(route, appended.route(0));
		}
		ChartPlotterRoutingAudit.block(chunks, 4, 2);
		ChartPlotterCollisionData sealed = new ChartPlotterCollisionData(chunks);
		assertEquals(ChartPlotterRoute.NO_ROUTE, new ChartPlotterRouteFinder(sealed, ChartPlotterRoutingAudit.offsetHull(), -2, -32, 4, 4, 5, 1, 100, () -> false).find().status);
	}
	@Test
	public void placementSnapsOnlyToTheBoatsConnectedWater() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -32; y < 72; y++) for (int x = 1; x < 72; x++) if (y != 0 || x != 3) ChartPlotterRoutingAudit.block(chunks, x, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRouteComponent component = ChartPlotterRouteComponent.create(data, -20, 0, () -> false);
		assertNotNull(component);
		assertFalse(component.contains(3, 0));
		for (int x : new int[]{0, 2, 3}) assertEquals(ChartPlotterCollisionData.key(0, 0), ChartPlotterRoutes.target(data, x, 0, -20, 0, 10, component));
		assertEquals(ChartPlotterCollisionData.key(20, 0), ChartPlotterRoutes.target(data, 20, 0, -20, 0, 10, component));
		assertEquals(ChartPlotterCollisionData.key(80, 0), ChartPlotterRoutes.target(data, 80, 0, -20, 0, 10, component));
		ChartPlotterRoutingAudit.block(chunks, -1, 0);
		ChartPlotterCollisionData changed = new ChartPlotterCollisionData(chunks);
		ChartPlotterRouteComponent rebuilt = ChartPlotterRouteComponent.create(changed, -20, 0, () -> false);
		assertNotNull(rebuilt);
		assertTrue(component.contains(-1, 0));
		assertFalse(rebuilt.contains(-1, 0));
		assertNull(ChartPlotterRouteComponent.create(changed, -20, 0, () -> true));
	}
	@Test
	public void legsConnectAtSelectedWaypointTiles() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-4, -4, 24, 24));
		ChartPlotterRoute incoming = null;
		ChartPlotterTrip trip = ChartPlotterTrip.empty(1);
		int sx = 0;
		int sy = 0;
		for (int[] target : new int[][]{{40, 5}, {80, 40}, {120, 80}}) {
			ChartPlotterRoute route = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), sx, sy, target[0], target[1], 5, 1, 100, () -> false).find();
			assertEquals(ChartPlotterRoute.OK, route.status);
			assertEquals(target[0], route.tx);
			assertEquals(target[1], route.ty);
			assertTrue(ChartPlotterRoutingAudit.near(route.x[route.n - 1], route.y[route.n - 1], route.tx, route.ty));
			assertTrue(ChartPlotterRoutingAudit.connectionClear(data, route));
			trip = trip.append(1, target[0], target[1], route);
			if (incoming != null) {
				assertTrue(route.start(incoming.tx, incoming.ty));
				assertEquals(incoming.tx + 0.5, trip.markerX(trip.size() - 2), 0);
				assertEquals(incoming.ty + 0.5, trip.markerY(trip.size() - 2), 0);
				assertEquals(incoming.connectionX(incoming.connection.length - 1), route.departureX(0), 0);
				assertEquals(incoming.connectionY(incoming.connection.length - 1), route.departureY(0), 0);
			}
			assertTrue(route.valid(data, () -> false));
			incoming = route;
			sx = route.tx;
			sy = route.ty;
		}
	}
	@Test
	public void nearbyWaterDoesNotMakeLandAReachableWaypoint() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		ChartPlotterRoutingAudit.block(chunks, 20, 0);
		ChartPlotterRoute route = new ChartPlotterRouteFinder(new ChartPlotterCollisionData(chunks), null, 0, 0, 20, 0, 5, 1, 100, () -> false).find();
		assertEquals(ChartPlotterRoute.BLOCKED, route.status);
	}
	@Test
	public void aLandBarrierCannotBeSkippedAtArrival() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -32; y < 72; y++) ChartPlotterRoutingAudit.block(chunks, 16, y);
		ChartPlotterRoute route = new ChartPlotterRouteFinder(new ChartPlotterCollisionData(chunks), null, 0, 0, 20, 0, 5, 1, 100, () -> false).find();
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
				long target = ChartPlotterRoutes.target(data, 20, clickY, 20, -40, 10, null);
				int tx = (int) (target >> 32);
				int ty = (int) target;
				assertEquals(20, tx);
				assertEquals(Math.min(0, clickY), ty);
				assertEquals(ChartPlotterCollisionData.OPEN, data.flagAt(tx, ty));
				if (ty == 0) assertEquals(ChartPlotterCollisionData.BLOCKED, hull.flag(data, tx, ty, -1));
				ChartPlotterRoute route = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), 20, -40, tx, ty, 5, speed, 100, () -> false).find();
				assertEquals(ChartPlotterRoute.OK, route.status);
				if (ty == -8) {
					assertEquals(tx, route.x[route.n - 1]);
					assertEquals(ty, route.y[route.n - 1]);
				} else assertTrue(route.y[route.n - 1] < ty);
				assertTrue(data.clear(route.x[route.n - 1] + 0.5, route.y[route.n - 1] + 0.5, tx + 0.5, ty + 0.5));
				assertTrue(route.valid(data, () -> false));
				assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, ChartPlotterRoutingAudit.offsetHull(), route));
				ChartPlotterRoute next = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), tx, ty, 50, -40, 5, speed, 100, () -> false).find();
				assertTrue(next.start(tx, ty));
				assertTrue(next.valid(data, () -> false));
				assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, ChartPlotterRoutingAudit.offsetHull(), next));
				ChartPlotterTrip trip = ChartPlotterTrip.single(1, tx, ty, route).append(1, 50, -40, next);
				assertEquals(tx + 0.5, trip.markerX(0), 0);
				assertEquals(ty + 0.5, trip.markerY(0), 0);
				assertEquals(tx, trip.x(0));
				assertEquals(ty, trip.y(0));
			}
			assertEquals(ChartPlotterCollisionData.key(20, 30), ChartPlotterRoutes.target(data, 20, 30, 20, -40, 10, null));
			assertEquals(ChartPlotterCollisionData.BLOCKED, data.flagAt(20, 30));
		}
	}
	@Test
	public void snappingAnOpenTileCannotCrossALandBarrier() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -2; y <= 2; y++) for (int x = 18; x <= 22; x++) if (x != 20 || y != 0) ChartPlotterRoutingAudit.block(chunks, x, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		assertEquals(ChartPlotterCollisionData.OPEN, data.flagAt(20, 0));
		assertEquals(ChartPlotterCollisionData.key(20, 0), ChartPlotterRoutes.target(data, 20, 0, 0, 0, 10, null));
		assertEquals(ChartPlotterRoute.NO_ROUTE, new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), 0, 0, 20, 0, 5, 1, 100, () -> false).find().status);
	}
	@Test
	public void unknownWaypointsCannotBorrowNearbyKnownWater() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-4, -4, -1, 4));
		assertEquals(ChartPlotterCollisionData.UNKNOWN, data.flagAt(0, 0));
		assertEquals(ChartPlotterCollisionData.key(0, 0), ChartPlotterRoutes.target(data, 0, 0, -20, 0, 10, null));
		assertEquals(ChartPlotterRoute.UNCHARTED, new ChartPlotterRouteFinder(data, null, -20, 0, 0, 0, 5, 1, 100, () -> false).find().status);
	}
	@Test
	public void routeValidationChecksTheShortWaterConnection() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRoute route = new ChartPlotterRouteFinder(data, null, 0, 0, 40, 0, 5, 1, 100, () -> false).find();
		route = ChartPlotterRoute.ok(0, 0, 40, 0, new int[]{0, 26}, new int[]{0, 0}, 2, 5, 100).plan(route.motion, route.hull, -1).connect(data, route.source, route.target);
		assertTrue(route.valid(data, () -> false));
		assertEquals(26, route.x[route.n - 1]);
		ChartPlotterRoutingAudit.block(chunks, 33, 0);
		assertFalse(route.valid(new ChartPlotterCollisionData(chunks), () -> false));
	}
	@Test
	public void nearbyArrivalsDetourUntilTheWaypointHasAClearWaterConnection() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -20; y <= 20; y++) ChartPlotterRoutingAudit.block(chunks, 16, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		for (int sx : new int[]{0, 12}) for (int bias : new int[]{5, 40}) {
			ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.config(64, 256), sx, 0, 20, 0, bias, 1, 100, () -> false);
			ChartPlotterRoute route = search.find();
			assertEquals(ChartPlotterRoute.OK, route.status);
			assertTrue(search.expanded > 0);
			assertEquals(20, route.x[route.n - 1]);
			assertEquals(0, route.y[route.n - 1]);
			assertTrue(route.valid(data, () -> false));
			assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, search.config, route));
		}
	}
}
