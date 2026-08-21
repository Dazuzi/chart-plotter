package com.chartplotter.route;
import com.chartplotter.ChartPlotterRouteEffort;
import org.junit.Test;
import static org.junit.Assert.*;
public class ChartPlotterTripTest {
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
		ChartPlotterTrip replanned = removed.pending(6, 5, 6, 0, ChartPlotterRouteEffort.BALANCED.weight, ChartPlotterRouteEffort.BALANCED, new boolean[]{false, true, false});
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
	public void subsequentLegStartsAtWaypoint() {
		ChartPlotterRoute route = ChartPlotterRoute.ok(0, 0, 10, 20, new int[]{0, 7}, new int[]{0, 12}, 2, 0, 250);
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 10, 20, route);
		assertEquals(10, ChartPlotterRoutes.legStartX(trip, 1, 5));
		assertEquals(20, ChartPlotterRoutes.legStartY(trip, 1, 6));
		assertEquals(5, ChartPlotterRoutes.legStartX(trip, 0, 5));
		assertEquals(6, ChartPlotterRoutes.legStartY(trip, 0, 6));
	}
	@Test
	public void replansLegsFromLiveStartAndPreviousStop() {
		ChartPlotterTrip trip = ChartPlotterTrip.single(1, 10, 20, route(0, 0, 10, 20)).append(2, 30, 40, route(10, 20, 30, 40));
		trip = trip.pending(3, 5, 6, 7, ChartPlotterRouteEffort.HIGH.weight, ChartPlotterRouteEffort.HIGH, new boolean[]{true, true});
		assertTrue(trip.route(0).start(5, 6));
		assertTrue(trip.route(1).start(10, 20));
		assertEquals(30, trip.route(1).tx);
		assertEquals(40, trip.route(1).ty);
	}
	private static ChartPlotterRoute route(int sx, int sy, int tx, int ty) {return ChartPlotterRoute.pending(sx, sy, tx, ty, 0, 250);}
}
