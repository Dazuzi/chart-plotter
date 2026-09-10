package com.chartplotter.route;

import com.chartplotter.collision.ChartPlotterCollisionData;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ChartPlotterRouteTargetTest {
	@Test
	public void boundedPointPathsMatchIndependentRelaxation() {
		Random random = new Random(58214);
		for (int trial = 0; trial < 20; trial++) {
			Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
			for (int y = -16; y <= 16; y++) for (int x = -16; x <= 16; x++) if ((x != 0 || y != 0) && random.nextInt(5) == 0) ChartPlotterRoutingAudit.block(chunks, x, y);
			ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
			Set<Long> expected = region(data);
			ChartPlotterRouteTarget target = ChartPlotterRouteTarget.create(data, 0, 0, () -> false);
			assertNotNull(target);
			for (int y = -16; y <= 16; y++) for (int x = -16; x <= 16; x++) {
				assertEquals("trial=" + trial + " tile=" + x + "," + y, expected.contains(ChartPlotterCollisionData.key(x, y)), target.contains(x, y));
				if (!target.contains(x, y)) continue;
				long[] path = target.path(data, x, y);
				assertEquals(ChartPlotterCollisionData.key(x, y), path[0]);
				assertEquals(ChartPlotterCollisionData.key(0, 0), path[path.length - 1]);
				double length = 0;
				for (int i = 1; i < path.length; i++) {
					int ax = (int) (path[i - 1] >> 32);
					int ay = (int) path[i - 1];
					int bx = (int) (path[i] >> 32);
					int by = (int) path[i];
					assertTrue(data.clear(ax + 0.5, ay + 0.5, bx + 0.5, by + 0.5));
					length += Math.hypot(bx - ax, by - ay);
				}
				assertTrue(length <= 20 + 1e-9);
			}
		}
	}
	@Test
	public void connectionGeometryJoinsFractionalHullOriginsToTileCenters() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-4, -4, 4, 4));
		ChartPlotterRouteTarget target = ChartPlotterRouteTarget.create(data, 8, 0, () -> false);
		for (double[] offset : new double[][]{{0.5, 0.5}, {0, 0.25}, {0.75, 0.125}}) {
			ChartPlotterRouteMotion motion = new ChartPlotterRouteMotion(1, offset[0], offset[1]);
			ChartPlotterRouteHull hull = new ChartPlotterRouteHull(null, motion, 0, false);
			ChartPlotterRoute route = ChartPlotterRoute.ok(-12, 0, 8, 0, new int[]{-10, 0}, new int[]{0, 0}, 2, 5, 100).plan(motion, hull, -1).connect(data, ChartPlotterRouteTarget.create(data, -12, 0, () -> false), target);
			assertEquals(-11.5, route.departureX(0), 0);
			assertEquals(0.5, route.departureY(0), 0);
			assertEquals(-10 + offset[0], route.departureX(route.departure.length - 1), 0);
			assertEquals(offset[1], route.departureY(route.departure.length - 1), 0);
			assertTrue(ChartPlotterRoutingAudit.connectionClear(data, route));
			assertTrue(route.valid(data, () -> false));
			ChartPlotterRoute pruned = route.advance(0 + offset[0], 0 + offset[1], 20, 32, 0);
			assertNotNull(pruned);
			assertTrue(ChartPlotterRoutingAudit.connectionClear(data, pruned));
			assertSame(route.connection, pruned.connection);
			assertEquals(0, pruned.departure.length);
		}
	}
	@Test
	public void proximityCannotHideALongPointDetour() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 4, 4);
		for (int y = -10; y <= 10; y++) ChartPlotterRoutingAudit.block(chunks, 0, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRouteTarget target = ChartPlotterRouteTarget.create(data, 4, 0, () -> false);
		assertNotNull(target);
		ChartPlotterRouteComponent component = ChartPlotterRouteComponent.create(data, -4, 0, () -> false);
		assertNotNull(component);
		assertTrue(component.contains(4, 0));
		assertFalse(target.contains(-4, 0));
		assertTrue(target.contains(4, 14));
		assertFalse(target.contains(4, 15));
	}
	@Test
	public void diagonalContactCannotConnectIsolatedTiles() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = new HashMap<>();
		chunks.put(ChartPlotterCollisionData.key(0, 0), new ChartPlotterCollisionData.Chunk(-1L, ~(1L | 1L << 9)));
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRouteTarget target = ChartPlotterRouteTarget.create(data, 1, 1, () -> false);
		assertNotNull(target);
		assertTrue(target.contains(1, 1));
		assertFalse(target.contains(0, 0));
		ChartPlotterRouteComponent component = ChartPlotterRouteComponent.create(data, 0, 0, () -> false);
		assertNotNull(component);
		assertFalse(component.contains(1, 1));
	}
	@Test
	public void unknownTilesAndCancellationNeverProduceAConnection() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = new HashMap<>();
		chunks.put(ChartPlotterCollisionData.key(0, 0), new ChartPlotterCollisionData.Chunk(1, 0));
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRouteTarget target = ChartPlotterRouteTarget.create(data, 0, 0, () -> false);
		assertNotNull(target);
		assertFalse(target.contains(1, 0));
		assertNull(ChartPlotterRouteTarget.create(data, 1, 0, () -> false));
		assertNull(ChartPlotterRouteTarget.create(data, 0, 0, () -> true));
		AtomicInteger checks = new AtomicInteger();
		assertNull(ChartPlotterRouteTarget.create(new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-4, -4, 4, 4)), 0, 0, () -> checks.incrementAndGet() > 3));
	}
	@Test
	public void cachedRegionRequiresUnchangedLocalCollisionChunks() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-8, -8, 8, 8);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRouteTarget target = ChartPlotterRouteTarget.create(data, 0, 0, () -> false);
		assertNotNull(target);
		ChartPlotterRoutingAudit.block(chunks, 40, 40);
		assertTrue(target.matches(new ChartPlotterCollisionData(chunks)));
		ChartPlotterRoutingAudit.block(chunks, 1, 0);
		ChartPlotterCollisionData changed = new ChartPlotterCollisionData(chunks);
		assertFalse(target.matches(changed));
		ChartPlotterRouteTarget rebuilt = ChartPlotterRouteTarget.create(changed, 0, 0, () -> false);
		assertNotNull(rebuilt);
		assertFalse(rebuilt.contains(1, 0));
		assertTrue(target.matches(data));
	}
	private static Set<Long> region(ChartPlotterCollisionData data) {
		double[][] distance = new double[29][29];
		for (double[] row : distance) Arrays.fill(row, Double.POSITIVE_INFINITY);
		if (data.flagAt(0, 0) == ChartPlotterCollisionData.OPEN) distance[14][14] = 0;
		boolean changed;
		do {
			changed = false;
			for (int y = 0; y < 29; y++) for (int x = 0; x < 29; x++) {
				if (data.flagAt(x - 14, y - 14) != ChartPlotterCollisionData.OPEN) continue;
				for (int ny = Math.max(0, y - 1); ny <= Math.min(28, y + 1); ny++) for (int nx = Math.max(0, x - 1); nx <= Math.min(28, x + 1); nx++) {
					if (data.flagAt(nx - 14, y - 14) != ChartPlotterCollisionData.OPEN || data.flagAt(x - 14, ny - 14) != ChartPlotterCollisionData.OPEN) continue;
					double next = distance[ny][nx] + Math.hypot(x - nx, y - ny);
					if (next >= distance[y][x] || next > 20) continue;
					distance[y][x] = next;
					changed = true;
				}
			}
		} while (changed);
		Set<Long> result = new HashSet<>();
		for (int y = 0; y < 29; y++) for (int x = 0; x < 29; x++) if (distance[y][x] <= 20) result.add(ChartPlotterCollisionData.key(x - 14, y - 14));
		return result;
	}
}
