package com.chartplotter.route;

import com.chartplotter.collision.ChartPlotterCollisionData;
import net.runelite.api.WorldEntityConfig;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class ChartPlotterRouteDistancesTest {
	@Test
	public void chunkConnectivityRequiresAnOpenSharedEdgeAndHonoursArrivalRadius() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-2, -2, 3, 3);
		for (int y = -16; y < 32; y++) ChartPlotterRoutingAudit.block(chunks, 8, y);
		ChartPlotterCollisionData blocked = new ChartPlotterCollisionData(chunks);
		assertFalse(ChartPlotterRouteTerrain.connected(blocked, 0, 0, 24, 0, 14, () -> false));
		assertTrue(ChartPlotterRouteTerrain.connected(blocked, 0, 0, 21, 0, 14, () -> false));
		chunks.put(ChartPlotterCollisionData.key(1, 3), new ChartPlotterCollisionData.Chunk(-1L, 0));
		ChartPlotterCollisionData open = new ChartPlotterCollisionData(chunks);
		assertTrue(ChartPlotterRouteTerrain.connected(open, 0, 0, 24, 0, 0, () -> false));
		assertTrue(ChartPlotterRouteTerrain.connected(open, 24, 0, 0, 0, 0, () -> false));
		assertTrue(ChartPlotterRouteTerrain.connected(open, 0, -10, 0, 24, 0, () -> false));
		assertTrue(ChartPlotterRouteTerrain.connected(open, 0, 24, 0, -10, 0, () -> false));
		assertFalse(ChartPlotterRouteTerrain.connected(open, 0, 0, 24, 0, 0, () -> true));
		chunks.clear();
		chunks.put(ChartPlotterCollisionData.key(0, 0), new ChartPlotterCollisionData.Chunk(-1L, 0));
		chunks.put(ChartPlotterCollisionData.key(1, 1), new ChartPlotterCollisionData.Chunk(-1L, 0));
		assertFalse(ChartPlotterRouteTerrain.connected(new ChartPlotterCollisionData(chunks), 7, 7, 8, 8, 0, () -> false));
		chunks.put(ChartPlotterCollisionData.key(0, 1), new ChartPlotterCollisionData.Chunk(-1L, 0));
		assertTrue(ChartPlotterRouteTerrain.connected(new ChartPlotterCollisionData(chunks), 7, 7, 8, 8, 0, () -> false));
		chunks = ChartPlotterRoutingAudit.open(-2, -2, 3, 3);
		for (int y = -16; y < 32; y++) ChartPlotterRoutingAudit.block(chunks, 12, y);
		assertFalse(ChartPlotterRouteTerrain.connected(new ChartPlotterCollisionData(chunks), 0, 0, 24, 0, 0, () -> false));
	}
	@Test
	public void chunkFloodMatchesTileFloodAcrossDisconnectedPiecesAndUnknownTiles() {
		Random random = new Random(4190);
		int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int trial = 0; trial < 40; trial++) {
			Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(0, 0, 3, 3);
			for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) if ((x != 0 || y != 0) && random.nextInt(3) == 0) ChartPlotterRoutingAudit.block(chunks, x, y);
			chunks.remove(ChartPlotterCollisionData.key(2, 2));
			ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
			boolean[] seen = new boolean[1024];
			java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
			seen[0] = true;
			queue.add(0);
			while (!queue.isEmpty()) {
				int a = queue.remove();
				int x = a % 32;
				int y = a / 32;
				for (int[] direction : directions) {
					int nx = x + direction[0];
					int ny = y + direction[1];
					if (nx < 0 || ny < 0 || nx >= 32 || ny >= 32 || seen[nx + ny * 32] || data.flagAt(nx, ny) != ChartPlotterCollisionData.OPEN) continue;
					seen[nx + ny * 32] = true;
					queue.add(nx + ny * 32);
				}
			}
			for (int query = 0; query < 20; query++) {
				int x = random.nextInt(32);
				int y = random.nextInt(32);
				int radius = query % 3;
				boolean expected = false;
				for (int ny = Math.max(0, y - radius); ny <= Math.min(31, y + radius); ny++) for (int nx = Math.max(0, x - radius); nx <= Math.min(31, x + radius); nx++) expected |= seen[nx + ny * 32];
				assertEquals(expected, ChartPlotterRouteTerrain.connected(data, 0, 0, x, y, radius, () -> false));
			}
		}
	}
	@Test
	public void potentialIsConsistentAcrossSpeedsBearingsAndArrivalAreas() {
		Random random = new Random(8721);
		for (int trial = 0; trial < 64; trial++) {
			double speed = trial < 32 ? 0.5 + trial * 0.5 : 0.5 + random.nextDouble() * 15.5;
			ChartPlotterRouteMotion motion = new ChartPlotterRouteMotion(speed, 0.5, 0.5);
			for (int d = 0; d < 16; d++) assertTrue(motion.lowerBound(motion.x[d], motion.y[d]) <= motion.cost[d]);
			for (int sample = 0; sample < 100; sample++) {
				int x = random.nextInt(4001) - 2000;
				int y = random.nextInt(4001) - 2000;
				int radius = random.nextInt(15);
				int a = motion.lowerBound(Math.max(0, Math.abs(x) - radius), Math.max(0, Math.abs(y) - radius));
				for (int d = 0; d < 16; d++) {
					int b = motion.lowerBound(Math.max(0, Math.abs(x + motion.x[d]) - radius), Math.max(0, Math.abs(y + motion.y[d]) - radius));
					assertTrue(Math.abs(a - b) <= motion.cost[d]);
				}
			}
			assertEquals(0, motion.lowerBound(0, 0));
			assertEquals(ChartPlotterRouteTerrain.MAX_COST, motion.lowerBound(8 << 20, 0));
		}
	}
	@Test
	public void resumedDistancesMatchIndependentDijkstraInArbitraryQueryOrder() {
		Random random = new Random(3297);
		for (double speed : new double[]{0.5, 1, 3}) for (WorldEntityConfig config : new WorldEntityConfig[]{null, ChartPlotterRoutingAudit.offsetHull()}) {
			Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(0, 0, 7, 5);
			for (int x = 0; x < 64; x++) for (int y = 0; y < 48; y++) if (x == 31 && y < 35 || random.nextInt(100) == 0) ChartPlotterRoutingAudit.block(chunks, x, y);
			ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
			ChartPlotterRouteMotion motion = new ChartPlotterRouteMotion(speed, 0.5, 0.5);
			ChartPlotterRouteHull hull = new ChartPlotterRouteHull(config, motion, 0, false);
			ChartPlotterRouteTerrain terrain = ChartPlotterRouteTerrain.create(data, motion, 5, 5, () -> false);
			assertNotNull(terrain);
			for (int[] goal : new int[][]{{54, 12, 2}, {10, 25, 0}}) {
				int[] expected = dijkstra(data, motion, hull, goal[0], goal[1], goal[2]);
				ChartPlotterRouteDistances distance = new ChartPlotterRouteDistances(data, terrain, hull, goal[0], goal[1], goal[2], 5, 5, 14, () -> false);
				List<Integer> order = new ArrayList<>();
				for (int a = 0; a < expected.length; a++) order.add(a);
				Collections.shuffle(order, random);
				for (int a : order) assertEquals("speed=" + speed + " tile=" + a, expected[a], distance.get(terrain.at(a % 64, a / 64)));
				assertEquals(0, distance.get(-1));
				assertEquals(0, distance.get(terrain.clearance.length));
			}
		}
	}
	@Test
	public void localQueriesAvoidWholeMapExpansionAndCancellationCanResume() {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(0, 0, 127, 127));
		ChartPlotterRouteMotion motion = new ChartPlotterRouteMotion(3, 0.5, 0.5);
		ChartPlotterRouteHull hull = new ChartPlotterRouteHull(null, motion, 0, false);
		ChartPlotterRouteTerrain terrain = ChartPlotterRouteTerrain.create(data, motion, 400, 512, () -> false);
		assertNotNull(terrain);
		AtomicBoolean cancel = new AtomicBoolean();
		ChartPlotterRouteDistances distance = new ChartPlotterRouteDistances(data, terrain, hull, 600, 512, 0, 400, 512, 0, cancel::get);
		assertEquals(200001, distance.get(terrain.at(400, 512)));
		assertTrue(distance.expanded < terrain.clearance.length / 8);
		assertTrue(distance.bytes() < terrain.clearance.length * 5L);
		cancel.set(true);
		assertEquals(-1, distance.get(terrain.at(0, 0)));
		cancel.set(false);
		assertEquals(600001, distance.get(terrain.at(0, 512)));
		cancel.set(true);
		ChartPlotterRouteDistances cancelled = new ChartPlotterRouteDistances(data, terrain, hull, 600, 512, 0, 400, 512, 0, cancel::get);
		cancel.set(false);
		assertEquals(-1, cancelled.get(terrain.at(600, 512)));
	}
	private static int[] dijkstra(ChartPlotterCollisionData data, ChartPlotterRouteMotion motion, ChartPlotterRouteHull hull, int gx, int gy, int radius) {
		int[] distance = new int[64 * 48];
		PriorityQueue<int[]> queue = new PriorityQueue<>(Comparator.comparingInt(a -> a[1]));
		for (int y = gy - radius; y <= gy + radius; y++) for (int x = gx - radius; x <= gx + radius; x++) {
			if (!data.clear(x + 0.5, y + 0.5, gx + 0.5, gy + 0.5) || hull.flag(data, x, y, -1) != ChartPlotterCollisionData.OPEN) continue;
			distance[x + y * 64] = 1;
			queue.add(new int[]{x + y * 64, 1});
		}
		while (!queue.isEmpty()) {
			int[] entry = queue.remove();
			if (distance[entry[0]] != entry[1]) continue;
			int x = entry[0] % 64;
			int y = entry[0] / 64;
			for (int d = 0; d < 16; d++) {
				int nx = x + motion.x[d];
				int ny = y + motion.y[d];
				if (nx < 0 || ny < 0 || nx >= 64 || ny >= 48) continue;
				int b = nx + ny * 64;
				int cost = entry[1] + motion.cost[d];
				if (distance[b] != 0 && distance[b] <= cost || !data.clear(x + 0.5, y + 0.5, nx + 0.5, ny + 0.5) || hull.core.flag(data, nx, ny) != ChartPlotterCollisionData.OPEN) continue;
				distance[b] = cost;
				queue.add(new int[]{b, cost});
			}
		}
		return distance;
	}
}
