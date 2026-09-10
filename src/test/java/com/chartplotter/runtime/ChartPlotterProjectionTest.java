package com.chartplotter.runtime;
import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntityConfig;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Test;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
public class ChartPlotterProjectionTest {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final WorldEntityConfig HULL = new WorldEntityConfig() {
		public int getId() {return 0;}
		public int getCategory() {return 0;}
		public int getBoundsX() {return 0;}
		public int getBoundsY() {return 0;}
		public int getBoundsWidth() {return 64;}
		public int getBoundsHeight() {return 256;}
	};
	private final ChartPlotterSailing sailing = new ChartPlotterSailing(null);
	private final Map<Long, ChartPlotterCollisionData.Chunk> chunks = new HashMap<>();
	@Before
	public void waterAndMotion() {
		for (int x = 400; x < 403; x++) for (int y = 400; y < 403; y++) chunks.put(ChartPlotterCollisionData.key(x, y), new ChartPlotterCollisionData.Chunk(-1L, 0));
		VarbitChanged speed = new VarbitChanged();
		speed.setVarbitId(VarbitID.SAILING_SIDEPANEL_BOAT_BASESPEED);
		speed.setValue(TS);
		sailing.varbit(speed);
		mode(2);
		sailing.sample(1);
	}
	@Test
	public void forecastStopsBeforeHullCollisionAndMarksTheOptionalExtension() {
		block(3208, 3204);
		ChartPlotterProjection.Path stopped = path(false, false, 1536);
		ChartPlotterProjection.Path extended = path(false, true, 1536);
		assertTrue(stopped.blocked);
		assertTrue(stopped.n > 1);
		assertEquals(stopped.n, stopped.blockedAt);
		assertTrue(stopped.x[stopped.n - 1] + HULL.getBoundsHeight() / 2 <= 8 * TS);
		assertTrue(extended.blocked);
		assertEquals(stopped.blockedAt, extended.blockedAt);
		assertTrue(extended.n > stopped.n);
		assertArrayEquals(Arrays.copyOf(stopped.x, stopped.n), Arrays.copyOf(extended.x, stopped.n));
		assertTrue(extended.x[extended.n - 1] > 8 * TS);
		for (int i = 0; i < extended.n; i++) assertEquals(4 * TS + TS / 2, extended.y[i]);
	}
	@Test
	public void reversingChecksTheWaterBehindTheBoat() {
		block(3201, 3204);
		mode(3);
		sailing.sample(0.5);
		ChartPlotterProjection.Path reverse = path(false, false, 1536);
		assertTrue(reverse.reverse);
		assertTrue(reverse.blocked);
		assertTrue(reverse.n > 1);
		assertTrue(reverse.x[reverse.n - 1] < reverse.x[0]);
		assertTrue(reverse.x[reverse.n - 1] - HULL.getBoundsHeight() / 2 >= 2 * TS);
		for (int i = 0; i < reverse.n; i++) assertEquals(1536, reverse.o[i]);
	}
	@Test
	public void slidingContinuesAlongTheShoreWithoutCrossingIt() {
		for (int y = 3200; y < 3224; y++) block(3208, y);
		ChartPlotterProjection.Path stopped = path(false, false, 1280);
		ChartPlotterProjection.Path sliding = path(true, false, 1280);
		assertTrue(stopped.blocked);
		assertFalse(sliding.blocked);
		assertTrue(sliding.y[sliding.n - 1] > stopped.y[stopped.n - 1]);
		boolean slid = false;
		for (int i = 0; i < sliding.n; i++) {
			slid |= sliding.slid(i);
			double extent = (Math.abs(Perspective.COSINE[sliding.o[i]]) * HULL.getBoundsWidth() + Math.abs(Perspective.SINE[sliding.o[i]]) * HULL.getBoundsHeight()) / 131072.0;
			assertTrue("point=" + i, sliding.x[i] + extent <= 8 * TS);
		}
		assertTrue(slid);
	}
	private ChartPlotterProjection.Path path(boolean slide, boolean extension, int heading) {
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterProjection projection = new ChartPlotterProjection(sailing, new ChartPlotterCollisionCache() {
			@Override
			public ChartPlotterCollisionData snapshot() {return data;}
		}, null, new ChartPlotterConfig() {
			@Override
			public boolean sailingSlide() {return slide;}
		});
		return projection.raw(3200, 3200, HULL, new LocalPoint(4 * TS + TS / 2, 4 * TS + TS / 2, WorldView.TOPLEVEL), heading, heading, 10, null, extension);
	}
	private void mode(int value) {
		VarbitChanged mode = new VarbitChanged();
		mode.setVarbitId(VarbitID.SAILING_SIDEPANEL_BOAT_MOVE_MODE);
		mode.setValue(value);
		sailing.varbit(mode);
	}
	private void block(int x, int y) {
		long key = ChartPlotterCollisionData.key(x >> 3, y >> 3);
		ChartPlotterCollisionData.Chunk old = chunks.get(key);
		assertNotNull(old);
		chunks.put(key, new ChartPlotterCollisionData.Chunk(-1L, old.blocked | 1L << ((x & 7) + ((y & 7) << 3))));
	}
}
