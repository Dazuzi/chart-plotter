package com.chartplotter.runtime;

import com.chartplotter.ChartPlotterCacheOverlay;
import com.chartplotter.ChartPlotterConfig;
import net.runelite.api.Point;
import org.junit.Test;

import java.awt.*;

import static org.junit.Assert.*;

public class ChartPlotterFeaturesTest {
	@Test
	public void registersInputOnlyForInteractiveFeatures() {
		ChartPlotterFeatures world = ChartPlotterFeatures.of(true, false, false, false, false, false, false, false, false, ChartPlotterCacheOverlay.OFF, false, false);
		ChartPlotterFeatures minimap = ChartPlotterFeatures.of(false, false, false, true, false, false, false, false, false, ChartPlotterCacheOverlay.OFF, false, false);
		ChartPlotterFeatures minimapChart = ChartPlotterFeatures.of(false, false, false, false, false, true, false, false, false, ChartPlotterCacheOverlay.OFF, false, false);
		ChartPlotterFeatures worldMap = ChartPlotterFeatures.of(false, false, false, false, false, false, true, false, false, ChartPlotterCacheOverlay.OFF, false, false);
		ChartPlotterFeatures chart = ChartPlotterFeatures.of(false, false, true, false, false, false, false, false, false, ChartPlotterCacheOverlay.OFF, false, false);
		assertFalse(world.input);
		assertTrue(minimap.input);
		assertTrue(minimapChart.input);
		assertFalse(worldMap.input);
		assertTrue(chart.input);
		assertTrue(world.scene);
		assertTrue(minimap.scene);
		assertFalse(minimapChart.scene);
		assertFalse(worldMap.scene);
	}
	@Test
	public void nullProjectionRectangleMatchesDefaultFootprint() {
		float[] x = new float[4];
		float[] y = new float[4];
		ChartPlotterProjection.rect(null, x, y);
		assertArrayEquals(new float[]{128, 128, -128, -128}, x, 0);
		assertArrayEquals(new float[]{-128, 128, 128, -128}, y, 0);
	}
	@Test
	public void defaultColorsAreStableObjects() {
		ChartPlotterConfig config = new ChartPlotterConfig() {};
		assertSame(ChartPlotterConfig.DEFAULT_LINE_COLOR, config.lineColor());
		assertSame(ChartPlotterConfig.DEFAULT_POTENTIAL_COLOR, config.potentialColor());
		assertSame(ChartPlotterConfig.DEFAULT_CHART_COLOR, config.chartColor());
		assertSame(ChartPlotterConfig.DEFAULT_BLOCKED_COLOR, config.blockedColor());
	}
	@Test
	public void worldMapCoordinatesRoundTripWithinOnePixel() {
		ChartPlotterWorldMap map = new ChartPlotterWorldMap(null);
		ChartPlotterWorldMap.State state = new ChartPlotterWorldMap.State(null, 3.5f, new Rectangle(40, 50, 800, 600), 229, 172, new Point(3200, 3300), -0.5);
		Point point = new Point(map.pointX(state, 3214, 0.5), map.pointY(state, 3288, 0.5));
		assertEquals(3214.5, map.worldX(point, state), 0.5 / state.z);
		assertEquals(3288.5, map.worldY(point, state), 0.5 / state.z);
		assertEquals(point.getX(), map.mapX(state, 3200, (3214 - 3200) * 128 + 64));
		assertEquals(point.getY(), map.mapY(state, 3300, (3288 - 3300) * 128 + 64));
	}
}
