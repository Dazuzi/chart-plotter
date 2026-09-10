package com.chartplotter.route;
import com.chartplotter.collision.ChartPlotterCollisionData;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;
public class ChartPlotterRouteConnectionTest {
	@Test
	public void continuationStartsAtTheSelectedTileDespiteEarlyHullArrival() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-8, -8, 24, 8));
		ChartPlotterRoute first = search(data, -40, 0, 14);
		first = ChartPlotterRoute.ok(-40, 0, 14, 0, new int[]{-40, 0}, new int[]{0, 0}, 2, 5, 100).plan(first.motion, first.hull, -1).connect(data, first.source, first.target);
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 14, 0, first);
		assertEquals(0, first.x[first.n - 1]);
		int sx = ChartPlotterRoutes.legStartX(trip, 1, -30);
		int sy = ChartPlotterRoutes.legStartY(trip, 1, 3);
		assertEquals(14, sx);
		assertEquals(0, sy);
		ChartPlotterRoute second = search(data, sx, sy, 54);
		assertEquals(ChartPlotterRoute.OK, second.status);
		assertEquals(14, second.x[0]);
		assertEquals(first.connectionX(first.connection.length - 1), second.departureX(0), 0);
		assertEquals(first.connectionY(first.connection.length - 1), second.departureY(0), 0);
		assertTrue(ChartPlotterRoutingAudit.connectionClear(data, first));
		assertTrue(ChartPlotterRoutingAudit.connectionClear(data, second));
		assertEquals(94, trip.append(2, 54, 0, second).distance(-39.5, 0.5, true), 1e-9);
		ChartPlotterRoutes.Turn turn = ChartPlotterRoutes.turn(first, -39.5, 0.5, 1, 0, 1, 0);
		assertTrue(turn.end);
		assertEquals(14, turn.x);
		assertEquals(54, turn.ticks);
	}
	@Test
	public void boatProgressAndActiveReplanningKeepPreparedLegs() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-8, -8, 24, 8));
		ChartPlotterRoute first = search(data, -40, 0, 14);
		ChartPlotterRoute second = search(data, 14, 0, 54);
		ChartPlotterRoute third = search(data, 54, 0, 94);
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 14, 0, first).append(1, 54, 0, second).append(1, 94, 0, third);
		for (ChartPlotterRoute active : new ChartPlotterRoute[]{first.advance(-19.5, 0.5, 20, 32, 2), search(data, -20, 8, 14), search(data, -20, -8, 14), ChartPlotterRoute.pending(-20, 0, 14, 0, 5, 100), ChartPlotterRoute.failed(-20, 0, 14, 0, 5, 100), null}) {
			ChartPlotterTrip updated = trip.route(0, active);
			assertSame(second, updated.route(1));
			assertSame(third, updated.route(2));
			assertEquals(14, ChartPlotterRoutes.legStartX(updated, 1, -20));
			assertSame(second, updated.advance(2).active());
			assertEquals(14.5, updated.markerX(0), 0);
		}
		ChartPlotterTrip advanced = trip.advance(2);
		ChartPlotterRoute active = advanced.active();
		assertNotNull(active);
		ChartPlotterRoute pruned = active.advance(24.5, 0.5, 20, 32, 2);
		assertNotNull(pruned);
		assertSame(third, advanced.route(0, pruned).route(1));
		assertEquals(0, pruned.departure.length);
		assertSame(second.connection, pruned.connection);
	}
	@Test
	public void newlyBlockedWaypointsInvalidateBothAdjacentLegsWithoutMoving() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-8, -8, 24, 8);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRoute first = search(data, -40, 0, 14);
		ChartPlotterRoute second = search(data, 14, 0, 54);
		ChartPlotterRoute third = search(data, 54, 0, 94);
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 14, 0, first).append(1, 54, 0, second).append(1, 94, 0, third);
		ChartPlotterRoutingAudit.block(chunks, 14, 0);
		ChartPlotterCollisionData changed = new ChartPlotterCollisionData(chunks);
		assertNotEquals(ChartPlotterCollisionData.key(14, 0), ChartPlotterRoutes.target(changed, 14, 0, 14, 0, 2, null));
		assertNull(first.refresh(changed, () -> false));
		assertNull(second.refresh(changed, () -> false));
		assertSame(third, third.refresh(changed, () -> false));
		ChartPlotterTrip updated = trip.route(0, search(changed, -40, 0, 14)).route(1, search(changed, 14, 0, 54));
		assertEquals(ChartPlotterRoute.BLOCKED, updated.route(0).status);
		assertEquals(ChartPlotterRoute.BLOCKED, updated.route(1).status);
		assertEquals(14, updated.route(0).tx);
		assertTrue(updated.route(1).start(14, 0));
		assertEquals(14.5, updated.markerX(0), 0);
		assertSame(third, updated.route(2));
	}
	@Test
	public void unrelatedLocalCollisionUpdatesRefreshFieldsWithoutReplacingPaths() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-8, -8, 24, 8);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRoute first = search(data, -40, 0, 14);
		ChartPlotterRoute second = search(data, 14, 0, 54);
		ChartPlotterRoutingAudit.block(chunks, 14, 7);
		ChartPlotterCollisionData changed = new ChartPlotterCollisionData(chunks);
		assertFalse(first.target.matches(changed));
		assertFalse(second.source.matches(changed));
		for (ChartPlotterRoute route : new ChartPlotterRoute[]{first, second}) {
			ChartPlotterRoute refreshed = route.refresh(changed, () -> false);
			assertNotNull(refreshed);
			assertNotSame(route, refreshed);
			assertSame(route.x, refreshed.x);
			assertSame(route.y, refreshed.y);
			assertSame(route.departure, refreshed.departure);
			assertSame(route.connection, refreshed.connection);
			assertTrue(refreshed.source.matches(changed));
			assertTrue(refreshed.target.matches(changed));
			assertTrue(ChartPlotterRoutingAudit.connectionClear(changed, refreshed));
			assertSame(refreshed, refreshed.refresh(changed, () -> false));
		}
		ChartPlotterRoutingAudit.block(chunks, 7, 0);
		assertNull(first.refresh(new ChartPlotterCollisionData(chunks), () -> false));
	}
	private static ChartPlotterRoute search(ChartPlotterCollisionData data, int sx, int sy, int tx) {return new ChartPlotterRouteFinder(data, null, sx, sy, tx, 0, 5, 1, 100, () -> false).find();}
}
