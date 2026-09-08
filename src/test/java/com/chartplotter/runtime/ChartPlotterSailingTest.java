package com.chartplotter.runtime;

import net.runelite.api.Perspective;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldEntityConfig;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ChartPlotterSailingTest {
	private static final WorldEntity SHIP = new WorldEntity() {
		@Override
		public WorldView getWorldView() {return null;}
		@Override
		public LocalPoint getLocalLocation() {return null;}
		@Override
		public LocalPoint getCameraFocus() {return null;}
		@Override
		public int getOrientation() {return 0;}
		@Override
		public LocalPoint getTargetLocation() {return null;}
		@Override
		public int getTargetOrientation() {return 0;}
		@Override
		public LocalPoint transformToMainWorld(LocalPoint point) {return point;}
		@Override
		public boolean isHiddenForOverlap() {return false;}
		@Override
		public WorldEntityConfig getConfig() {return null;}
		@Override
		public int getOwnerType() {return OWNER_TYPE_SELF_PLAYER;}
	};
	@Test
	public void averagesOnlyTheLastFiveSamplesIncludingStops() {
		ChartPlotterSailing sailing = new ChartPlotterSailing(null);
		sailing.average(true);
		sailing.sample(2);
		assertEquals(2, sailing.averageSpeed(), 0);
		sailing.sample(4);
		sailing.sample(6);
		sailing.sample(8);
		sailing.sample(10);
		assertEquals(6, sailing.averageSpeed(), 0);
		sailing.sample(0);
		assertEquals(5.6, sailing.averageSpeed(), 1e-9);
		assertEquals(0, sailing.speed(), 0);
	}
	@Test
	public void disablingAndReenablingAveragingDiscardsHistory() {
		ChartPlotterSailing sailing = new ChartPlotterSailing(null);
		sailing.sample(6);
		assertEquals(0, sailing.averageSpeed(), 0);
		sailing.average(true);
		assertEquals(0, sailing.averageSpeed(), 0);
		sailing.sample(2);
		sailing.average(true);
		assertEquals(2, sailing.averageSpeed(), 0);
		sailing.average(false);
		sailing.sample(10);
		sailing.average(true);
		sailing.sample(4);
		assertEquals(4, sailing.averageSpeed(), 0);
	}
	@Test
	public void motionResetDiscardsSamplesBeforeReboarding() {
		ChartPlotterSailing sailing = new ChartPlotterSailing(null);
		sailing.average(true);
		for (int i = 0; i < 7; i++) sailing.sample(6);
		sailing.clear();
		assertEquals(0, sailing.speed(), 0);
		assertEquals(0, sailing.averageSpeed(), 0);
		sailing.sample(2);
		assertEquals(2, sailing.averageSpeed(), 0);
		for (int i = 0; i < 5; i++) sailing.sample(0);
		assertEquals(0, sailing.averageSpeed(), 0);
	}
	@Test
	public void sceneTransitionPreservesRecentSpeedsUntilMovementResumes() {
		ChartPlotterSailing sailing = new ChartPlotterSailing(null);
		sailing.average(true);
		int x = 0;
		sailing.motion(SHIP, new LocalPoint(x, 0, WorldView.TOPLEVEL), false);
		for (int speed : new int[]{2, 4, 6, 8, 6}) {
			x += speed * Perspective.LOCAL_TILE_SIZE;
			sailing.motion(SHIP, new LocalPoint(x, 0, WorldView.TOPLEVEL), false);
		}
		assertEquals(5.2, sailing.averageSpeed(), 1e-9);
		LocalPoint shifted = new LocalPoint(0, 0, WorldView.TOPLEVEL);
		sailing.scene(SHIP, shifted);
		sailing.motion(SHIP, shifted, true);
		assertEquals(5.2, sailing.averageSpeed(), 1e-9);
		for (int i = 0; i < 2; i++) {
			sailing.motion(SHIP, shifted, false);
			assertEquals(6, sailing.speed(), 0);
			assertEquals(5.2, sailing.averageSpeed(), 1e-9);
		}
		sailing.motion(SHIP, new LocalPoint(4 * Perspective.LOCAL_TILE_SIZE, 0, WorldView.TOPLEVEL), false);
		assertEquals(4, sailing.speed(), 0);
		assertEquals(5.6, sailing.averageSpeed(), 1e-9);
	}
	@Test
	public void rejectedPositionJumpPreservesHistoryWithoutAddingItsSpeed() {
		ChartPlotterSailing sailing = new ChartPlotterSailing(null);
		sailing.average(true);
		sailing.motion(SHIP, new LocalPoint(0, 0, WorldView.TOPLEVEL), false);
		for (int i = 1; i <= 5; i++) sailing.motion(SHIP, new LocalPoint(i * Perspective.LOCAL_TILE_SIZE, 0, WorldView.TOPLEVEL), false);
		sailing.motion(SHIP, new LocalPoint(100 * Perspective.LOCAL_TILE_SIZE, 0, WorldView.TOPLEVEL), false);
		assertEquals(1, sailing.speed(), 0);
		assertEquals(1, sailing.averageSpeed(), 0);
		LocalPoint stopped = new LocalPoint(102 * Perspective.LOCAL_TILE_SIZE, 0, WorldView.TOPLEVEL);
		sailing.motion(SHIP, stopped, false);
		assertEquals(2, sailing.speed(), 0);
		assertEquals(1.2, sailing.averageSpeed(), 1e-9);
		sailing.motion(SHIP, stopped, false);
		assertEquals(0, sailing.speed(), 0);
		assertEquals(1, sailing.averageSpeed(), 0);
	}
}
