package com.chartplotter;
final class ChartPlotterFeatures {
	final boolean routes;
	final boolean cacheView;
	final boolean edit;
	final boolean worldOverlay;
	final boolean minimapOverlay;
	final boolean worldMapOverlay;
	final boolean input;
	final boolean tracking;
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
	static ChartPlotterFeatures of(ChartPlotterConfig config) {return of(config.worldEnabled(), config.minimapEnabled(), config.worldMapEnabled(), config.cacheOverlay(), config.nodeEditor());}
	static ChartPlotterFeatures of(boolean world, boolean minimap, boolean worldMap, ChartPlotterCacheOverlay cache, boolean edit) {return new ChartPlotterFeatures(world, minimap, worldMap, cache, edit);}
	static ChartPlotterFeatures off() {return of(false, false, false, ChartPlotterCacheOverlay.OFF, false);}
	boolean cache(boolean boarded) {return edit || boarded && (routes || cacheView);}
}
