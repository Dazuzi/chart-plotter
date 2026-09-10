package com.chartplotter.route;

import com.chartplotter.collision.ChartPlotterCollisionData;
import net.runelite.api.WorldEntityConfig;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class ChartPlotterRouteMaskTest {
	@Test
	public void packedMasksMatchTileChecksAcrossWordAndTerrainBoundaries() {
		Random random = new Random(7812);
		for (WorldEntityConfig config : new WorldEntityConfig[]{ChartPlotterRoutingAudit.offsetHull(), ChartPlotterRoutingAudit.config(8192, 8192)}) {
			Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-10, -10, 21, 21);
			for (int i = 0; i < 100; i++) ChartPlotterRoutingAudit.block(chunks, random.nextInt(256) - 80, random.nextInt(256) - 80);
			chunks.remove(ChartPlotterCollisionData.key(0, 0));
			ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
			ChartPlotterRouteMotion motion = new ChartPlotterRouteMotion(3, 0.5, 0.125);
			ChartPlotterRouteHull hull = new ChartPlotterRouteHull(config, motion, 0, false);
			ChartPlotterRouteTerrain terrain = ChartPlotterRouteTerrain.create(data, motion, -20, 0, () -> false);
			assertNotNull(terrain);
			List<ChartPlotterRouteHull.Mask> masks = new ArrayList<>(Arrays.asList(hull.hull));
			masks.addAll(Arrays.asList(hull.move));
			masks.addAll(Arrays.asList(hull.turn));
			masks.addAll(Arrays.asList(hull.point));
			masks.add(hull.circle);
			masks.add(hull.core);
			for (int i = 0; i < 256; i++) {
				int x = i - 80;
				int y = random.nextInt(256) - 80;
				for (ChartPlotterRouteHull.Mask mask : masks) assertEquals(data.flagAt(x, y) == ChartPlotterCollisionData.OPEN && mask.flag(data, x, y) == ChartPlotterCollisionData.OPEN, mask.clear(terrain, terrain.at(x, y)));
			}
			ChartPlotterRouteTerrain narrower = ChartPlotterRouteTerrain.create(new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-2, -2, 2, 2)), motion, 0, 0, () -> false);
			assertNotNull(narrower);
			for (ChartPlotterRouteHull.Mask mask : masks) {
				mask.clear(narrower, narrower.at(0, 0));
				assertEquals(mask.flag(data, 30, 30) == ChartPlotterCollisionData.OPEN, mask.clear(terrain, terrain.at(30, 30)));
			}
		}
	}
	@Test
	public void cachedTurnArcsMatchEveryCompleteTurnMask() {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(-4, -4, 8, 8);
		for (int y = -10; y <= 10; y++) ChartPlotterRoutingAudit.block(chunks, 20, y);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		for (double speed : new double[]{1, 3}) {
			ChartPlotterRouteFinder search = new ChartPlotterRouteFinder(data, ChartPlotterRoutingAudit.offsetHull(), 0, 0, 40, 0, 5, speed, 140, () -> false);
			assertEquals(ChartPlotterRoute.OK, search.find().status);
			assertNotNull(search.terrain);
			int checked = 0;
			for (int x = 0; x < 40; x += 3) for (int y = -15; y <= 15; y += 3) {
				int tile = search.terrain.at(x, y);
				if (search.pages[tile >>> 8] == null) continue;
				for (int a = 0; a < 16; a++) for (int b = 15; b >= 0; b--) {
					boolean expected = a != b && search.hull.circle.flag(data, x, y) != ChartPlotterCollisionData.OPEN && search.hull.turn[a * 16 + b].flag(data, x, y) != ChartPlotterCollisionData.OPEN;
					assertEquals(expected, search.turnBlocked(x, y, a, b));
					assertEquals(expected, search.turnBlocked(x, y, b, a));
					checked++;
				}
			}
			assertTrue(checked > 0);
		}
	}
}
