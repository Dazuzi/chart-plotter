package com.chartplotter.runtime;

import com.chartplotter.ChartPlotterCacheOverlay;
import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.ChartPlotterEtaMode;
import com.chartplotter.ChartPlotterLineMode;
import net.runelite.api.Point;
import org.junit.Test;

import java.awt.*;

import static org.junit.Assert.*;

public class ChartPlotterFeaturesTest {
	@Test
	public void courseAndChartModesSelectInputAndSceneFeatures() {
		ChartPlotterFeatures world = ChartPlotterFeatures.of(true, false, false, false, false, false, false, false, false, ChartPlotterCacheOverlay.OFF, false, false, false);
		ChartPlotterFeatures minimap = ChartPlotterFeatures.of(false, false, false, true, false, false, false, false, false, ChartPlotterCacheOverlay.OFF, false, false, false);
		ChartPlotterFeatures minimapChart = ChartPlotterFeatures.of(false, false, false, false, false, true, false, false, false, ChartPlotterCacheOverlay.OFF, false, false, false);
		ChartPlotterFeatures worldMap = ChartPlotterFeatures.of(false, false, false, false, false, false, true, false, false, ChartPlotterCacheOverlay.OFF, false, false, false);
		ChartPlotterFeatures chart = ChartPlotterFeatures.of(false, false, true, false, false, false, false, false, false, ChartPlotterCacheOverlay.OFF, false, false, false);
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
	public void speedPanelOnlyTracksMotion() {
		ChartPlotterFeatures features = ChartPlotterFeatures.of(false, false, false, false, false, false, false, false, false, ChartPlotterCacheOverlay.OFF, false, false, true);
		assertTrue(features.infoOverlay);
		assertTrue(features.tracking);
		assertFalse(features.routes);
		assertFalse(features.chart);
		assertFalse(features.input);
		assertFalse(features.scene);
		assertFalse(features.worldOverlay);
		assertFalse(features.minimapOverlay);
		assertFalse(features.worldMapOverlay);
		assertFalse(features.cache(true));
	}
	@Test
	public void etaPanelSupportsChartingWithoutCourseOverlays() {
		ChartPlotterFeatures features = ChartPlotterFeatures.of(false, false, false, false, false, false, false, false, false, ChartPlotterCacheOverlay.OFF, false, true, false);
		assertTrue(features.infoOverlay);
		assertTrue(features.tracking);
		assertTrue(features.chart);
		assertTrue(features.input);
		assertTrue(features.worldMapOverlay);
		for (boolean boarded : new boolean[]{false, true}) assertEquals(boarded, features.cache(boarded));
		assertFalse(features.scene);
		assertFalse(features.worldOverlay);
		assertFalse(features.minimapOverlay);
	}
	@Test
	public void stopProgressOptionControlsTripTrackingIndependently() {
		for (boolean enabled : new boolean[]{false, true}) {
			ChartPlotterConfig config = new ChartPlotterConfig() {
				@Override
				public ChartPlotterLineMode worldLineMode() {return ChartPlotterLineMode.OFF;}
				@Override
				public ChartPlotterLineMode worldMapLineMode() {return ChartPlotterLineMode.OFF;}
				@Override
				public ChartPlotterEtaMode courseTurnEta() {return ChartPlotterEtaMode.OFF;}
				@Override
				public boolean infoStopProgress() {return enabled;}
			};
			ChartPlotterFeatures features = ChartPlotterFeatures.of(config);
			assertEquals(enabled, features.infoOverlay);
			assertEquals(enabled, features.chart);
			assertEquals(enabled, features.routes);
			assertEquals(enabled, features.tracking);
			assertEquals(enabled, features.input);
			assertEquals(enabled, features.worldMapOverlay);
			assertEquals(enabled, features.cache(true));
			assertFalse(features.cacheView);
			assertFalse(features.course);
			assertFalse(features.scene);
			assertFalse(features.worldOverlay);
			assertFalse(features.minimapOverlay);
		}
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
	public void worldMapCoordinatesRoundTripWithinOnePixel() {
		ChartPlotterWorldMap map = new ChartPlotterWorldMap(null);
		for (float zoom : new float[]{1, 1.5f, 2, 3, 3.5f, 4, 8}) for (int width : new int[]{800, 801}) for (int height : new int[]{600, 601}) for (double offset : new double[]{0, 0.25, 0.5, 0.75}) {
			ChartPlotterWorldMap.State state = new ChartPlotterWorldMap.State(null, zoom, new Rectangle(40, 50, width, height), (int) Math.ceil(width / (double) zoom), (int) Math.ceil(height / (double) zoom), new Point(3200, 3300), zoom - Math.ceil(zoom / 2.0));
			Point point = new Point(map.pointX(state, 3214 + offset), map.pointY(state, 3288 + offset));
			assertEquals(3214 + offset, map.worldX(point, state), 0.5 / zoom + 1e-9);
			assertEquals(3288 + offset, map.worldY(point, state), 0.5 / zoom + 1e-9);
			assertEquals(point.getX(), map.mapX(state, 3200, (3214 - 3200) * 128 + (int) (offset * 128)));
			assertEquals(point.getY(), map.mapY(state, 3300, (3288 - 3300) * 128 + (int) (offset * 128)));
		}
	}
	@Test
	public void worldMapUsesRuneLiteTileOriginsForOddViewportDimensions() {
		ChartPlotterWorldMap map = new ChartPlotterWorldMap(null);
		ChartPlotterWorldMap.State state = new ChartPlotterWorldMap.State(null, 4, new Rectangle(40, 50, 801, 601), 201, 151, new Point(3200, 3300), 2);
		assertEquals(498, map.pointX(state, 3214.5));
		assertEquals(397, map.pointY(state, 3288.5));
	}
}
