package com.chartplotter.route;
import com.chartplotter.collision.ChartPlotterCollisionData;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;
public class ChartPlotterRouteApproachTest {
	@Test
	public void openWaterApproachesKeepAnAvailableStraightBearing() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-40, -40, 40, 40));
		for (double speed : new double[]{0.5, 1, 3}) for (int d = 0; d < 16; d++) {
			ChartPlotterRouteMotion motion = new ChartPlotterRouteMotion(speed, 0.5, 0.5);
			int tx = motion.x[d] * 20;
			int ty = motion.y[d] * 20;
			ChartPlotterRoute route = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), 0, 0, tx, ty, 5, speed, 100, () -> false).find();
			assertEquals(ChartPlotterRoute.OK, route.status);
			assertEquals(tx, route.x[route.n - 1]);
			assertEquals(ty, route.y[route.n - 1]);
			assertEquals(1, route.connection.length);
			for (int i = 0; i < route.n; i++) assertEquals("speed=" + speed + " direction=" + d + " point=" + route.x[i] + "," + route.y[i], 0L, (long) route.x[i] * ty - (long) route.y[i] * tx);
			assertTrue(route.valid(data, () -> false));
			assertTrue(ChartPlotterRoutingAudit.connectionClear(data, route));
			ChartPlotterRoute remaining = route;
			for (int step = 1; step < 20; step++) {
				int bx = motion.x[d] * step;
				int by = motion.y[d] * step;
				if (route.target.contains(bx, by)) break;
				remaining = remaining.advance(bx + 0.5, by + 0.5, 20, 32, 2);
				assertNotNull(remaining);
				assertSame(route.connection, remaining.connection);
				assertSame(remaining, remaining.refresh(data, () -> false));
				ChartPlotterRoute replanned = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), bx, by, tx, ty, 5, speed, 100, () -> false).find();
				assertEquals(ChartPlotterRoute.OK, replanned.status);
				assertEquals(route.x[route.n - 1], replanned.x[replanned.n - 1]);
				assertEquals(route.y[route.n - 1], replanned.y[replanned.n - 1]);
				assertArrayEquals(route.connection, replanned.connection);
			}
		}
	}
	@Test
	public void interruptedExactArrivalSearchRetainsTheVerifiedApproach() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-8, -8, 8, 8);
		ChartPlotterRoutingAudit.block(chunks, 20, -5);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRouteFinder[] search = new ChartPlotterRouteFinder[1];
		search[0] = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.config(64, 256), 20, -40, 20, 0, 5, 1, 100, () -> search[0].terrain != null);
		ChartPlotterRoute route = search[0].find();
		assertNotNull(search[0].terrain);
		assertEquals(ChartPlotterRoute.OK, route.status);
		assertEquals(20, route.tx);
		assertEquals(0, route.ty);
		assertTrue(route.connection.length > 1);
		assertTrue(route.valid(data, () -> false));
		assertTrue(ChartPlotterRoutingAudit.connectionClear(data, route));
		assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, search[0].config, route));
		ChartPlotterRoute complete = new ChartPlotterRouteFinder(data, search[0].config, 20, -40, 20, 0, 5, 1, 100, () -> false).find();
		assertEquals(ChartPlotterRoute.OK, complete.status);
		assertEquals(20, complete.x[complete.n - 1]);
		assertEquals(0, complete.y[complete.n - 1]);
		assertTrue(complete.valid(data, () -> false));
	}
	@Test
	public void aimingAtTheWaypointStillRelaxesTheHullBeforeTheShore() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-8, -8, 8, 8);
		for (int y = 0; y < 72; y++) for (int x = -64; x < 72; x++) if (y != 0 || x < 19 || x > 21) ChartPlotterRoutingAudit.block(chunks, x, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		for (double speed : new double[]{0.5, 1, 3}) {
			ChartPlotterRouteFinder finder = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.config(192, 384), 20, -40, 20, 0, 5, speed, 100, () -> false);
			ChartPlotterRoute route = finder.find();
			assertEquals(ChartPlotterCollisionData.BLOCKED, finder.hull.flag(data, 20, 0, -1));
			assertEquals(ChartPlotterRoute.OK, route.status);
			assertEquals(2, route.n);
			assertEquals(20, route.x[0]);
			assertEquals(20, route.x[1]);
			assertTrue(route.y[1] < 0);
			assertEquals(20.5, route.connectionX(route.connection.length - 1), 0);
			assertEquals(0.5, route.connectionY(route.connection.length - 1), 0);
			assertTrue(route.valid(data, () -> false));
			assertTrue(ChartPlotterRoutingAudit.connectionClear(data, route));
			assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, finder.config, route));
		}
	}
}
