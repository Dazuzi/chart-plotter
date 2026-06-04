package com.chartplotter.runtime;
import com.chartplotter.ChartPlotterCacheOverlay;
import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.ChartPlotterTurnEta;
public final class ChartPlotterFeatures {
	public final boolean course;
	public final boolean chart;
	public final boolean routes;
	public final boolean cacheView;
	public final boolean edit;
	public final boolean worldOverlay;
	public final boolean minimapOverlay;
	public final boolean worldMapOverlay;
	public final boolean input;
	public final boolean tracking;
	private ChartPlotterFeatures(boolean wc, boolean wp, boolean wch, boolean mc, boolean mp, boolean mch, boolean wmc, boolean wmp, boolean wmch, ChartPlotterCacheOverlay cache, boolean edit, boolean nextTurn) {
		boolean world = wc || wp || wch;
		boolean minimap = mc || mp || mch;
		boolean worldMap = wmc || wmp || wmch;
		course = wc || wp || mc || mp || wmc || wmp;
		chart = wch || mch || wmch;
		routes = course || chart;
		cacheView = cache != ChartPlotterCacheOverlay.OFF;
		this.edit = edit;
		worldOverlay = world || cache.world || nextTurn;
		minimapOverlay = minimap;
		worldMapOverlay = worldMap || chart || cache.worldMap || edit;
		input = routes || edit;
		tracking = routes || cacheView || edit || nextTurn;
	}
	public static ChartPlotterFeatures of(ChartPlotterConfig config) {return of(config.worldLineMode().on, config.worldProjectedLineMode().on, config.worldChartLine(), config.minimapLineMode().on, config.minimapProjectedLineMode().on, config.minimapChartLine(), config.worldMapLineMode().on, config.worldMapProjectedLineMode().on, config.worldMapChartLine(), config.cacheOverlay(), config.nodeEditor(), config.courseTurnEta() != ChartPlotterTurnEta.OFF);}
	public static ChartPlotterFeatures of(boolean wc, boolean wp, boolean wch, boolean mc, boolean mp, boolean mch, boolean wmc, boolean wmp, boolean wmch, ChartPlotterCacheOverlay cache, boolean edit, boolean nextTurn) {return new ChartPlotterFeatures(wc, wp, wch, mc, mp, mch, wmc, wmp, wmch, cache, edit, nextTurn);}
	public static ChartPlotterFeatures off() {return of(false, false, false, false, false, false, false, false, false, ChartPlotterCacheOverlay.OFF, false, false);}
	public boolean cache(boolean boarded) {return edit || boarded && (routes || cacheView);}
}
