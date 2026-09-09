package com.chartplotter.route;
import com.chartplotter.ChartPlotterTurnPreference;
import com.chartplotter.collision.ChartPlotterCollisionData;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;
public class ChartPlotterRouteConnectionTest {
	@Test
	public void detourStartsAtTheIncomingEndpoint() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-6, -6, 10, 10);
		ChartPlotterRoutingAudit.block(chunks, -3, 0);
		for (int y = -20; y <= 20; y++) ChartPlotterRoutingAudit.block(chunks, 12, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		for (double speed : new double[]{0.5, 1, 3}) for (ChartPlotterTurnPreference shape : ChartPlotterTurnPreference.values()) {
			ChartPlotterRoute incoming = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), 0, -30, 0, 14, shape.bias, speed, 100, null, () -> false).find();
			assertEquals(ChartPlotterRoute.OK, incoming.status);
			assertEquals(0, incoming.x[incoming.n - 1]);
			assertEquals(0, incoming.y[incoming.n - 1]);
			ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), 0, 0, 48, 0, shape.bias, speed, 100, incoming, () -> false);
			ChartPlotterRoute route = search.find();
			assertEquals(ChartPlotterRoute.OK, route.status);
			assertEquals(ChartPlotterCollisionData.OPEN, search.hull.hull[0].flag(data, 0, 0));
			assertTrue(search.expanded > 0);
			assertEquals(0, route.x[0]);
			assertEquals(0, route.y[0]);
			assertTrue(route.continues(incoming));
			assertTrue(route.valid(data, () -> false));
			assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, search.config, route));
		}
	}
	@Test
	public void waypointTurnAvoidsAnObstacleBetweenTwoClearHeadings() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-6, -6, 10, 10);
		ChartPlotterRoutingAudit.block(chunks, 4, 4);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		for (ChartPlotterTurnPreference shape : ChartPlotterTurnPreference.values()) {
			ChartPlotterRoute incoming = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), 0, -30, 0, 14, shape.bias, 1, 100, null, () -> false).find();
			ChartPlotterRouteHull hull = incoming.hull;
			assertEquals(ChartPlotterCollisionData.OPEN, hull.hull[0].flag(data, 0, 0));
			assertEquals(ChartPlotterCollisionData.OPEN, hull.move[4].flag(data, 0, 0));
			assertEquals(ChartPlotterCollisionData.BLOCKED, hull.turn[4].flag(data, 0, 0));
			ChartPlotterRoute route = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), 0, 0, 48, 0, shape.bias, 1, 100, incoming, () -> false).find();
			assertEquals(ChartPlotterRoute.OK, route.status);
			assertTrue(route.continues(incoming));
			assertTrue(route.valid(data, () -> false));
			assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, ChartPlotterRoutingAudit.offsetHull(), route));
		}
	}
	@Test
	public void prunedStopsRetainTheArrivalHeadingForTheFollowingLeg() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-8, -8, 16, 16));
		ChartPlotterRoute first = new ChartPlotterRouteFinder(data, null, -40, 0, 14, 0, 5, 1, 100, null, () -> false).find();
		ChartPlotterRoute second = first.advance(0.5, 0.5, 20, 32, 0);
		assertNotNull(second);
		assertTrue(second.continues(first));
		assertEquals(1, second.n);
		assertEquals(1536, second.arrivalHeading());
		ChartPlotterRoute third = new ChartPlotterRouteFinder(data, null, 0, 0, 64, 64, 5, 1, 100, second, () -> false).find();
		assertTrue(third.continues(second));
		assertEquals(1536, third.heading);
		assertTrue(third.valid(data, () -> false));
	}
	@Test
	public void directContinuationPrefersTheIncomingBearingBeforeTurning() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-8, -8, 32, 32));
		ChartPlotterRoute first = new ChartPlotterRouteFinder(data, null, -40, 0, 14, 0, 5, 1, 100, null, () -> false).find();
		int tx = first.motion.x[3] * 5 + 2;
		int ty = first.motion.y[3] * 5;
		ChartPlotterRoute second = new ChartPlotterRouteFinder(data, null, 0, 0, tx + 14, ty + 14, 5, 1, 100, first, () -> false).find();
		assertTrue(second.continues(first));
		assertEquals(3, second.n);
		assertEquals(2, second.x[1]);
		assertEquals(0, second.y[1]);
		assertTrue(second.valid(data, () -> false));
	}
	@Test
	public void changedArrivalInvalidatesDependentLegsWhilePruningKeepsThem() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-8, -8, 16, 16));
		ChartPlotterRoute first = new ChartPlotterRouteFinder(data, null, -40, 0, 14, 0, 5, 1, 100, null, () -> false).find();
		ChartPlotterRoute second = new ChartPlotterRouteFinder(data, null, 0, 0, 54, 0, 5, 1, 100, first, () -> false).find();
		ChartPlotterRoute third = new ChartPlotterRouteFinder(data, null, 40, 0, 94, 0, 5, 1, 100, second, () -> false).find();
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 14, 0, first).append(1, 54, 0, second).append(1, 94, 0, third);
		ChartPlotterTrip pruned = trip.route(0, first.advance(-9.5, 0.5, 20, 32, 2));
		assertSame(second, pruned.route(1));
		assertSame(third, pruned.route(2));
		assertSame(second, pruned.advance(2).active());
		ChartPlotterRoute moved = ChartPlotterRoute.ok(-40, 4, 14, 0, new int[]{-40, 0}, new int[]{4, 4}, 2, 5, 100).plan(first.motion, first.hull, -1);
		ChartPlotterRoute turned = ChartPlotterRoute.ok(0, -40, 14, 0, new int[]{0, 0}, new int[]{-40, 0}, 2, 5, 100).plan(first.motion, first.hull, -1);
		for (ChartPlotterRoute changed : new ChartPlotterRoute[]{moved, turned, ChartPlotterRoute.failed(-40, 0, 0, 0, 5, 100)}) {
			ChartPlotterTrip updated = trip.route(0, changed);
			assertNull(updated.route(1));
			assertNull(updated.route(2));
			assertEquals(54, updated.x(1));
			assertEquals(94, updated.x(2));
		}
		assertSame(second, trip.route(1));
		assertSame(third, trip.route(2));
	}
}
