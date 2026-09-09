package com.chartplotter.route;
import com.chartplotter.collision.ChartPlotterCollisionData;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import static org.junit.Assert.*;
public class ChartPlotterRouteFinderTest {
	@Test
	public void followsNearbyLaterSegmentsAndOnlyReplansOutsideTheTolerance() {
		ChartPlotterRoute route = ChartPlotterRoute.ok(0, 0, 100, 100, new int[]{0, 100, 100}, new int[]{0, 0, 100}, 3, 5, 100);
		assertSame(route, route.advance(40.5, -31.5, 20, 32, 2));
		assertNull(route.advance(40.5, -32.5, 20, 32, 2));
		ChartPlotterRoute advanced = route.advance(115.5, 70.5, 20, 32, 2);
		assertNotNull(advanced);
		assertEquals(2, advanced.n);
		assertArrayEquals(new int[]{100, 100}, advanced.x);
		assertArrayEquals(new int[]{72, 100}, advanced.y);
	}
	@Test
	public void pruningStaysAheadOnLongMovementStepsWithoutChangingTheirHeading() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-4, -4, 16, 16));
		ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, null, 0, 0, 40, 20, 5, 3, 100, null, () -> false);
		search.find();
		int d = 1;
		int dx = search.motion.x[d];
		int dy = search.motion.y[d];
		ChartPlotterRoute route = ChartPlotterRoute.ok(0, 0, dx * 10, dy * 10, new int[]{0, dx * 10}, new int[]{0, dy * 10}, 2, 5, 100).plan(search.motion, search.hull, ChartPlotterRouteMoves.OR[d]);
		double bx = dx * 2.4;
		double by = dy * 2.4;
		ChartPlotterRoute advanced = route.advance(bx + route.offsetX, by + route.offsetY, 20, 32, 2);
		assertNotNull(advanced);
		assertTrue((advanced.x[0] - bx) * dx + (advanced.y[0] - by) * dy >= 2 * Math.hypot(dx, dy));
		assertEquals(ChartPlotterRouteMoves.OR[d], advanced.heading);
		assertTrue(advanced.valid(data, () -> false));
		assertSame(advanced, advanced.advance(bx + route.offsetX, by + route.offsetY, 20, 32, 2));
	}
	@Test
	public void coastalArrivalDoesNotRequireEnteringTheExactWaypoint() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
		for (int y = 0; y < 40; y++) for (int x = -32; x < 40; x++) if (y != 0 || x < 19 || x > 21) ChartPlotterRoutingAudit.block(chunks, x, y);
		for (boolean detour : new boolean[]{false, true}) {
			if (detour) for (int x = 18; x <= 22; x++) ChartPlotterRoutingAudit.block(chunks, x, -17);
			ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
			for (double speed : new double[]{0.5, 1, 3}) {
				ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.config(64, 256), 20, -20, 20, 0, 5, speed, 100, null, () -> false);
				ChartPlotterRoute route = search.find();
				assertEquals(ChartPlotterCollisionData.OPEN, search.hull.flag(data, 20, 0, -1));
				for (int d = 0; d < 16; d++) assertNotEquals(ChartPlotterCollisionData.OPEN, search.hull.move[d].flag(data, 20 - search.motion.x[d], -search.motion.y[d]));
				assertEquals(ChartPlotterRoute.OK, route.status);
				assertEquals(20, route.tx);
				assertEquals(0, route.ty);
				assertTrue(route.y[route.n - 1] < 0);
				assertTrue(route.valid(data, () -> false));
				assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, search.config, route));
			}
		}
		for (int x = -32; x < 40; x++) ChartPlotterRoutingAudit.block(chunks, x, -17);
		assertEquals(ChartPlotterRoute.NO_ROUTE, new ChartPlotterRouteFinder(new ChartPlotterCollisionData(chunks), ChartPlotterRoutingAudit.config(64, 256), 20, -20, 20, 0, 5, 1, 100, null, () -> false).find().status);
	}
	@Test
	public void pruningToAWaypointRetainsTheIncomingTurnClearance() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(new ChartPlotterCollisionData(chunks), ChartPlotterRoutingAudit.offsetHull(), 0, 0, 20, 20, 5, 1, 100, null, () -> false);
		search.find();
		ChartPlotterRoute route = ChartPlotterRoute.ok(0, 0, 20, 20, new int[]{0, 20, 20}, new int[]{0, 0, 20}, 3, 5, 100).plan(search.motion, search.hull, 1536);
		ChartPlotterRoute advanced = route.advance(20.5, 0.5, 2, 8, 0);
		assertNotNull(advanced);
		assertEquals(1536, advanced.heading);
		for (int cell : search.hull.turn[4 * 16].coordinates) {
			Map<Long, ChartPlotterCollisionData.Chunk> changed = new HashMap<>(chunks);
			ChartPlotterRoutingAudit.block(changed, 20 + (cell >> 16), (short) cell);
			ChartPlotterCollisionData data = new ChartPlotterCollisionData(changed);
			if (search.hull.hull[4].flag(data, 20, 0) != ChartPlotterCollisionData.OPEN || !advanced.plan(search.motion, search.hull, 1024).valid(data, () -> false)) continue;
			assertFalse(advanced.valid(data, () -> false));
			return;
		}
		fail("No turn-only obstacle tested");
	}
	@Test
	public void stationaryShipDoesNotConsumeThePlannedRoute() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-4, -4, 4, 4));
		ChartPlotterRoute route = new ChartPlotterRouteFinder(data, null, 0, 0, 1, 20, 5, 1, 100, null, () -> false).find();
		assertEquals(ChartPlotterRoute.OK, route.status);
		assertSame(route, route.advance(0.5, 0.5, 2, 8, 0));
		ChartPlotterRoute advanced = route.advance(route.x[route.n - 1] + 0.5, route.y[route.n - 1] + 0.5, 2, 8, 0);
		assertNotNull(advanced);
		assertEquals(1, advanced.n);
		assertTrue(advanced.valid(data, () -> false));
	}
	@Test
	public void unrelatedCollisionChangesKeepThePrunedRouteValid() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
		ChartPlotterRoute route = new ChartPlotterRouteFinder(new ChartPlotterCollisionData(chunks), null, 0, 0, 30, 0, 5, 1, 100, null, () -> false).find();
		route = route.advance(5.5, 0.5, 2, 8, 0);
		assertNotNull(route);
		ChartPlotterRoutingAudit.block(chunks, 1, 0);
		ChartPlotterRoutingAudit.block(chunks, 20, 20);
		assertTrue(route.valid(new ChartPlotterCollisionData(chunks), () -> false));
		ChartPlotterRoutingAudit.block(chunks, 10, 0);
		assertFalse(route.valid(new ChartPlotterCollisionData(chunks), () -> false));
	}
	@Test
	public void newObstacleInvalidatesAnExistingRoute() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
		ChartPlotterCollisionData original = new ChartPlotterCollisionData(chunks);
		ChartPlotterRoute route = new ChartPlotterRouteFinder(original, null, 0, 0, 30, 0, 5, 1, 100, null, () -> false).find();
		assertTrue(route.valid(original, () -> false));
		ChartPlotterRoutingAudit.block(chunks, 10, 0);
		ChartPlotterCollisionData changed = new ChartPlotterCollisionData(chunks);
		assertFalse(route.valid(changed, () -> false));
		ChartPlotterRoute replacement = new ChartPlotterRouteFinder(changed, null, 0, 0, 30, 0, 5, 1, 100, null, () -> false).find();
		assertEquals(ChartPlotterRoute.OK, replacement.status);
		assertTrue(replacement.n > 2);
		assertTrue(replacement.valid(changed, () -> false));
	}
	@Test
	public void followingDoesNotJumpToAParallelFutureLeg() {
		ChartPlotterRoute route = ChartPlotterRoute.ok(0, 0, 0, 7, new int[]{0, 100, 100, 0}, new int[]{0, 0, 7, 7}, 4, 5, 100);
		ChartPlotterRoute advanced = route.advance(50.5, 4.5, 20, 32, 0);
		assertNotNull(advanced);
		assertEquals(4, advanced.n);
		assertEquals(50, advanced.x[0]);
		assertEquals(0, advanced.y[0]);
		assertEquals(100, advanced.x[1]);
		assertEquals(0, advanced.y[1]);
	}
	@Test
	public void startsNearTheBoatWhenItsAnchorOrFootprintTouchesAPier() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
		ChartPlotterRoutingAudit.block(chunks, 1, 0);
		for (int sx : new int[]{0, 1}) {
			ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
			ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.config(128, 384), sx, 0, 0, -30, 5, 1, 100, null, () -> false);
			ChartPlotterRoute route = search.find();
			assertEquals(ChartPlotterCollisionData.BLOCKED, search.hull.flag(data, sx, 0, -1));
			assertEquals(ChartPlotterRoute.OK, route.status);
			assertEquals(sx, route.sx);
			assertEquals(0, route.sy);
			assertTrue(Math.max(Math.abs(route.x[0] - sx), Math.abs(route.y[0])) <= ChartPlotterRoutes.REACH_RADIUS);
			assertEquals(0, route.tx);
			assertEquals(-30, route.ty);
			assertEquals(0.5, route.offsetX, 0);
			assertEquals(0.5, route.offsetY, 0);
			assertTrue(route.valid(data, () -> false));
			assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, search.config, route));
		}
	}
	@Test
	public void departureUsesTheCourseBearingBesideAPier() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
		for (int x = -32; x < 40; x++) ChartPlotterRoutingAudit.block(chunks, x, 1);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.config(64, 256), 0, 0, 20, 0, 5, 1, 100, null, () -> false);
		ChartPlotterRoute route = search.find();
		assertEquals(ChartPlotterCollisionData.BLOCKED, search.hull.hull[0].flag(data, 0, 0));
		assertEquals(ChartPlotterCollisionData.BLOCKED, search.hull.turn[4].flag(data, 0, 0));
		assertEquals(ChartPlotterRoute.OK, route.status);
		assertEquals(2, route.n);
		assertArrayEquals(new int[]{0, 6}, route.x);
		assertArrayEquals(new int[]{0, 0}, route.y);
		assertEquals(-1, route.heading);
		assertTrue(route.valid(data, () -> false));
		assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, search.config, route));
	}
	@Test
	public void departureTileSnappingIsLimited() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
		for (int y = 1; y < 40; y++) for (int x = -32; x < 40; x++) ChartPlotterRoutingAudit.block(chunks, x, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRouteHull hull = new ChartPlotterRouteHull(ChartPlotterRoutingAudit.offsetHull(), new ChartPlotterRouteMotion(3, 0.5, 0.5), 0, false);
		assertEquals(ChartPlotterCollisionData.BLOCKED, hull.flag(data, 20, 0, -1));
		for (int x : new int[]{-20, 0, 20, 30}) {
			assertEquals(ChartPlotterCollisionData.key(20, 0), ChartPlotterRoutes.target(data, 20, 0, x, -20, 2));
			assertEquals(ChartPlotterCollisionData.key(20, 0), ChartPlotterRoutes.target(data, 20, 1, x, -20, 2));
			assertEquals(ChartPlotterCollisionData.key(20, 0), ChartPlotterRoutes.target(data, 20, 2, x, -20, 2));
			assertEquals(ChartPlotterCollisionData.key(20, 3), ChartPlotterRoutes.target(data, 20, 3, x, -20, 2));
		}
	}
	@Test
	public void incomingBearingsInOpenWaterRetainTheRouteAndItsDestination() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-4, -4, 12, 12));
		ChartPlotterRoute route = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), 0, 0, 0, 80, 5, 3, 100, null, () -> false).find();
		assertEquals(ChartPlotterRoute.OK, route.status);
		assertEquals(2, route.n);
		for (int heading = 0; heading < 2048; heading += 128) {
			ChartPlotterRoute rotated = route.plan(route.motion, route.hull, heading);
			assertSame(route.x, rotated.x);
			assertSame(route.y, rotated.y);
			assertTrue(rotated.valid(data, () -> false));
			ChartPlotterRoutes.Turn marker = ChartPlotterRoutes.turn(rotated, 0.25, 0.75, 0, 0, 3, 0);
			assertTrue(marker.end);
			assertEquals(0, marker.x);
			assertEquals(route.y[route.n - 1], marker.y);
		}
	}
	@Test
	public void incomingSegmentRechecksTheTurnNearAnObstacle() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
		ChartPlotterRoute route = new ChartPlotterRouteFinder(new ChartPlotterCollisionData(chunks), ChartPlotterRoutingAudit.offsetHull(), 0, 0, 20, 0, 5, 1, 100, null, () -> false).find();
		for (int cell : route.hull.turn[4].coordinates) {
			Map<Long, ChartPlotterCollisionData.Chunk> changed = new HashMap<>(chunks);
			ChartPlotterRoutingAudit.block(changed, cell >> 16, (short) cell);
			ChartPlotterCollisionData data = new ChartPlotterCollisionData(changed);
			if (!route.valid(data, () -> false) || route.hull.hull[0].flag(data, 0, 0) != ChartPlotterCollisionData.OPEN) continue;
			assertFalse(route.plan(route.motion, route.hull, 1024).valid(data, () -> false));
			return;
		}
		fail("No departure turn obstacle tested");
	}
	@Test
	public void targetSelectionMatchesBruteForcePerimeters() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
		Random random = new Random(7);
		for (int x = -30; x <= 30; x++) for (int y = -30; y <= 30; y++) if (random.nextInt(4) != 0) ChartPlotterRoutingAudit.block(chunks, x, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		for (int i = 0; i < 60; i++) {
			int tx = random.nextInt(41) - 20;
			int ty = random.nextInt(41) - 20;
			int sx = random.nextInt(81) - 40;
			int sy = random.nextInt(81) - 40;
			assertEquals(targetReference(data, tx, ty, sx, sy), ChartPlotterRoutes.target(data, tx, ty, sx, sy, 2));
		}
	}
	@Test
	public void failureSnapshotPreservesOtherGenerationsAndFinishedLegs() {
		ChartPlotterRoute good = ChartPlotterRoute.ok(0, 0, 10, 0, new int[]{0, 10}, new int[]{0, 0}, 2, 5, 100);
		ChartPlotterTrip trip = ChartPlotterTrip.single(7, 10, 0, good).append(7, 20, 0, ChartPlotterRoute.pending(10, 0, 20, 0, 5, 100));
		assertSame(trip, trip.failed(6, new boolean[]{true, true}));
		ChartPlotterTrip failed = trip.failed(7, new boolean[]{false, true});
		assertSame(good, failed.route(0));
		assertEquals(ChartPlotterRoute.FAILED, failed.route(1).status);
		assertEquals(ChartPlotterRoute.PENDING, trip.route(1).status);
		assertEquals(ChartPlotterRoute.FAILED, trip.failed(7, new boolean[]{true, true}).route(0).status);
	}
	@Test
	public void stationaryRoutesAreRevalidatedWithoutAnIncomingHeading() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
		ChartPlotterCollisionData original = new ChartPlotterCollisionData(chunks);
		ChartPlotterRoute point = new ChartPlotterRouteFinder(original, null, 0, 0, 0, 0, 5, 1, 100, null, () -> false).find();
		assertTrue(point.valid(original, () -> false));
		assertFalse(point.valid(original, () -> true));
		ChartPlotterRoutingAudit.block(chunks, 0, 0);
		assertFalse(point.valid(new ChartPlotterCollisionData(chunks), () -> false));
		chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
		ChartPlotterRoute route = new ChartPlotterRouteFinder(original, ChartPlotterRoutingAudit.offsetHull(), 0, 0, 0, 0, 5, 1, 100, null, () -> false).find();
		assertEquals(ChartPlotterRoute.OK, route.status);
		assertTrue(route.valid(original, () -> false));
		ChartPlotterRoutingAudit.block(chunks, 0, 0);
		assertFalse(route.valid(new ChartPlotterCollisionData(chunks), () -> false));
	}
	private static long targetReference(ChartPlotterCollisionData data, int tx, int ty, int sx, int sy) {
		int f = data.flagAt(tx, ty);
		if (f == ChartPlotterCollisionData.UNKNOWN || f == ChartPlotterCollisionData.OPEN) return ChartPlotterCollisionData.key(tx, ty);
		int bx = tx;
		int by = ty;
		int distance = Integer.MAX_VALUE;
		long best = Long.MAX_VALUE;
		for (int r = 1; r <= 2; r++) {
			for (int y = ty - r; y <= ty + r; y++) {
				for (int x = tx - r; x <= tx + r; x++) {
					if (Math.max(Math.abs(x - tx), Math.abs(y - ty)) != r || data.flagAt(x, y) != ChartPlotterCollisionData.OPEN) continue;
					long dx = x - sx;
					long dy = y - sy;
					long score = dx * dx + dy * dy;
					int d = (x - tx) * (x - tx) + (y - ty) * (y - ty);
					if (d > distance || d == distance && score >= best) continue;
					bx = x;
					by = y;
					distance = d;
					best = score;
				}
			}
			if (best != Long.MAX_VALUE) return ChartPlotterCollisionData.key(bx, by);
		}
		return ChartPlotterCollisionData.key(tx, ty);
	}
}
