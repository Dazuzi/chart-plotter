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
	public final boolean infoOverlay;
	public final boolean scene;
	public final boolean input;
	public final boolean tracking;
	private ChartPlotterFeatures(boolean wc, boolean wp, boolean wch, boolean mc, boolean mp, boolean mch, boolean wmc, boolean wmp, boolean wmch, ChartPlotterCacheOverlay cache, boolean edit, boolean nextTurn, boolean infoTrip, boolean boatSpeed) {
		boolean world = wc || wp || wch;
		boolean minimap = mc || mp || mch;
		boolean worldMap = wmc || wmp || wmch;
		course = wc || wp || mc || mp || wmc || wmp;
		chart = wch || mch || wmch || infoTrip;
		routes = course || chart;
		cacheView = cache != ChartPlotterCacheOverlay.OFF;
		this.edit = edit;
		worldOverlay = world || cache.world || nextTurn;
		minimapOverlay = minimap;
		worldMapOverlay = worldMap || chart || cache.worldMap || edit;
		infoOverlay = infoTrip || boatSpeed;
		scene = worldOverlay || mc || mp;
		input = chart || edit || minimapOverlay;
		tracking = routes || cacheView || edit || nextTurn || infoOverlay;
	}
	public static ChartPlotterFeatures of(ChartPlotterConfig config) {return of(config.worldLineMode().on, config.worldProjectedLineMode().on, config.worldChartLine(), config.minimapLineMode().on, config.minimapProjectedLineMode().on, config.minimapChartLine(), config.worldMapLineMode().on, config.worldMapProjectedLineMode().on, config.worldMapChartLine(), config.cacheOverlay(), config.nodeEditor(), config.courseTurnEta() != ChartPlotterTurnEta.OFF, config.infoStopProgress() || config.infoTripEta() || config.infoStopEta() || config.infoTurnEta(), config.infoBoatSpeed());}
	public static ChartPlotterFeatures of(boolean wc, boolean wp, boolean wch, boolean mc, boolean mp, boolean mch, boolean wmc, boolean wmp, boolean wmch, ChartPlotterCacheOverlay cache, boolean edit, boolean nextTurn, boolean infoTrip, boolean boatSpeed) {return new ChartPlotterFeatures(wc, wp, wch, mc, mp, mch, wmc, wmp, wmch, cache, edit, nextTurn, infoTrip, boatSpeed);}
	public static ChartPlotterFeatures off() {return of(false, false, false, false, false, false, false, false, false, ChartPlotterCacheOverlay.OFF, false, false, false, false);}
	public boolean cache(boolean boarded) {return edit || boarded && (routes || cacheView);}
}
