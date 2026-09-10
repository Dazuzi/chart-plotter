package com.chartplotter.route;
import com.chartplotter.ChartPlotterRouteEffort;
import com.chartplotter.ChartPlotterTurnPreference;
import com.chartplotter.collision.ChartPlotterCollisionData;
import org.junit.Test;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
public class ChartPlotterRouteShapeTest {
	@Test
	public void smoothPreservesHullClearanceAtDifferentSpeeds() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -10; y <= 10; y++) ChartPlotterRoutingAudit.block(chunks, 20, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		for (double speed : new double[]{0.5, 1, 3}) {
			ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), 0, 0, 40, 0, 40, speed, 140, () -> false);
			ChartPlotterRoute route = search.find();
			assertEquals(ChartPlotterRoute.OK, route.status);
			assertEquals(0.5, route.offsetX, 0);
			assertEquals(0.5, route.offsetY, 0);
			assertTrue(route.valid(data, () -> false));
			assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, ChartPlotterRoutingAudit.offsetHull(), route));
		}
	}
	@Test
	public void smoothingRemovesCorrectionsWithoutCuttingThroughAnObstacle() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -10; y <= 10; y++) ChartPlotterRoutingAudit.block(chunks, 20, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		AtomicBoolean cancel = new AtomicBoolean();
		ChartPlotterRouteFinder search = search(data, 140, 40, cancel::get);
		ChartPlotterRoute route = ChartPlotterRoute.ok(0, 0, 40, 0, new int[]{0, 5, 5, 10, 10, 15, 15, 25, 25, 40}, new int[]{0, 0, 5, 5, 10, 10, 15, 15, 0, 0}, 10, 40, 140);
		search.find();
		route = route.plan(search.motion, search.hull, 1536).connect(data, search.source, search.target);
		assertTrue(route.valid(data, () -> false));
		ChartPlotterRoute smooth = ChartPlotterRouteSmoother.smooth(search, route, ChartPlotterRoutingAudit.length(route) * 1.1).plan(search.motion, search.hull, 1536).connect(data, search.source, search.target);
		assertTrue(smooth.n < route.n);
		assertTrue(smooth.valid(data, () -> false));
		assertTrue(ChartPlotterRoutingAudit.length(smooth) <= ChartPlotterRoutingAudit.length(route) * 1.1);
		assertEquals(smooth.n, smooth.x.length);
		cancel.set(true);
		assertSame(route, ChartPlotterRouteSmoother.smooth(search, route, ChartPlotterRoutingAudit.length(route) * 1.1));
	}
	@Test
	public void smoothNeverAddsTurnsOrExceedsTheBalancedDistanceAllowance() {
		Random random = new Random(1083);
		for (int trial = 0; trial < 12; trial++) {
			Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-2, -2, 8, 4);
			for (int x = 8; x <= 32; x += 8) for (int y = -8; y < 12; y++) if (random.nextInt(3) != 0) ChartPlotterRoutingAudit.block(chunks, x, y);
			ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
			for (ChartPlotterRouteEffort effort : ChartPlotterRouteEffort.values()) {
				String context = "trial=" + trial + " effort=" + effort.name();
				ChartPlotterRoute balanced = search(data, effort.weight, ChartPlotterTurnPreference.BALANCED.bias, () -> false).find();
				ChartPlotterRoute smooth = search(data, effort.weight, ChartPlotterTurnPreference.SMOOTH.bias, () -> false).find();
				assertEquals(context, ChartPlotterRoute.OK, balanced.status);
				assertEquals(context, ChartPlotterRoute.OK, smooth.status);
				assertTrue(context, smooth.valid(data, () -> false));
				assertTrue(context, ChartPlotterRoutingAudit.turns(smooth) <= ChartPlotterRoutingAudit.turns(balanced));
				assertTrue(context, ChartPlotterRoutingAudit.length(smooth) <= ChartPlotterRoutingAudit.length(balanced) * 1.1 + 1e-9);
			}
		}
	}
	@Test
	public void refinementDeadlineKeepsTheCompletedBalancedRoute() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -10; y <= 10; y++) ChartPlotterRoutingAudit.block(chunks, 20, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		AtomicInteger checks = new AtomicInteger();
		ChartPlotterRoute balanced = search(data, 100, 5, () -> {checks.incrementAndGet(); return false;}).find();
		int deadline = checks.get() + 1;
		checks.set(0);
		ChartPlotterRoute smooth = search(data, 100, 40, () -> checks.incrementAndGet() > deadline).find();
		assertTrue(checks.get() > deadline);
		assertEquals(ChartPlotterRoute.OK, smooth.status);
		assertTrue(smooth.valid(data, () -> false));
		assertArrayEquals(balanced.x, smooth.x);
		assertArrayEquals(balanced.y, smooth.y);
	}
	private static ChartPlotterRouteFinder search(ChartPlotterCollisionData data, int weight, int bias, java.util.function.BooleanSupplier cancel) {return new ChartPlotterRouteFinder(data, null, 0, 0, 40, 0, bias, 1, weight, cancel);}
}
