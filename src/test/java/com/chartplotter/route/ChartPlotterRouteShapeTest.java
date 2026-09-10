package com.chartplotter.route;
import com.chartplotter.ChartPlotterRouteEffort;
import com.chartplotter.ChartPlotterTurnPreference;
import com.chartplotter.collision.ChartPlotterCollisionData;
import org.junit.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
public class ChartPlotterRouteShapeTest {
	@Test
	public void routeShapesPreserveHullClearanceAtDifferentSpeeds() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -10; y <= 10; y++) ChartPlotterRoutingAudit.block(chunks, 20, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		for (double speed : new double[]{0.5, 1, 3}) for (ChartPlotterTurnPreference shape : ChartPlotterTurnPreference.values()) {
			ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), 0, 0, 40, 0, shape.bias, speed, 140, () -> false);
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
		ChartPlotterRoute smooth = ChartPlotterRouteSmoother.smooth(search, route).plan(search.motion, search.hull, 1536).connect(data, search.source, search.target);
		assertTrue(smooth.n < route.n);
		assertTrue(smooth.valid(data, () -> false));
		assertTrue(search.routeCost(smooth) < search.routeCost(route));
		assertEquals(smooth.n, smooth.x.length);
		cancel.set(true);
		assertSame(route, ChartPlotterRouteSmoother.smooth(search, route));
	}
	@Test
	public void longerLegsRemoveAShortCorrectionForBothShapes() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -8; y <= 8; y++) ChartPlotterRoutingAudit.block(chunks, 20, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		for (ChartPlotterTurnPreference shape : new ChartPlotterTurnPreference[]{ChartPlotterTurnPreference.BALANCED, ChartPlotterTurnPreference.SMOOTH}) for (ChartPlotterRouteEffort effort : ChartPlotterRouteEffort.values()) {
			ChartPlotterRouteFinder search = search(data, effort.weight, shape.bias, () -> false);
			ChartPlotterRoute found = search.find();
			assertEquals(ChartPlotterRoute.OK, found.status);
			assertEquals(3, found.n);
			assertTrue(found.valid(data, () -> false));
			ChartPlotterRoute route = ChartPlotterRoute.ok(0, 0, 40, 0, new int[]{0, 18, 22, 40}, new int[]{0, 9, 9, 0}, 4, shape.bias, effort.weight);
			ChartPlotterRoute smooth = ChartPlotterRouteSmoother.smooth(search, route).plan(search.motion, search.hull, -1).connect(data, search.source, search.target);
			assertArrayEquals(new int[]{0, 20, 40}, smooth.x);
			assertArrayEquals(new int[]{0, 10, 0}, smooth.y);
			assertTrue(smooth.valid(data, () -> false));
			assertTrue(ChartPlotterRoutingAudit.length(smooth) > ChartPlotterRoutingAudit.length(route));
			assertTrue(search.routeCost(smooth) < search.routeCost(route));
		}
	}
	@Test
	public void smoothAcceptsMoreDistanceForOneFewerTurnWithoutAComparisonCap() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -9; y <= 9; y++) ChartPlotterRoutingAudit.block(chunks, 25, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		for (ChartPlotterTurnPreference shape : new ChartPlotterTurnPreference[]{ChartPlotterTurnPreference.BALANCED, ChartPlotterTurnPreference.SMOOTH}) {
			ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, null, 0, 0, 50, 0, shape.bias, 1, 100, () -> false);
			ChartPlotterRoute found = search.find();
			ChartPlotterRoute route = ChartPlotterRoute.ok(0, 0, 50, 0, new int[]{0, 20, 30, 50}, new int[]{0, 10, 10, 0}, 4, shape.bias, 100);
			ChartPlotterRoute smooth = ChartPlotterRouteSmoother.smooth(search, route).plan(search.motion, search.hull, -1).connect(data, search.source, search.target);
			assertEquals(shape == ChartPlotterTurnPreference.SMOOTH ? 3 : 4, smooth.n);
			assertEquals(smooth.n, found.n);
			assertTrue(smooth.valid(data, () -> false));
			assertTrue(search.routeCost(smooth) <= search.routeCost(route));
			if (shape == ChartPlotterTurnPreference.SMOOTH) assertTrue(ChartPlotterRoutingAudit.length(smooth) > ChartPlotterRoutingAudit.length(route) * 1.1);
		}
	}
	@Test
	public void smoothRejectsALargeDetourForOneFewerTurn() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-20, -20, 40, 40);
		for (int y = -9; y <= 9; y++) ChartPlotterRoutingAudit.block(chunks, 100, y);
		ChartPlotterRoutingAudit.block(chunks, 100, 50);
		ChartPlotterRoutingAudit.block(chunks, 100, -50);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, null, 0, 0, 200, 0, 40, 1, 100, () -> false);
		search.hull = new ChartPlotterRouteHull(null, search.motion, 0, false);
		ChartPlotterRoute route = ChartPlotterRoute.ok(0, 0, 200, 0, new int[]{0, 20, 180, 200}, new int[]{0, 10, 10, 0}, 4, 40, 100);
		ChartPlotterRoute detour = ChartPlotterRoute.ok(0, 0, 200, 0, new int[]{0, 100, 200}, new int[]{0, 100, 0}, 3, 40, 100).plan(search.motion, search.hull, -1).connect(data, null, ChartPlotterRouteTarget.create(data, 200, 0, () -> false));
		assertTrue(detour.valid(data, () -> false));
		assertTrue(search.routeCost(detour) > search.routeCost(route));
		ChartPlotterRoute smooth = ChartPlotterRouteSmoother.smooth(search, route);
		assertEquals(route.n, smooth.n);
		assertTrue(search.routeCost(smooth) <= search.routeCost(route));
	}
	@Test
	public void smoothingCanMoveACornerWithoutRemovingATurn() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -8; y <= 8; y++) ChartPlotterRoutingAudit.block(chunks, 20, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		for (int bias : new int[]{5, 40}) {
			ChartPlotterRouteFinder search = search(data, 140, bias, () -> false);
			search.find();
			ChartPlotterRoute route = ChartPlotterRoute.ok(0, 0, 40, 0, new int[]{0, 0, 40}, new int[]{0, 20, 0}, 3, bias, 140);
			ChartPlotterRoute smooth = ChartPlotterRouteSmoother.smooth(search, route).plan(search.motion, search.hull, -1).connect(data, search.source, search.target);
			assertEquals(route.n, smooth.n);
			assertArrayEquals(new int[]{0, 20, 40}, smooth.x);
			assertArrayEquals(new int[]{0, 10, 0}, smooth.y);
			assertTrue(smooth.valid(data, () -> false));
			assertTrue(search.routeCost(smooth) < search.routeCost(route));
		}
	}
	@Test
	public void refinementDeadlineKeepsTheCompletedSmoothRoute() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -10; y <= 10; y++) ChartPlotterRoutingAudit.block(chunks, 20, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRouteFinder[] search = new ChartPlotterRouteFinder[1];
		ChartPlotterPathHeap[] first = new ChartPlotterPathHeap[1];
		AtomicBoolean cancelled = new AtomicBoolean();
		search[0] = search(data, 100, 40, () -> {
			if (first[0] == null) first[0] = search[0].heap;
			else if (first[0] != search[0].heap) cancelled.set(true);
			return cancelled.get();
		});
		ChartPlotterRoute route = search[0].find();
		assertTrue(cancelled.get());
		assertEquals(ChartPlotterRoute.OK, route.status);
		assertTrue(route.valid(data, () -> false));
		assertEquals(search[0].routeCost(route), search[0].routeCost);
	}
	@Test
	public void smoothingDeadlineKeepsTheCompletedRoute() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-4, -4, 8, 8));
		AtomicInteger checks = new AtomicInteger();
		ChartPlotterRouteFinder search = search(data, 100, 40, () -> checks.incrementAndGet() > 4);
		search.hull = new ChartPlotterRouteHull(null, search.motion, 0, false);
		ChartPlotterRoute route = ChartPlotterRoute.ok(0, 0, 40, 0, new int[]{0, 10, 10, 20, 20, 40}, new int[]{0, 0, 10, 10, 0, 0}, 6, 40, 100);
		assertSame(route, ChartPlotterRouteSmoother.smooth(search, route));
		assertTrue(checks.get() > 4);
	}
	private static ChartPlotterRouteFinder search(ChartPlotterCollisionData data, int weight, int bias, java.util.function.BooleanSupplier cancel) {return new ChartPlotterRouteFinder(data, null, 0, 0, 40, 0, bias, 1, weight, cancel);}
}
