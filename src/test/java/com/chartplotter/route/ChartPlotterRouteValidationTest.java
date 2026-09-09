package com.chartplotter.route;

import com.chartplotter.collision.ChartPlotterCollisionData;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntityConfig;
import org.junit.Test;

import java.awt.geom.Path2D;
import java.util.*;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

public class ChartPlotterRouteValidationTest {
	@Test
	public void freeDepartureSearchMatchesIndependentDijkstra() {
		Random random = new Random(7612);
		for (int trial = 0; trial < 30; trial++) {
			Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(0, 0, 3, 3);
			for (int y = 0; y < 30; y++) for (int x = 0; x < 32; x++) if (x == 8 || random.nextInt(6) == 0 && x != 0 && x != 31) ChartPlotterRoutingAudit.block(chunks, x, y);
			ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
			int bias = trial % 2 == 0 ? 0 : 5;
			double speed = trial % 2 == 0 ? 1 : 3;
			int expected = dijkstra(data, new ChartPlotterRouteMotion(speed, 0.5, 0.5), bias);
			for (int weight : new int[]{100, 110, 140}) {
				ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, null, 0, 0, 31, 0, bias, speed, weight, null, () -> false);
				ChartPlotterRoute result = search.find();
				assertNotNull(search.terrain);
				if (expected == Integer.MAX_VALUE) assertEquals(ChartPlotterRoute.NO_ROUTE, result.status);
				else {
					assertEquals(ChartPlotterRoute.OK, result.status);
					assertTrue("trial=" + trial, search.routeCost >= expected && search.routeCost <= (long) expected * weight / 100);
					assertEquals(search.routeCost, cost(result, search.motion, bias));
				}
			}
		}
	}
	@Test
	public void openWaterRoutesReachAValidApproachAtDifferentSpeeds() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-3, -3, 20, 4));
		for (double speed : new double[]{0.5, 1, 2, 3, 5}) for (int[] target : new int[][]{{40, 5}, {100, 1}}) {
			ChartPlotterRoute route = search(data, ChartPlotterRoutingAudit.offsetHull(), target[0], target[1], speed, () -> false).find();
			assertEquals(ChartPlotterRoute.OK, route.status);
			assertTrue(ChartPlotterRoutes.near(route.x[route.n - 1], route.y[route.n - 1], target[0], target[1]));
			assertTrue(data.clear(route.x[route.n - 1] + 0.5, route.y[route.n - 1] + 0.5, target[0] + 0.5, target[1] + 0.5));
			assertTrue(route.valid(data, () -> false));
			assertArrayEquals(new int[]{0, 0}, ChartPlotterRoutingAudit.clips(data, ChartPlotterRoutingAudit.offsetHull(), route));
		}
	}
	@Test
	public void footprintSweepsContainIndependentPolygonSamples() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-4, -4, 4, 4));
		for (double speed : new double[]{1, 3}) for (boolean reverse : new boolean[]{false, true}) for (double[] phase : new double[][]{{0.5, 0.5}, {0, 0.25}, {0.75, 0.125}}) {
			ChartPlotterRouteMotion motion = new ChartPlotterRouteMotion(speed, phase[0], phase[1]);
			ChartPlotterRouteTerrain terrain = ChartPlotterRouteTerrain.create(data, motion, 0, 0, () -> false);
			assertNotNull(terrain);
			ChartPlotterRouteHull hull = new ChartPlotterRouteHull(ChartPlotterRoutingAudit.offsetHull(), motion, terrain.width, reverse);
			for (int i = 0; i < terrain.clearance.length; i++) if (terrain.clearance[i] != 0) terrain.clearance[i] = 1;
			int origin = terrain.at(0, 0);
			for (int d = 0; d < 16; d++) {
				int orientation = ChartPlotterRouteMoves.OR[d] + (reverse ? 1024 : 0) & 2047;
				for (int step = 0; step <= 32; step++) {
					Path2D shape = polygon(ChartPlotterRoutingAudit.offsetHull(), phase[0] + motion.x[d] * step / 32.0, phase[1] + motion.y[d] * step / 32.0, orientation);
					assertCovered(shape, terrain, origin, hull.move[d]);
				}
				for (int change : new int[]{-8, -3, 1, 5, 8}) {
					int next = d + change & 15;
					for (int step = 0; step <= 64; step++) assertCovered(polygon(ChartPlotterRoutingAudit.offsetHull(), phase[0], phase[1], orientation + change * 2 * step & 2047), terrain, origin, hull.turn[d * 16 + next]);
				}
			}
		}
	}
	@Test
	public void terrainLowerBoundIsConsistentForAcceptedHullMoves() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
		for (int x = -20; x <= 20; x++) ChartPlotterRoutingAudit.block(chunks, x, 0);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRouteMotion motion = new ChartPlotterRouteMotion(3, 0.5, 0.5);
		ChartPlotterRouteTerrain terrain = ChartPlotterRouteTerrain.create(data, motion, 0, -12, () -> false);
		assertNotNull(terrain);
		ChartPlotterRouteHull hull = new ChartPlotterRouteHull(ChartPlotterRoutingAudit.offsetHull(), motion, terrain.width, false);
		ChartPlotterRouteDistances distance = new ChartPlotterRouteDistances(data, terrain, hull, 0, 16, 0, 0, -12, 0, () -> false);
		for (int y = -22; y <= 22; y++) for (int x = -22; x <= 22; x++) for (int d = 0; d < 16; d++) {
			int a = terrain.at(x, y);
			int b = terrain.at(x + motion.x[d], y + motion.y[d]);
			if (!hull.move[d].clear(terrain, a) || b < 0 || distance.get(b) == 0) continue;
			assertTrue(distance.get(a) != 0);
			assertTrue(distance.get(a) <= motion.cost[d] + distance.get(b));
		}
	}
	@Test
	public void shortMoveMasksVisitEveryCrossedTileAtEverySpeed() {
		for (double speed = 0.5; speed <= 16; speed += 0.5) {
			ChartPlotterRouteMotion motion = new ChartPlotterRouteMotion(speed, 0.5, 0.5);
			for (int d = 0; d < 16; d++) {
				assertTrue(ChartPlotterRouteMoves.model(motion.x[d], motion.y[d], speed));
				int next = d + 1 & 15;
				int det = motion.x[d] * motion.y[next] - motion.y[d] * motion.x[next];
				double gx = (motion.cost[d] * (double) motion.y[next] - motion.cost[next] * (double) motion.y[d]) / det;
				double gy = (motion.x[d] * (double) motion.cost[next] - motion.x[next] * (double) motion.cost[d]) / det;
				for (int j = 0; j < 16; j++) assertTrue(gx * motion.x[j] + gy * motion.y[j] <= motion.cost[j] + 1e-6);
				for (int x = -motion.radius; x <= motion.radius; x++) for (int y = -motion.radius; y <= motion.radius; y++) {
					if (!new java.awt.geom.Rectangle2D.Double(x + 1e-8, y + 1e-8, 1 - 2e-8, 1 - 2e-8).intersectsLine(0.5, 0.5, 0.5 + motion.x[d], 0.5 + motion.y[d])) continue;
					int coordinate = x << 16 | y & 65535;
					boolean found = false;
					for (int cell : motion.cells[d]) if (cell == coordinate) {found = true; break;}
					assertTrue("speed=" + speed + " direction=" + d + " tile=" + x + "," + y, found);
				}
			}
		}
	}
	@Test
	public void statusAndCancellationRemainDistinct() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-3, -3, 4, 4);
		for (int y = -14; y <= 14; y++) for (int x = 6; x <= 34; x++) ChartPlotterRoutingAudit.block(chunks, x, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRoute blocked = search(data, null, 20, 0, 1, () -> false).find();
		assertEquals(ChartPlotterRoute.BLOCKED, blocked.status);
		assertEquals(-1, blocked.heading);
		assertEquals(1, blocked.motion.speed, 0);
		assertEquals(ChartPlotterRoute.UNCHARTED, search(data, null, 1000, 0, 1, () -> false).find().status);
		assertEquals(ChartPlotterRoute.OK, search(data, null, 0, 0, 1, () -> false).find().status);
		assertEquals(ChartPlotterRoute.PENDING, search(data, null, 20, 20, 1, () -> true).find().status);
		int[] checks = {0};
		assertEquals(ChartPlotterRoute.PENDING, search(data, null, -20, 0, 1, () -> ++checks[0] > 1).find().status);
		assertEquals(ChartPlotterRoute.OK, search(data, null, -20, 0, 1, () -> false).find().status);
	}
	private static ChartPlotterRouteFinder search(ChartPlotterCollisionData data, WorldEntityConfig config, int tx, int ty, double speed, BooleanSupplier cancel) {return new ChartPlotterRouteFinder(data, config, 0, 0, tx, ty, 5, speed, 100, null, cancel);}
	private static int turn(int a, int b, int bias) {int d = Math.abs(a - b); return a == b || bias == 0 ? 0 : 4000 + 2000 * Math.min(d, 16 - d);}
	private static int cost(ChartPlotterRoute route, ChartPlotterRouteMotion motion, int bias) {
		int cost = 0;
		int last = -1;
		for (int i = 1; i < route.n; i++) {
			int dx = route.x[i] - route.x[i - 1];
			int dy = route.y[i] - route.y[i - 1];
			int d = motion.dir(dx, dy);
			assertTrue(d >= 0);
			int steps = motion.x[d] != 0 ? dx / motion.x[d] : dy / motion.y[d];
			cost += motion.cost[d] * steps + (last < 0 ? 0 : turn(last, d, bias));
			last = d;
		}
		return cost;
	}
	private static int dijkstra(ChartPlotterCollisionData data, ChartPlotterRouteMotion motion, int bias) {
		int[] distance = new int[32 * 32 * 16];
		Arrays.fill(distance, Integer.MAX_VALUE);
		PriorityQueue<int[]> queue = new PriorityQueue<>(Comparator.comparingInt(a -> a[1]));
		for (int d = 0; d < 16; d++) {distance[d] = 0; queue.add(new int[]{d, 0});}
		while (!queue.isEmpty()) {
			int[] entry = queue.remove();
			int a = entry[0];
			if (entry[1] != distance[a]) continue;
			int x = (a >>> 4) % 32;
			int y = (a >>> 4) / 32;
			if (Math.max(Math.abs(x - 31), Math.abs(y)) <= 14 && data.clear(x + 0.5, y + 0.5, 31.5, 0.5)) return entry[1];
			for (int d = 0; d < 16; d++) {
				int nx = x + motion.x[d];
				int ny = y + motion.y[d];
				if (nx < 0 || ny < 0 || nx >= 32 || ny >= 32) continue;
				boolean open = true;
				for (int yy = Math.min(y, ny); yy <= Math.max(y, ny); yy++) for (int xx = Math.min(x, nx); xx <= Math.max(x, nx); xx++) {
					if (data.flagAt(xx, yy) == ChartPlotterCollisionData.OPEN) continue;
					if (new java.awt.geom.Rectangle2D.Double(xx - 1e-8, yy - 1e-8, 1 + 2e-8, 1 + 2e-8).intersectsLine(x + 0.5, y + 0.5, nx + 0.5, ny + 0.5)) open = false;
				}
				if (!open) continue;
				int b = (nx + ny * 32) * 16 + d;
				int next = entry[1] + motion.cost[d] + turn(a & 15, d, bias);
				if (next >= distance[b]) continue;
				distance[b] = next;
				queue.add(new int[]{b, next});
			}
		}
		return Integer.MAX_VALUE;
	}
	private static void assertCovered(Path2D shape, ChartPlotterRouteTerrain terrain, int origin, ChartPlotterRouteHull.Mask mask) {
		java.awt.geom.Rectangle2D bounds = shape.getBounds2D();
		for (int y = (int) Math.floor(bounds.getMinY()); y < Math.ceil(bounds.getMaxY()); y++) for (int x = (int) Math.floor(bounds.getMinX()); x < Math.ceil(bounds.getMaxX()); x++) {
			if (!shape.intersects(x + 1e-8, y + 1e-8, 1 - 2e-8, 1 - 2e-8)) continue;
			int cell = terrain.at(x, y);
			terrain.clearance[cell] = 0;
			terrain.open[cell >>> 6] &= ~(1L << cell);
			assertFalse("missed tile=" + x + "," + y, mask.clear(terrain, origin));
			terrain.clearance[cell] = 1;
			terrain.open[cell >>> 6] |= 1L << cell;
		}
	}
	private static Path2D polygon(WorldEntityConfig config, double x, double y, int orientation) {
		double cos = Perspective.COSINE[orientation] / 65536.0;
		double sin = Perspective.SINE[orientation] / 65536.0;
		Path2D.Double shape = new Path2D.Double();
		for (int i = 0; i < 4; i++) {
			double px = config.getBoundsX() / 128.0 + (i == 0 || i == 3 ? -1 : 1) * config.getBoundsWidth() / 256.0;
			double py = config.getBoundsY() / 128.0 + (i < 2 ? -1 : 1) * config.getBoundsHeight() / 256.0;
			double wx = x + cos * px + sin * py;
			double wy = y + cos * py - sin * px;
			if (i == 0) shape.moveTo(wx, wy);
			else shape.lineTo(wx, wy);
		}
		shape.closePath();
		return shape;
	}
}
