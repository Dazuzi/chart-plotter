package com.chartplotter.runtime;
import com.chartplotter.ChartPlotterCacheOverlay;
import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.ChartPlotterTurnEta;
public final class ChartPlotterFeatures {
	public final boolean routes;
	public final boolean cacheView;
	public final boolean edit;
	public final boolean worldOverlay;
	public final boolean minimapOverlay;
	public final boolean worldMapOverlay;
	public final boolean input;
	public final boolean tracking;
	public final boolean nextTurn;
	private ChartPlotterFeatures(boolean world, boolean minimap, boolean worldMap, ChartPlotterCacheOverlay cache, boolean edit, boolean nextTurn) {
		routes = world || minimap || worldMap;
		cacheView = cache != ChartPlotterCacheOverlay.OFF;
		this.edit = edit;
		this.nextTurn = nextTurn;
		worldOverlay = world || cache.world || nextTurn;
		minimapOverlay = minimap;
		worldMapOverlay = worldMap || cache.worldMap || edit;
		input = routes || edit;
		tracking = routes || cacheView || edit || nextTurn;
	}
	public static ChartPlotterFeatures of(ChartPlotterConfig config) {return of(config.worldLineMode().on, config.minimapLineMode().on, config.worldMapLineMode().on, config.cacheOverlay(), config.nodeEditor(), config.courseTurnEta() != ChartPlotterTurnEta.OFF);}
	public static ChartPlotterFeatures of(boolean world, boolean minimap, boolean worldMap, ChartPlotterCacheOverlay cache, boolean edit, boolean nextTurn) {return new ChartPlotterFeatures(world, minimap, worldMap, cache, edit, nextTurn);}
	public static ChartPlotterFeatures off() {return of(false, false, false, ChartPlotterCacheOverlay.OFF, false, false);}
	public boolean cache(boolean boarded) {return edit || boarded && (routes || cacheView);}
}
