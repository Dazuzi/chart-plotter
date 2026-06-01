package com.chartplotter.runtime;
import com.chartplotter.ChartPlotterCacheOverlay;
import com.chartplotter.ChartPlotterConfig;
public final class ChartPlotterFeatures {
	public final boolean routes;
	public final boolean cacheView;
	public final boolean edit;
	public final boolean worldOverlay;
	public final boolean minimapOverlay;
	public final boolean worldMapOverlay;
	public final boolean input;
	public final boolean tracking;
	private ChartPlotterFeatures(boolean world, boolean minimap, boolean worldMap, ChartPlotterCacheOverlay cache, boolean edit) {
		routes = world || minimap || worldMap;
		cacheView = cache != ChartPlotterCacheOverlay.OFF;
		this.edit = edit;
		worldOverlay = world || cache.world;
		minimapOverlay = minimap;
		worldMapOverlay = worldMap || cache.worldMap || edit;
		input = routes || edit;
		tracking = routes || cacheView || edit;
	}
	public static ChartPlotterFeatures of(ChartPlotterConfig config) {return of(config.worldEnabled(), config.minimapEnabled(), config.worldMapEnabled(), config.cacheOverlay(), config.nodeEditor());}
	public static ChartPlotterFeatures of(boolean world, boolean minimap, boolean worldMap, ChartPlotterCacheOverlay cache, boolean edit) {return new ChartPlotterFeatures(world, minimap, worldMap, cache, edit);}
	public static ChartPlotterFeatures off() {return of(false, false, false, ChartPlotterCacheOverlay.OFF, false);}
	public boolean cache(boolean boarded) {return edit || boarded && (routes || cacheView);}
}
