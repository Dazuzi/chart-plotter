package com.chartplotter.route;
import com.chartplotter.ChartPlotterRouteEffort;
import org.junit.Test;
import static org.junit.Assert.*;
public class ChartPlotterTripTest {
	@Test
	public void recalculationKeepsTheVisibleRouteUntilItsReplacementArrives() {
		ChartPlotterRoute original = ChartPlotterRoute.ok(0, 0, 100, 0, new int[]{0, 100}, new int[]{0, 0}, 2, 5, 100);
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 100, 0, original);
		ChartPlotterTrip pending = trip.pending(2, 20, 20, 5, 100, ChartPlotterRouteEffort.MAXIMUM, new boolean[]{true});
		ChartPlotterRoute pendingRoute = pending.active();
		assertNotNull(pendingRoute);
		assertEquals(ChartPlotterRoute.OK, pendingRoute.status);
		assertSame(original.x, pendingRoute.x);
		assertSame(original.y, pendingRoute.y);
		assertTrue(pendingRoute.recalculating);
		assertEquals(2, pending.generation());
		ChartPlotterRoute replacement = ChartPlotterRoute.ok(20, 20, 100, 0, new int[]{20, 60, 100}, new int[]{20, 0, 0}, 3, 5, 100);
		assertSame(replacement, pending.route(0, replacement).active());
		assertFalse(replacement.recalculating);
		assertSame(pendingRoute, pending.active());
		ChartPlotterTrip appended = pending.append(3, 200, 0, route(100, 0, 200, 0));
		ChartPlotterRoute appendedRoute = appended.active();
		assertNotNull(appendedRoute);
		assertTrue(appendedRoute.recalculating);
		ChartPlotterRoute advanced = appendedRoute.advance(20.5, 0.5, 20, 32, 2);
		assertNotNull(advanced);
		assertTrue(advanced.recalculating);
		ChartPlotterTrip retry = appended.route(0, advanced).route(0, route(20, 0, 100, 0));
		ChartPlotterRoute retryRoute = retry.active();
		assertNotNull(retryRoute);
		assertSame(advanced.x, retryRoute.x);
		assertTrue(retryRoute.recalculating);
		ChartPlotterTrip moved = trip.move(3, 0, 100, 100).pending(3, 20, 20, 5, 100, ChartPlotterRouteEffort.MAXIMUM, new boolean[]{true});
		ChartPlotterRoute movedRoute = moved.active();
		assertNotNull(movedRoute);
		assertEquals(ChartPlotterRoute.PENDING, movedRoute.status);
		assertEquals(100, movedRoute.ty);
	}
	@Test
	public void destinationMarkersStayFixedWhenTheArrivalPositionChanges() {
		ChartPlotterRoute route = ChartPlotterRoute.ok(0, 0, 100, 100, new int[]{0, 97}, new int[]{0, 95}, 2, 5, 100).plan(new ChartPlotterRouteMotion(3, 0.25, 0.75), null, 1536);
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 100, 100, route);
		assertEquals(100, trip.x(0));
		assertEquals(100, trip.y(0));
		ChartPlotterRoutes.Turn turn = ChartPlotterRoutes.turn(route, 0, 0, 0, 0, 3, 0);
		assertEquals(100, turn.x);
		assertEquals(100, turn.y);
		assertEquals(0.5, turn.offsetX, 0);
		assertEquals(0.5, turn.offsetY, 0);
		assertTrue(turn.end);
		ChartPlotterRoute replacement = ChartPlotterRoute.ok(0, 0, 100, 100, new int[]{0, 92}, new int[]{0, 100}, 2, 5, 100);
		trip = trip.route(0, replacement);
		assertEquals(100, trip.x(0));
		assertEquals(100, trip.y(0));
		turn = ChartPlotterRoutes.turn(trip.active(), 0, 0, 0, 0, 3, 0);
		assertEquals(100, turn.x);
		assertEquals(100, turn.y);
		assertTrue(turn.end);
		ChartPlotterTrip moved = trip.move(2, 0, 150, 200);
		assertEquals(150, moved.x(0));
		assertEquals(200, moved.y(0));
	}
	@Test
	public void stopNumberAdvancesWhileTotalRemainsStable() {
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 10, 20, route(0, 0, 10, 20)).append(2, 30, 40, route(10, 20, 30, 40)).append(3, 50, 60, route(30, 40, 50, 60));
		ChartPlotterTrip original = trip;
		for (int i = 1; i <= 3; i++) {
			assertEquals(i, trip.stopNumber());
			assertEquals(3, trip.totalStops());
			trip = trip.advance(i + 3);
		}
		assertTrue(trip.empty());
		assertEquals(0, trip.stopNumber());
		assertEquals(0, trip.totalStops());
		assertEquals(1, original.stopNumber());
		assertEquals(3, original.totalStops());
		trip = ChartPlotterTrip.single(7, 70, 80, route(50, 60, 70, 80));
		assertEquals(1, trip.stopNumber());
		assertEquals(1, trip.totalStops());
	}
	@Test
	public void editingRemainingStopsUpdatesTotalWithoutAdvancingProgress() {
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 10, 20, route(0, 0, 10, 20)).append(2, 30, 40, route(10, 20, 30, 40)).append(3, 50, 60, route(30, 40, 50, 60)).advance(4);
		trip = trip.append(5, 70, 80, route(50, 60, 70, 80));
		assertEquals(2, trip.stopNumber());
		assertEquals(4, trip.totalStops());
		ChartPlotterTrip removed = trip.remove(6, 0);
		assertEquals(2, removed.stopNumber());
		assertEquals(3, removed.totalStops());
		ChartPlotterTrip truncated = trip.truncate(7, 1);
		assertEquals(2, truncated.stopNumber());
		assertEquals(2, truncated.totalStops());
		assertEquals(0, truncated.remove(8, 0).totalStops());
		assertEquals(0, trip.truncate(9, 0).totalStops());
	}
	@Test
	public void movingAndReplanningStopsPreservesProgress() {
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 10, 20, route(0, 0, 10, 20)).append(2, 30, 40, route(10, 20, 30, 40)).append(3, 50, 60, route(30, 40, 50, 60)).advance(4);
		trip = trip.move(5, 0, 35, 45).pending(6, 10, 20, 0, ChartPlotterRouteEffort.MAXIMUM.weight, ChartPlotterRouteEffort.MAXIMUM, new boolean[]{true, true});
		trip = trip.route(0, route(10, 20, 35, 45)).generation(7);
		assertEquals(2, trip.stopNumber());
		assertEquals(3, trip.totalStops());
	}
	@Test
	public void truncatesClickedStopAndTail() {
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 10, 20, route(0, 0, 10, 20));
		trip = trip.append(2, 30, 40, route(10, 20, 30, 40));
		trip = trip.append(3, 50, 60, route(30, 40, 50, 60));
		ChartPlotterTrip truncated = trip.truncate(4, 1);
		assertEquals(1, truncated.size());
		assertEquals(10, truncated.x(0));
		assertEquals(20, truncated.y(0));
		assertSame(trip.route(0), truncated.active());
		assertTrue(trip.truncate(5, 0).empty());
	}
	@Test
	public void removesOnlySelectedStop() {
		ChartPlotterRoute first = route(0, 0, 10, 20);
		ChartPlotterRoute second = route(10, 20, 30, 40);
		ChartPlotterRoute third = route(30, 40, 50, 60);
		ChartPlotterRoute fourth = route(50, 60, 70, 80);
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 10, 20, first).append(2, 30, 40, second).append(3, 50, 60, third).append(4, 70, 80, fourth);
		ChartPlotterTrip removed = trip.remove(5, 1);
		assertEquals(3, removed.size());
		assertEquals(10, removed.x(0));
		assertEquals(20, removed.y(0));
		assertEquals(50, removed.x(1));
		assertEquals(60, removed.y(1));
		assertEquals(70, removed.x(2));
		assertEquals(80, removed.y(2));
		assertSame(first, removed.route(0));
		assertNull(removed.route(1));
		assertSame(fourth, removed.route(2));
		ChartPlotterTrip replanned = removed.pending(6, 5, 6, 0, ChartPlotterRouteEffort.REFINED.weight, ChartPlotterRouteEffort.REFINED, new boolean[]{false, true, false});
		assertTrue(replanned.route(1).start(10, 20));
		assertEquals(50, replanned.route(1).tx);
		assertEquals(60, replanned.route(1).ty);
		assertSame(fourth, replanned.route(2));
		assertEquals(4, trip.size());
	}
	@Test
	public void removesBoundaryStops() {
		ChartPlotterRoute first = route(0, 0, 10, 20);
		ChartPlotterRoute second = route(10, 20, 30, 40);
		ChartPlotterRoute third = route(30, 40, 50, 60);
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 10, 20, first).append(2, 30, 40, second).append(3, 50, 60, third);
		ChartPlotterTrip firstRemoved = trip.remove(4, 0);
		assertEquals(2, firstRemoved.size());
		assertEquals(30, firstRemoved.x(0));
		assertNull(firstRemoved.route(0));
		assertSame(third, firstRemoved.route(1));
		ChartPlotterTrip lastRemoved = trip.remove(5, 2);
		assertEquals(2, lastRemoved.size());
		assertSame(first, lastRemoved.route(0));
		assertSame(second, lastRemoved.route(1));
		assertTrue(ChartPlotterTrip.single(1, 10, 20, first).remove(6, 0).empty());
	}
	@Test
	public void advancesToPrecomputedLeg() {
		ChartPlotterRoute first = route(0, 0, 10, 20);
		ChartPlotterRoute second = route(10, 20, 30, 40);
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 10, 20, first).append(2, 30, 40, second).advance(3);
		assertEquals(1, trip.size());
		assertEquals(30, trip.x(0));
		assertEquals(40, trip.y(0));
		assertSame(second, trip.active());
	}
	@Test
	public void generationChangePreservesTripSnapshot() {
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 10, 20, route(0, 0, 10, 20)).append(2, 30, 40, route(10, 20, 30, 40)).generation(3);
		assertEquals(2, trip.size());
		assertEquals(10, trip.x(0));
		assertEquals(40, trip.y(1));
		assertNotNull(trip.route(0));
		assertNotNull(trip.route(1));
	}
	@Test
	public void movesOnlySelectedStop() {
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 10, 20, route(0, 0, 10, 20)).append(2, 30, 40, route(10, 20, 30, 40));
		ChartPlotterTrip moved = trip.move(3, 0, 15, 25);
		assertEquals(15, moved.x(0));
		assertEquals(25, moved.y(0));
		assertEquals(30, moved.x(1));
		assertEquals(40, moved.y(1));
		assertSame(trip.route(0), moved.route(0));
		assertSame(trip.route(1), moved.route(1));
	}
	@Test
	public void subsequentLegStartsAtTheActualArrivalPoint() {
		ChartPlotterRoute route = ChartPlotterRoute.ok(0, 0, 10, 20, new int[]{0, 7}, new int[]{0, 12}, 2, 0, 250);
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 10, 20, route);
		assertEquals(7, ChartPlotterRoutes.legStartX(trip, 1, 5));
		assertEquals(12, ChartPlotterRoutes.legStartY(trip, 1, 6));
		assertEquals(5, ChartPlotterRoutes.legStartX(trip, 0, 5));
		assertEquals(6, ChartPlotterRoutes.legStartY(trip, 0, 6));
	}
	@Test
	public void replansLegsFromLiveStartAndPreviousStop() {
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 10, 20, route(0, 0, 10, 20)).append(2, 30, 40, route(10, 20, 30, 40));
		trip = trip.pending(3, 5, 6, 7, ChartPlotterRouteEffort.MAXIMUM.weight, ChartPlotterRouteEffort.MAXIMUM, new boolean[]{true, true});
		assertTrue(trip.route(0).start(5, 6));
		assertTrue(trip.route(1).start(10, 20));
		assertEquals(30, trip.route(1).tx);
		assertEquals(40, trip.route(1).ty);
	}
	@Test
	public void remainingDistanceFollowsTurnsAndIncludesLaterStops() {
		ChartPlotterRoute first = ChartPlotterRoute.ok(0, 0, 3, 4, new int[]{0, 3, 3}, new int[]{0, 0, 4}, 3, 0, 250);
		ChartPlotterRoute second = ChartPlotterRoute.ok(3, 4, 6, 8, new int[]{3, 6}, new int[]{4, 8}, 2, 0, 250);
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 3, 4, first).append(2, 6, 8, second);
		assertEquals(6, trip.distance(1.5, 0.5, false), 1e-9);
		assertEquals(11, trip.distance(1.5, 0.5, true), 1e-9);
		assertEquals(5, trip.advance(3).distance(3.5, 4.5, true), 1e-9);
	}
	@Test
	public void unresolvedLaterLegDoesNotHideNextStopDistance() {
		ChartPlotterRoute first = ChartPlotterRoute.ok(0, 0, 3, 4, new int[]{0, 3}, new int[]{0, 4}, 2, 0, 250);
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 3, 4, first).append(2, 6, 8, route(3, 4, 6, 8));
		assertEquals(5, trip.distance(0.5, 0.5, false), 1e-9);
		assertTrue(Double.isNaN(trip.distance(0.5, 0.5, true)));
		assertTrue(Double.isNaN(trip.route(1, null).distance(0.5, 0.5, true)));
		assertTrue(Double.isNaN(trip.route(0, ChartPlotterRoute.none(0, 0, 3, 4, 0, 250)).distance(0.5, 0.5, false)));
		assertTrue(Double.isNaN(ChartPlotterTrip.empty(1).distance(0.5, 0.5, true)));
	}
	@Test
	public void remainingDistanceHandlesPrunedFinalPoint() {
		ChartPlotterRoute route = ChartPlotterRoute.ok(3, 4, 3, 4, new int[]{3}, new int[]{4}, 1, 0, 250);
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 3, 4, route);
		assertEquals(1.5, trip.distance(2, 4.5, true), 1e-9);
		assertEquals(0, trip.distance(3.5, 4.5, true), 0);
	}
	private static ChartPlotterRoute route(int sx, int sy, int tx, int ty) {return ChartPlotterRoute.pending(sx, sy, tx, ty, 0, 250);}
}
